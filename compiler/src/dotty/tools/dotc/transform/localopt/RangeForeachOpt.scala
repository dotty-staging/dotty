package dotty.tools.dotc
package transform.localopt

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.Flags.Mutable
import dotty.tools.dotc.core.NameKinds.UniqueName
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.transform.BetaReduce
import dotty.tools.dotc.transform.MegaPhase.MiniPhase

/** Rewrites `foreach` calls on ranges built directly from one of the standard
 *  factories into an equivalent counted `while` loop, so that neither the
 *  `Range` nor (for function literals) the function value is allocated. The
 *  recognized receiver shapes are
 *
 *   - `Range(s, e)`, `Range(s, e, st)`, `Range.inclusive(s, e)`, `Range.inclusive(s, e, st)`
 *   - `s to e`, `s.to(e, st)`, `s until e`, `s.until(e, st)`
 *   - any of the step-less forms above followed by `.by(st)`
 *
 *  For example `(s to e).foreach(f)` becomes (schematically):
 *
 *  {{{
 *  val start = s; val end = e         // arguments that are not simple pure expressions
 *                                     // are evaluated once, in the original order
 *  var n: Long = end.toLong - start.toLong + 1
 *  var i: Int = start
 *  while n > 0 do
 *    f(i)                             // beta-reduced when `f` is a function literal
 *    i += step
 *    n -= 1
 *  }}}
 *
 *  Since `for x <- a to b do ...` desugars to `foreach`, `for` loops over
 *  ranges compile to allocation-free `while` loops as well.
 *
 *  The element count is computed with `Long` arithmetic, so ranges spanning
 *  the whole of `Int` iterate correctly, and the wrap-around of the final
 *  `i += step` is harmless because `n` has reached 0 by then. Evaluation
 *  order and the eager `IllegalArgumentException` thrown by the `Range`
 *  constructor for a zero step are preserved.
 */
class RangeForeachOpt extends MiniPhase:
  import tpd.*

  override def phaseName: String = RangeForeachOpt.name

  override def description: String = RangeForeachOpt.description

  override def transformApply(tree: Apply)(using Context): Tree = tree match
    case Apply(TypeApply(sel @ Select(recv, _), _ :: Nil), f :: Nil)
        if sel.symbol == defn.Range_foreach =>
      staticRange(stripped(recv)) match
        case Some(range) => rewrite(range, f, tree)
        case None => tree
    case _ => tree

  /** A range receiver built in place from start, end, optional step and inclusiveness. */
  private case class StaticRange(start: Tree, end: Tree, step: Option[Tree], isInclusive: Boolean)

  private def stripped(tree: Tree): Tree = tree match
    case Inlined(_, Nil, expansion) => stripped(expansion)
    case Block(Nil, expr) => stripped(expr)
    case Typed(expr, _) => stripped(expr)
    case _ => tree

  private def staticRange(tree: Tree)(using Context): Option[StaticRange] = tree match
    case Apply(fun @ Select(qual, _), args) =>
      val sym = fun.symbol
      def wrappedInt: Option[Tree] = stripped(qual) match
        case Apply(wrap, s :: Nil) if wrap.symbol == defn.LowPriorityImplicits_intWrapper => Some(s)
        case _ => None
      if sym == defn.RangeModule_apply || sym == defn.RangeModule_inclusive then
        args match
          case s :: e :: Nil => Some(StaticRange(s, e, None, sym == defn.RangeModule_inclusive))
          case _ => None
      else if sym == defn.RangeModule_applyWithStep || sym == defn.RangeModule_inclusiveWithStep then
        args match
          case s :: e :: st :: Nil => Some(StaticRange(s, e, Some(st), sym == defn.RangeModule_inclusiveWithStep))
          case _ => None
      else if sym == defn.RichInt_to || sym == defn.RichInt_until then
        (wrappedInt, args) match
          case (Some(s), e :: Nil) => Some(StaticRange(s, e, None, sym == defn.RichInt_to))
          case _ => None
      else if sym == defn.RichInt_toWithStep || sym == defn.RichInt_untilWithStep then
        (wrappedInt, args) match
          case (Some(s), e :: st :: Nil) => Some(StaticRange(s, e, Some(st), sym == defn.RichInt_toWithStep))
          case _ => None
      else if sym == defn.Range_by then
        args match
          case st :: Nil =>
            // Only step-less inner ranges: an explicit inner step would need its own
            // zero check before being discarded, which is not worth supporting.
            staticRange(stripped(qual)) match
              case Some(range) if range.step.isEmpty => Some(range.copy(step = Some(st)))
              case _ => None
          case _ => None
      else None
    case _ => None

  private def rewrite(range: StaticRange, f: Tree, tree: Apply)(using Context): Tree =
    // A function argument that is not a `Function1` (e.g. a `null` literal) is left alone.
    if !f.tpe.widen.derivesFrom(defn.Function1) then return tree
    range.step match
      case Some(Literal(st)) if st.intValue == 0 =>
        return tree // keep the original code and its constructor exception
      case _ =>

    val span = tree.span
    val stats = List.newBuilder[Tree]

    def lit(l: Long): Tree = Literal(Constant(l)).withSpan(span)
    def toLong(t: Tree): Tree = t.select(defn.Int_toLong)

    // Evaluate an operand once, in order. Everything that is not a literal or a
    // pure identifier is bound to a local: re-reading vars or re-executing
    // definitions inside pure blocks on every loop iteration would be wrong.
    def bind(name: String, arg: Tree): Tree = arg match
      case _: Literal => arg
      case _: Ident if isPureExpr(arg) => arg
      case _ =>
        val vdef = SyntheticValDef(UniqueName.fresh(name.toTermName), arg).withSpan(span)
        stats += vdef
        ref(vdef.symbol).withSpan(span)

    val start = bind("start", range.start)
    val end = bind("end", range.end)
    val step = range.step match
      case Some(st) => bind("step", st)
      case None => Literal(Constant(1)).withSpan(span)

    step match
      case _: Literal => () // a zero literal was rejected above
      case _ =>
        stats += If(
          step.select(defn.Int_==).appliedTo(Literal(Constant(0))),
          Throw(New(defn.IllegalArgumentExceptionType, defn.IllegalArgumentExceptionClass_stringConstructor,
            Literal(Constant("step cannot be 0.")) :: Nil)),
          unitLiteral).withSpan(span)

    def plusOneIfInclusive(gap: Tree): Tree =
      if range.isInclusive then gap.select(defn.Long_+).appliedTo(lit(1L)) else gap

    // The number of elements, in [0, 2^32]; non-positive means the range is empty.
    val count: Tree = (start, end, step) match
      case (Literal(s), Literal(e), Literal(st)) =>
        val gap = e.intValue.toLong - s.intValue.toLong
        val q = gap / st.intValue
        lit(q + (if range.isInclusive || gap % st.intValue != 0 then 1L else 0L))
      case _ => step match
        case Literal(st) if st.intValue == 1 =>
          plusOneIfInclusive(toLong(end).select(defn.Long_-).appliedTo(toLong(start)))
        case Literal(st) if st.intValue == -1 =>
          plusOneIfInclusive(toLong(start).select(defn.Long_-).appliedTo(toLong(end)))
        case _ =>
          // gap / step, plus one more element when inclusive or the division is inexact
          val gapDef = SyntheticValDef(UniqueName.fresh("gap".toTermName),
            toLong(end).select(defn.Long_-).appliedTo(toLong(start))).withSpan(span)
          stats += gapDef
          val gap = ref(gapDef.symbol)
          val extra =
            if range.isInclusive then lit(1L)
            else If(
              gap.select(defn.Long_%).appliedTo(toLong(step)).select(defn.Long_!=).appliedTo(lit(0L)),
              lit(1L), lit(0L)).withSpan(span)
          gap.select(defn.Long_/).appliedTo(toLong(step)).select(defn.Long_+).appliedTo(extra)

    val nDef = SyntheticValDef(UniqueName.fresh("n".toTermName), count, flags = Mutable).withSpan(span)
    val iDef = SyntheticValDef(UniqueName.fresh("i".toTermName), start, flags = Mutable).withSpan(span)
    val nRef = ref(nDef.symbol).withSpan(span)
    val iRef = ref(iDef.symbol).withSpan(span)

    val directCall = f.select(nme.apply).appliedTo(iRef).withSpan(span)
    val reduced = BetaReduce(directCall)
    val call =
      if reduced ne directCall then reduced
      else
        // Not a reducible function literal: evaluate the function expression
        // once, after the range operands, exactly like the original call.
        val fv = bind("f", f)
        if fv eq f then directCall
        else fv.select(nme.apply).appliedTo(iRef).withSpan(span)

    stats += nDef
    stats += iDef
    val body = Block(
      call :: Assign(iRef, iRef.select(defn.Int_+).appliedTo(step)).withSpan(span) :: Nil,
      Assign(nRef, nRef.select(defn.Long_-).appliedTo(lit(1L))).withSpan(span))
    val loop = WhileDo(nRef.select(defn.Long_>).appliedTo(lit(0L)), body).withSpan(span)
    Block(stats.result(), loop).withSpan(span)
  end rewrite

object RangeForeachOpt:
  val name: String = "rangeForeachOpt"
  val description: String = "rewrite `foreach` on statically constructed ranges into while loops"
