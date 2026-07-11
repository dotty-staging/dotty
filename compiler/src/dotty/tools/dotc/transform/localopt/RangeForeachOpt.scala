package dotty.tools.dotc
package transform.localopt

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Constants.{Constant, IntTag, BooleanTag}
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.Flags.Mutable
import dotty.tools.dotc.core.NameKinds.UniqueName
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.transform.BetaReduce
import dotty.tools.dotc.transform.MegaPhase.MiniPhase

import scala.collection.mutable.ListBuffer

/** Rewrites `foreach` calls on ranges built directly from one of the standard
 *  factories into the result of inlining the library code by hand, so that
 *  neither the `Range` nor (for function literals) the function value is
 *  allocated. The recognized receiver shapes are
 *
 *   - `Range(s, e)`, `Range(s, e, st)`, `Range.inclusive(s, e)`, `Range.inclusive(s, e, st)`
 *   - `s to e`, `s.to(e, st)`, `s until e`, `s.until(e, st)`
 *   - any of the step-less forms above followed by `.by(st)`
 *
 *  The emitted code is a faithful inlining of the code these calls would run,
 *  from `library/src/scala/collection/immutable/Range.scala` (and
 *  `RichInt.scala` for the `to`/`until` sugar). Schematically, for
 *  `Range(s, e, st).foreach(f)`:
 *
 *  {{{
 *  val start = s; val end = e; val step = st   // factory arguments, evaluated left to right
 *  if step == 0 then                           // constructor statement, Range.scala:98
 *    throw new IllegalArgumentException("step cannot be 0.")
 *  <evaluate f>                                // the foreach argument, evaluated after the receiver
 *  if <isEmpty> then ()                        // Range#foreach (Range.scala:221-232), where
 *  else                                        // `if (!isEmpty)` has its branches swapped
 *    val lastElement = ...                     // Range#lastElement, i.e. Range.lastElementOf
 *    var i = start
 *    while { f(i); i != lastElement } do       // `while (true) { f(i); if (i == lastElement) return; i += step }`
 *      i = i + step                            // with the `return` expressed as a do-while condition
 *  }}}
 *
 *  Each helper below reproduces one member of `Range`; where `Range.scala`
 *  reads a constructor parameter or `isInclusive`, the corresponding
 *  compile-time constant (literal tree or the matched factory's
 *  inclusiveness) is substituted, and the arithmetic is constant-folded when
 *  its operands are literals — e.g. `(1 to 10).foreach(f)` needs no
 *  `lastElement` computation at all, only `while { f(i); i != 10 } do i += 1`.
 *
 *  Correctness notes:
 *   - `isEmpty`, `numRangeElements` and `lastElement` are eager constructor
 *     vals in the library, but they are pure, so this phase computes them
 *     only where their value is used (`lastElement` only on the non-empty
 *     path). The only effect of construction, the zero-step exception, is
 *     kept (hoisted over the equally pure `isEmpty`).
 *   - Since `for x <- a to b do ...` desugars to `foreach`, `for` loops over
 *     ranges compile to allocation-free `while` loops as well.
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

  /** A range receiver built in place from start, end, optional step and inclusiveness.
   *  Which factory produced it only matters through these four values:
   *  `Range.apply` is `new Range.Exclusive(start, end, step | 1)` (Range.scala:671/679),
   *  `Range.inclusive` is `new Range.Inclusive(start, end, step | 1)` (Range.scala:689/697),
   *  and `RichInt#to`/`RichInt#until` delegate to those (RichInt.scala:63-87).
   */
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
            // Range#by is `copy(start, end, step)` (Range.scala:186-195): same bounds and
            // inclusiveness, only the step replaced. It is accepted here only on step-less
            // inner ranges, whose implicit step 1 statically passes the inner constructor's
            // zero-step check (Range.scala:98); an explicit inner step would need that
            // check before being discarded, which is not worth supporting.
            staticRange(stripped(qual)) match
              case Some(range) if range.step.isEmpty => Some(range.copy(step = Some(st)))
              case _ => None
          case _ => None
      else None
    case _ => None

  /** An `Int` binary primitive, constant-folded when both sides are literals
   *  (so that the library formulas below collapse for literal ranges), with
   *  `x + 0`, `x - 0` and `x ^ 0` simplified to `x` (arising when a literal
   *  step makes `stepSign` fold to 0).
   */
  private def intBinop(l: Tree, op: Symbol, r: Tree)(using Context): Tree = (l, r) match
    case (Literal(a), Literal(b)) if a.tag == IntTag && b.tag == IntTag =>
      val x = a.intValue
      val y = b.intValue
      val folded =
        if op == defn.Int_+ then Constant(x + y)
        else if op == defn.Int_- then Constant(x - y)
        else if op == defn.Int_* then Constant(x * y)
        else if op == defn.Int_^ then Constant(x ^ y)
        else if op == defn.Int_>> then Constant(x >> y)
        else if op == defn.Int_== then Constant(x == y)
        else if op == defn.Int_!= then Constant(x != y)
        else if op == defn.Int_> then Constant(x > y)
        else if op == defn.Int_< then Constant(x < y)
        else if op == defn.Int_>= then Constant(x >= y)
        else if op == defn.Int_<= then Constant(x <= y)
        else return l.select(op).appliedTo(r)
      Literal(folded).withSpan(l.span)
    case (_, Literal(b)) if b.tag == IntTag && b.intValue == 0
        && (op == defn.Int_+ || op == defn.Int_- || op == defn.Int_^) =>
      l
    case _ =>
      l.select(op).appliedTo(r)

  /** `java.lang.Integer.divideUnsigned`, constant-folded like [[intBinop]]. */
  private def divideUnsigned(l: Tree, r: Tree)(using Context): Tree = (l, r) match
    case (Literal(a), Literal(b)) if a.tag == IntTag && b.tag == IntTag =>
      Literal(Constant(java.lang.Integer.divideUnsigned(a.intValue, b.intValue)))
    case _ =>
      ref(defn.Integer_divideUnsigned).appliedTo(l, r)

  /** An `if`, with the branch selected at compile time for a literal condition. */
  private def mkIf(cond: Tree, thenp: Tree, elsep: Tree)(using Context): Tree = cond match
    case Literal(c) if c.tag == BooleanTag => if c.booleanValue then thenp else elsep
    case _ => If(cond, thenp, elsep)

  private def rewrite(range: StaticRange, f: Tree, tree: Apply)(using Context): Tree =
    // A function argument that is not a `Function1` (e.g. a `null` literal) is left alone.
    if !f.tpe.widen.derivesFrom(defn.Function1) then return tree
    range.step match
      case Some(Literal(st)) if st.intValue == 0 =>
        return tree // keep the original code and its constructor exception (Range.scala:98)
      case _ =>

    val span = tree.span
    def lit(i: Int): Tree = Literal(Constant(i)).withSpan(span)

    // Evaluate a subexpression once and refer to it by name, like the
    // library's parameters and vals do. Literals and pure identifiers are
    // used directly; everything else is bound to a local, since re-reading
    // vars or re-executing definitions inside pure blocks on every use would
    // be wrong.
    def bindTo(defs: ListBuffer[Tree])(name: String, rhs: Tree): Tree = rhs match
      case _: Literal => rhs
      case _: Ident if isPureExpr(rhs) => rhs
      case _ =>
        val vdef = SyntheticValDef(UniqueName.fresh(name.toTermName), rhs).withSpan(span)
        defs += vdef
        ref(vdef.symbol).withSpan(span)

    val stats = ListBuffer.empty[Tree]

    // The factory arguments, evaluated left to right, exactly as the
    // `Range.apply(start, end, step)` / `intWrapper(start).to(end).by(step)`
    // call chain would evaluate them. The step-less factories construct the
    // range with step 1 (Range.scala:679/697, RichInt.scala:63/79).
    val start = bindTo(stats)("start", range.start)
    val end = bindTo(stats)("end", range.end)
    val step = range.step match
      case Some(st) => bindTo(stats)("step", st)
      case None => lit(1)

    // Constructor statement, Range.scala:98:
    //   if (step == 0) throw new IllegalArgumentException("step cannot be 0.")
    // Omitted for a literal step: a zero literal was rejected above.
    step match
      case _: Literal => ()
      case _ =>
        // TODO: we can introduce a method in `Scala3RunTime|Range` to check the step
        // and reduce bytecode size
        stats += If(
          intBinop(step, defn.Int_==, lit(0)),
          Throw(New(defn.IllegalArgumentExceptionType, defn.IllegalArgumentExceptionClass_stringConstructor,
            Literal(Constant("step cannot be 0.")) :: Nil)),
          unitLiteral).withSpan(span)

    // Constructor val `isEmpty`, Range.scala:91-96, with `isInclusive` known
    // from the matched factory:
    //   final override val isEmpty: Boolean = (
    //     if (isInclusive)
    //       (if (step >= 0) start > end else start < end)
    //     else
    //       (if (step >= 0) start >= end else start <= end))
    val isEmpty = mkIf(
      intBinop(step, defn.Int_>=, lit(0)),
      intBinop(start, if range.isInclusive then defn.Int_> else defn.Int_>=, end),
      intBinop(start, if range.isInclusive then defn.Int_< else defn.Int_<=, end))

    // Constructor val `lastElement` (Range.scala:149), whose implementation is
    // `Range.lastElementOf` (Range.scala:640-666). In the library it is
    // computed eagerly, but it is pure and only meaningful for non-empty
    // ranges, so it is emitted on the non-empty path only (its `defs` join the
    // loop prelude):
    //   if (((step + 1) & ~2) == 0)  // `step == 1 || step == -1`
    //     (if (isInclusive) end else end - step)
    //   else
    //     start + (step * (numRangeElementsOf(start, end, step, isInclusive) - 1))
    def stepIsPlusMinusOneLast: Tree = // the `if (isInclusive) end else end - step` arm
      if range.isInclusive then end else intBinop(end, defn.Int_-, step)
    def generalLast(defs: ListBuffer[Tree]): Tree =
      // `Range.numRangeElementsOf` (Range.scala:612-630), keeping the
      // library's val names:
      //   val stepSign = step >> 31
      //   val gap = ((end - start) ^ stepSign) - stepSign
      //   val absStep = (step ^ stepSign) - stepSign
      //   val div = Integer.divideUnsigned(gap, absStep)
      //   if (isInclusive || (absStep * div != gap)) div + 1 else div
      val stepSign = bindTo(defs)("stepSign", intBinop(step, defn.Int_>>, lit(31)))
      val gap = bindTo(defs)("gap",
        intBinop(intBinop(intBinop(end, defn.Int_-, start), defn.Int_^, stepSign), defn.Int_-, stepSign))
      val absStep = bindTo(defs)("absStep",
        intBinop(intBinop(step, defn.Int_^, stepSign), defn.Int_-, stepSign))
      val div = bindTo(defs)("div", divideUnsigned(gap, absStep))
      val numRangeElements =
        if range.isInclusive then intBinop(div, defn.Int_+, lit(1))
        else mkIf(
          intBinop(intBinop(absStep, defn.Int_*, div), defn.Int_!=, gap),
          intBinop(div, defn.Int_+, lit(1)),
          div)
      // `locationAfterN(numRangeElements - 1)` (Range.scala:130-142):
      //   private def locationAfterN(n: Int): Int = start + (step * n)
      intBinop(start, defn.Int_+, intBinop(step, defn.Int_*, intBinop(numRangeElements, defn.Int_-, lit(1))))
    def lastElementExpr(defs: ListBuffer[Tree]): Tree = step match
      case Literal(st) if st.intValue == 1 || st.intValue == -1 =>
        stepIsPlusMinusOneLast // the `((step + 1) & ~2) == 0` test decided at compile time
      case _: Literal =>
        generalLast(defs)
      case _ =>
        // Runtime step: emitting both branches inline would be a lot of
        // bytecode, so call `Scala3RunTime.rangeLastElement`, whose body is
        // the same `Range.lastElementOf` that `Range#lastElement` inlines.
        ref(defn.Scala3RunTime_rangeLastElement)
          .appliedTo(start, end, step, Literal(Constant(range.isInclusive)))
          .withSpan(span)

    // `Range#foreach`, Range.scala:221-232:
    //   if (!isEmpty) {
    //     var i = start
    //     while (true) {
    //       f(i)
    //       if (i == lastElement) return
    //       i += step
    //     }
    //   }
    // The `while (true) ... return` becomes a do-while: `f(i)` and the
    // (negated) equality test form the loop condition, `i += step` the body.
    val iDef = SyntheticValDef(UniqueName.fresh("i".toTermName), start, flags = Mutable).withSpan(span)
    val iRef = ref(iDef.symbol).withSpan(span)

    // The function argument, evaluated once, after the receiver (so after the
    // zero-step exception); function literals are beta-reduced into the loop.
    val directCall = f.select(nme.apply).appliedTo(iRef).withSpan(span)
    val reduced = BetaReduce(directCall)
    val call =
      if reduced ne directCall then reduced
      else
        val fv = bindTo(stats)("f", f)
        if fv eq f then directCall
        else fv.select(nme.apply).appliedTo(iRef).withSpan(span)

    val loopPrelude = ListBuffer.empty[Tree]
    val lastElement = bindTo(loopPrelude)("lastElement", lastElementExpr(loopPrelude))
    loopPrelude += iDef
    val loop = WhileDo(
      Block(call :: Nil, intBinop(iRef, defn.Int_!=, lastElement)),
      Assign(iRef, intBinop(iRef, defn.Int_+, step)).withSpan(span)).withSpan(span)
    val whenNonEmpty = Block(loopPrelude.toList, loop).withSpan(span)

    // `if (!isEmpty) { ... }` with the branches swapped instead of negating.
    val body = isEmpty match
      case Literal(c) if c.tag == BooleanTag =>
        if c.booleanValue then unitLiteral else whenNonEmpty
      case cond =>
        If(cond, unitLiteral, whenNonEmpty).withSpan(span)

    Block(stats.toList, body).withSpan(span)
  end rewrite

object RangeForeachOpt:
  val name: String = "rangeForeachOpt"
  val description: String = "rewrite `foreach` on statically constructed ranges into while loops"
