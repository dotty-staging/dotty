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
import dotty.tools.dotc.inlines.Inlines
import dotty.tools.dotc.transform.BetaReduce
import dotty.tools.dotc.transform.MegaPhase.MiniPhase

import scala.collection.mutable.ListBuffer

/** Rewrites `foreach` calls on ranges built directly from one of the standard
 *  factories into the result of inlining the library code, so that neither
 *  the `Range` nor (for function literals) the function value is allocated.
 *  The recognized receiver shapes are
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
 *  `<isEmpty>` and `<lastElement>` are not reproduced by hand: this phase
 *  synthesizes calls to the `private[scala] inline` methods `Range.isEmptyOf`
 *  and `Range.lastElementOf` — the single implementations that the `Range`
 *  constructor itself uses — and interprets them by running the inliner on
 *  them ([[interpreted]]). For statically known arguments the expansion
 *  constant-folds away (`InlineTyper` reduces `if`s with constant conditions),
 *  so e.g. `(1 to 10).foreach(f)` needs no computation at all, only
 *  `while { f(i); i != 10 } do i += 1`, while `(a until b).foreach(f)`
 *  reduces `lastElement` to `b - 1`. When the step is not statically known,
 *  inlining `lastElementOf`'s unsigned arithmetic would be a lot of bytecode,
 *  so the call goes to `Scala3RunTime.rangeLastElement` instead, whose body
 *  is that same inline method.
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

  /** Interpret a call to one of the `Range` companion's `private[scala] inline`
   *  methods, by synthesizing the call and running the actual inliner on it.
   *  The methods take `inline` parameters, so constant arguments are
   *  substituted into the expansion with their constant types, where the
   *  inline typer folds the arithmetic and selects `if` branches; for fully
   *  static arguments the library method is thereby evaluated at compile
   *  time and a plain literal is returned. Otherwise the residue is the
   *  library's own code, kept under its `Inlined` node (which accounts for
   *  the expansion's foreign source positions).
   */
  private def interpreted(method: Symbol, args: List[Tree], tree: Apply)(using Context): Tree =
    // Past the typer and Inlining phases, `Inlines.needsInlining` is false, so
    // the expansion does not automatically inline the inline calls it contains
    // (`lastElementOf` calls `numRangeElementsOf`); expand them here instead.
    val expandNested = new TreeMap:
      override def transform(t: Tree)(using Context): Tree = t match
        case t: Apply if Inlines.isInlineable(t.symbol) => transform(Inlines.inlineCall(t))
        case _ => super.transform(t)
    val expansion = expandNested.transform(
      Inlines.inlineCall(ref(method).appliedToTermArgs(args).withSpan(tree.span)))
    // A fully reduced expansion is a literal under pure wrappers (the inliner
    // ascribes the method's declared result type, hiding the constant type):
    // re-issue it as a plain literal belonging to this compilation unit.
    stripped(expansion) match
      case Literal(c) => Literal(c).withSpan(tree.span)
      case _ => expansion

  private def rewrite(range: StaticRange, f: Tree, tree: Apply)(using Context): Tree =
    // A function argument that is not a `Function1` (e.g. a `null` literal) is left alone.
    if !f.tpe.widen.derivesFrom(defn.Function1) then return tree
    // The library members this rewrite builds on may be absent when compiling
    // against an older standard library; skip the optimization then.
    if !defn.RangeModule_isEmptyOf.exists
      || !defn.RangeModule_lastElementOf.exists
      || !defn.Scala3RunTime_rangeLastElement.exists
    then return tree
    range.step match
      case Some(Literal(st)) if st.intValue == 0 =>
        return tree // keep the original code and its constructor exception (Range.scala:98)
      case _ =>

    val span = tree.span

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
      case None => Literal(Constant(1)).withSpan(span)
    val isInclusive = Literal(Constant(range.isInclusive)).withSpan(span)

    // Constructor statement, Range.scala:98:
    //   if (step == 0) throw new IllegalArgumentException("step cannot be 0.")
    // Omitted for a literal step: a zero literal was rejected above.
    step match
      case _: Literal => ()
      case _ =>
        // TODO: we can introduce a method in `Scala3RunTime|Range` to check the step
        // and reduce bytecode size
        stats += If(
          step.select(defn.Int_==).appliedTo(Literal(Constant(0))),
          Throw(New(defn.IllegalArgumentExceptionType, defn.IllegalArgumentExceptionClass_stringConstructor,
            Literal(Constant("step cannot be 0.")) :: Nil)),
          unitLiteral).withSpan(span)

    // Constructor val `isEmpty` (Range.scala:91), interpreted from its
    // implementation `Range.isEmptyOf`: a constant for literal operands, a
    // single comparison for a literal step, the full conditional otherwise.
    val isEmpty = interpreted(defn.RangeModule_isEmptyOf, List(start, end, step, isInclusive), tree)

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

    // Constructor val `lastElement` (Range.scala:149), computed only on the
    // non-empty path, where its value is meaningful. For a literal step its
    // implementation `Range.lastElementOf` is interpreted like `isEmptyOf`
    // above; for a runtime step the inlined unsigned arithmetic would be a
    // lot of bytecode, so it is delegated to `Scala3RunTime.rangeLastElement`,
    // whose body is that same inline method.
    val lastElementExpr = step match
      case _: Literal =>
        interpreted(defn.RangeModule_lastElementOf, List(start, end, step, isInclusive), tree)
      case _ =>
        ref(defn.Scala3RunTime_rangeLastElement)
          .appliedTo(start, end, step, isInclusive)
          .withSpan(span)

    val loopPrelude = ListBuffer.empty[Tree]
    val lastElement = bindTo(loopPrelude)("lastElement", lastElementExpr)
    loopPrelude += iDef
    val loop = WhileDo(
      Block(call :: Nil, iRef.select(defn.Int_!=).appliedTo(lastElement)),
      Assign(iRef, iRef.select(defn.Int_+).appliedTo(step)).withSpan(span)).withSpan(span)
    val whenNonEmpty = Block(loopPrelude.toList, loop).withSpan(span)

    // `if (!isEmpty) { ... }` with the branches swapped instead of negating,
    // and decided at compile time when `isEmptyOf` reduced to a constant.
    val body = isEmpty match
      case Literal(c) => if c.booleanValue then unitLiteral else whenNonEmpty
      case cond => If(cond, unitLiteral, whenNonEmpty).withSpan(span)

    Block(stats.toList, body).withSpan(span)
  end rewrite

object RangeForeachOpt:
  val name: String = "rangeForeachOpt"
  val description: String = "rewrite `foreach` on statically constructed ranges into while loops"
