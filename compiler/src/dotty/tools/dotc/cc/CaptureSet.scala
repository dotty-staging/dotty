package dotty.tools
package dotc
package cc

import core.*
import Types.*, Symbols.*, Flags.*, Contexts.*, Decorators.*
import config.Printers.capt
import Annotations.Annotation
import annotation.threadUnsafe
import annotation.constructorOnly
import annotation.internal.sharable
import reporting.trace
import printing.{Showable, Printer}
import printing.Texts.*
import util.SimpleIdentitySet
import util.common.alwaysTrue

/** A class for capture sets. Capture sets can be constants or variables.
 *  Capture sets support inclusion constraints <:< where <:< is subcapturing.
 *  They also allow mapping with arbitrary functions from elements to capture sets,
 *  by supporting a monadic flatMap operation. That is, constraints can be
 *  of one of the following forms
 *
 *    cs1 <:< cs2
 *    cs1 = ∪ {f(x) | x ∈ cs2}
 *
 *  where the `f`s are arbitrary functions from capture references to capture sets.
 *  We call the resulting constraint system "monadic set constraints".
 */
sealed abstract class CaptureSet extends Showable:
  import CaptureSet.*

  /** The elements of this capture set. For capture variables,
   *  the elements known so far.
   */
  def elems: Refs

  /** Is this capture set constant (i.e. not an unsolved capture variable)?
   *  Solved capture variables count as constant.
   */
  def isConst: Boolean

  /** Is this capture set always empty? For capture veraiables, returns
   *  always false
   */
  def isAlwaysEmpty: Boolean

  /** Is this capture set definitely non-empty? */
  final def isNotEmpty: Boolean = !elems.isEmpty

  /** Cast to variable. @pre: @isConst */
  def asVar: Var =
    assert(!isConst)
    asInstanceOf[Var]

  /** Add new elements to this capture set if allowed.
   *  @pre `newElems` is not empty and does not overlap with `this.elems`.
   *  Constant capture sets never allow to add new elements.
   *  Variables allow it if and only if the new elements can be included
   *  in all their supersets.
   *  @param origin   The set where the elements come from, or `empty` if not known.
   *  @return CompareResult.OK if elements were added, or a conflicting
   *          capture set that prevents addition otherwise.
   */
  protected def addNewElems(newElems: Refs, origin: CaptureSet)(using Context, VarState): CompareResult

  /** If this is a variable, add `cs` as a super set */
  protected def addSuper(cs: CaptureSet)(using Context, VarState): CompareResult

  /** If `cs` is a variable, add this capture set as one of its super sets */
  protected def addSub(cs: CaptureSet)(using Context): this.type =
    cs.addSuper(this)(using ctx, UnrecordedState)
    this

  /** Try to include all references of `elems` that are not yet accounted by this
   *  capture set. Inclusion is via `addNewElems`.
   *  @param origin   The set where the elements come from, or `empty` if not known.
   *  @return  CompareResult.OK if all unaccounted elements could be added,
   *           capture set that prevents addition otherwise.
   */
  protected final def tryInclude(elems: Refs, origin: CaptureSet)(using Context, VarState): CompareResult =
    val unaccounted = elems.filter(!accountsFor(_))
    if unaccounted.isEmpty then CompareResult.OK
    else addNewElems(unaccounted, origin)

  protected final def tryInclude(elem: CaptureRef, origin: CaptureSet)(using Context, VarState): CompareResult =
    if accountsFor(elem) then CompareResult.OK
    else addNewElems(elem.singletonCaptureSet.elems, origin)

  /** {x} <:< this   where <:< is subcapturing, but treating all variables
   *                 as frozen.
   */
  def accountsFor(x: CaptureRef)(using ctx: Context): Boolean =
    reporting.trace(i"$this accountsFor $x, ${x.captureSetOfInfo}?", show = true) {
      elems.contains(x)
      || !x.isRootCapability
          && x.captureSetOfInfo.subCaptures(this, frozen = true) == CompareResult.OK
    }

  /** The subcapturing test */
  final def subCaptures(that: CaptureSet, frozen: Boolean)(using Context): CompareResult =
    subCaptures(that)(using ctx, if frozen then FrozenState else VarState())

  private def subCaptures(that: CaptureSet)(using Context, VarState): CompareResult =
    def recur(elems: List[CaptureRef]): CompareResult = elems match
      case elem :: elems1 =>
        var result = that.tryInclude(elem, this)
        if result != CompareResult.OK && !elem.isRootCapability && summon[VarState] != FrozenState then
          result = elem.captureSetOfInfo.subCaptures(that)
        if result == CompareResult.OK then
          recur(elems1)
        else
          varState.abort()
          result
      case Nil =>
        addSuper(that)
    recur(elems.toList)
      .showing(i"subcaptures $this <:< $that = ${result.show}", capt)

  def =:= (that: CaptureSet)(using Context): Boolean =
       this.subCaptures(that, frozen = true) == CompareResult.OK
    && that.subCaptures(this, frozen = true) == CompareResult.OK

  /** The smallest capture set (via <:<) that is a superset of both
   *  `this` and `that`
   */
  def ++ (that: CaptureSet)(using Context): CaptureSet =
    if this.subCaptures(that, frozen = true) == CompareResult.OK then that
    else if that.subCaptures(this, frozen = true) == CompareResult.OK then this
    else if this.isConst && that.isConst then Const(this.elems ++ that.elems)
    else Var(this.elems ++ that.elems).addSub(this).addSub(that)

  /** The smallest superset (via <:<) of this capture set that also contains `ref`.
   */
  def + (ref: CaptureRef)(using Context): CaptureSet =
    this ++ ref.singletonCaptureSet

  /** The largest capture set (via <:<) that is a subset of both `this` and `that`
   */
  def **(that: CaptureSet)(using Context): CaptureSet =
    if this.subCaptures(that, frozen = true) == CompareResult.OK then this
    else if that.subCaptures(this, frozen = true) == CompareResult.OK then that
    else if this.isConst && that.isConst then Const(elems.intersect(that.elems))
    else if that.isConst then Intersected(this.asVar, that)
    else Intersected(that.asVar, this)

  def -- (that: CaptureSet.Const)(using Context): CaptureSet =
    val elems1 = elems.filter(!that.accountsFor(_))
    if elems1.size == elems.size then this
    else if this.isConst then Const(elems1)
    else Diff(asVar, that)

  def - (ref: CaptureRef)(using Context): CaptureSet =
    this -- ref.singletonCaptureSet

  def filter(p: CaptureRef => Boolean)(using Context): CaptureSet =
    if this.isConst then Const(elems.filter(p))
    else Filtered(asVar, p)

  /** capture set obtained by applying `f` to all elements of the current capture set
   *  and joining the results. If the current capture set is a variable, the same
   *  transformation is applied to all future additions of new elements.
   */
  def map(tm: TypeMap)(using Context): CaptureSet = tm match
    case tm: BiTypeMap =>
      val mappedElems = elems.map(tm.forward)
      if isConst then Const(mappedElems)
      else BiMapped(asVar, tm, mappedElems)
    case _ =>
      val mapped = mapRefs(elems, tm, tm.variance)
      if isConst then mapped
      else Mapped(asVar, tm, tm.variance, mapped)

  def substParams(tl: BindingType, to: List[Type])(using Context) =
    map(Substituters.SubstParamsMap(tl, to))

  /** An upper approximation of this capture set. This is the set itself
   *  except for real (non-mapped, non-filtered) capture set variables, where
   *  it is the intersection of all upper approximations of known supersets
   *  of the variable.
   *  The upper approximation is meaningful only if it is constant. If not,
   *  `upperApprox` can return an arbitrary capture set variable.
   */
  protected def upperApprox(origin: CaptureSet)(using Context): CaptureSet

  protected def propagateSolved()(using Context): Unit = ()

  def toRetainsTypeArg(using Context): Type =
    assert(isConst)
    ((NoType: Type) /: elems) ((tp, ref) =>
      if tp.exists then OrType(tp, ref, soft = false) else ref)

  def toRegularAnnotation(using Context): Annotation  =
    Annotation(CaptureAnnotation(this, boxed = false).tree)

  override def toText(printer: Printer): Text =
    Str("{") ~ Text(elems.toList.map(printer.toTextCaptureRef), ", ") ~ Str("}")

object CaptureSet:
  type Refs = SimpleIdentitySet[CaptureRef]
  type Vars = SimpleIdentitySet[Var]
  type Deps = SimpleIdentitySet[CaptureSet]

  /** If set to `true`, capture stack traces that tell us where sets are created */
  private final val debugSets = false

  private val emptySet = SimpleIdentitySet.empty
  @sharable private var varId = 0

  val empty: CaptureSet.Const = Const(emptySet)

  /** The universal capture set `{*}` */
  def universal(using Context): CaptureSet =
    defn.captureRoot.termRef.singletonCaptureSet

  /** Used as a recursion brake */
  @sharable private[dotc] val Pending = Const(SimpleIdentitySet.empty)

  def apply(elems: CaptureRef*)(using Context): CaptureSet.Const =
    if elems.isEmpty then empty
    else Const(SimpleIdentitySet(elems.map(_.normalizedRef)*))

  def apply(elems: Refs)(using Context): CaptureSet.Const =
    if elems.isEmpty then empty else Const(elems)

  class Const private[CaptureSet] (val elems: Refs) extends CaptureSet:
    assert(elems != null)
    def isConst = true
    def isAlwaysEmpty = elems.isEmpty

    def addNewElems(elems: Refs, origin: CaptureSet)(using Context, VarState): CompareResult =
      CompareResult.fail(this)

    def addSuper(cs: CaptureSet)(using Context, VarState) = CompareResult.OK

    def upperApprox(origin: CaptureSet)(using Context): CaptureSet = this

    override def toString = elems.toString
  end Const

  class Var(initialElems: Refs = emptySet) extends CaptureSet:
    val id =
      varId += 1
      varId

    private var isSolved: Boolean = false

    var elems: Refs = initialElems
    var deps: Deps = emptySet
    def isConst = isSolved
    def isAlwaysEmpty = false

    private def recordElemsState()(using VarState): Boolean =
      varState.getElems(this) match
        case None => varState.putElems(this, elems)
        case _ => true

    private[CaptureSet] def recordDepsState()(using VarState): Boolean =
      varState.getDeps(this) match
        case None => varState.putDeps(this, deps)
        case _ => true

    def resetElems()(using state: VarState): Unit =
      elems = state.elems(this)

    def resetDeps()(using state: VarState): Unit =
      deps = state.deps(this)

    def addNewElems(newElems: Refs, origin: CaptureSet)(using Context, VarState): CompareResult =
      if !isConst && recordElemsState() then
        elems ++= newElems
        // assert(id != 2 || elems.size != 2, this)
        val depsIt = deps.iterator
        while depsIt.hasNext do
          val result = depsIt.next.tryInclude(newElems, this)
          if result != CompareResult.OK then return result
        CompareResult.OK
      else
        CompareResult.fail(this)

    def addSuper(cs: CaptureSet)(using Context, VarState): CompareResult =
      if (cs eq this) || cs.elems.contains(defn.captureRoot.termRef) || isConst then
        CompareResult.OK
      else if recordDepsState() then
        deps += cs
        CompareResult.OK
      else
        CompareResult.fail(this)

    private var computingApprox = false

    final def upperApprox(origin: CaptureSet)(using Context): CaptureSet =
      if computingApprox then universal
      else if isConst then this
      else
        computingApprox = true
        try computeApprox(origin).ensuring(_.isConst)
        finally computingApprox = false

    protected def computeApprox(origin: CaptureSet)(using Context): CaptureSet =
      (universal /: deps) { (acc, sup) => acc ** sup.upperApprox(this) }

    def solve(variance: Int)(using Context): Unit =
      if variance < 0 && !isConst then
        val approx = upperApprox(empty)
        //println(i"solving var $this $approx ${approx.isConst} deps = ${deps.toList}")
        if approx.isConst then
          val newElems = approx.elems -- elems
          if newElems.isEmpty
              || addNewElems(newElems, empty)(using ctx, VarState()) == CompareResult.OK then
            markSolved()

    def markSolved()(using Context): Unit =
      isSolved = true
      deps.foreach(_.propagateSolved())

    protected def ids(using Context): String =
      val trail = this.match
        case dv: DerivedVar => dv.source.ids
        case _ => ""
      s"$id${getClass.getSimpleName.take(1)}$trail"

    override def toText(printer: Printer): Text = inContext(printer.printerContext) {
      super.toText(printer) ~ (Str(ids) provided !isConst && ctx.settings.YccDebug.value)
    }

    override def toString = s"Var$id$elems"
  end Var

  abstract class DerivedVar(initialElems: Refs)(using @constructorOnly ctx: Context)
  extends Var(initialElems):
    def source: Var

    addSub(source)

    override def propagateSolved()(using Context) =
      if source.isConst && !isConst then markSolved()
  end DerivedVar

  /** A variable that changes when `source` changes, where all additional new elements are mapped
   *  using   ∪ { f(x) | x <- elems }
   */
  class Mapped private[CaptureSet]
    (val source: Var, tm: TypeMap, variance: Int, initial: CaptureSet)(using @constructorOnly ctx: Context)
  extends DerivedVar(initial.elems):
    addSub(initial)
    val stack = if debugSets then (new Throwable).getStackTrace().take(20) else null

    private def whereCreated(using Context): String =
      if stack == null then ""
      else i"""
              |Stack trace of variable creation:"
              |${stack.mkString("\n")}"""

    override def addNewElems(newElems: Refs, origin: CaptureSet)(using Context, VarState): CompareResult =
      val added =
        if origin eq source then
          mapRefs(newElems, tm, variance)
        else
          if variance <= 0 && !origin.isConst && (origin ne initial) then
            report.warning(i"trying to add elems $newElems from unrecognized source $origin of mapped set $this$whereCreated")
            return CompareResult.fail(this)
          Const(newElems)
      val result = super.addNewElems(added.elems, origin)
      if result == CompareResult.OK then
        if added.isConst then result
        else if added.asVar.recordDepsState() then { addSub(added); result }
        else CompareResult.fail(this)
      else result

    override def computeApprox(origin: CaptureSet)(using Context): CaptureSet =
      if source eq origin then universal
      else source.upperApprox(this).map(tm)

    override def propagateSolved()(using Context) =
      if initial.isConst then super.propagateSolved()

    override def toString = s"Mapped$id($source, elems = $elems)"
  end Mapped

  class BiMapped private[CaptureSet]
    (val source: Var, bimap: BiTypeMap, initialElems: Refs)(using @constructorOnly ctx: Context)
  extends DerivedVar(initialElems):

    override def addNewElems(newElems: Refs, origin: CaptureSet)(using Context, VarState): CompareResult =
      if origin eq source then
        super.addNewElems(newElems.map(bimap.forward), origin)
      else
        val r = super.addNewElems(newElems, origin)
        if r == CompareResult.OK then
          source.tryInclude(newElems.map(bimap.backward), this)
            .showing(i"propagating new elems $newElems backward from $this to $source", capt)
          else r

    override def computeApprox(origin: CaptureSet)(using Context): CaptureSet =
      if source eq origin then super.computeApprox(this).map(bimap.inverseTypeMap)
      else source.upperApprox(this).map(bimap)

    override def toString = s"BiMapped$id($source, elems = $elems)"
  end BiMapped

  /** A variable with elements given at any time as { x <- source.elems | p(x) } */
  class Filtered private[CaptureSet]
    (val source: Var, p: CaptureRef => Boolean)(using @constructorOnly ctx: Context)
  extends DerivedVar(source.elems.filter(p)):

    override def addNewElems(newElems: Refs, origin: CaptureSet)(using Context, VarState): CompareResult =
      super.addNewElems(newElems.filter(p), origin)

    override def computeApprox(origin: CaptureSet)(using Context): CaptureSet =
      if source eq origin then universal
      else source.upperApprox(this).filter(p)

    override def toString = s"${getClass.getSimpleName}$id($source, elems = $elems)"
  end Filtered

  /** A variable with elements given at any time as { x <- source.elems | !other.accountsFor(x) } */
  class Diff(source: Var, other: Const)(using Context)
  extends Filtered(source, !other.accountsFor(_))

  /** A variable with elements given at any time as { x <- source.elems | other.accountsFor(x) } */
  class Intersected(source: Var, other: CaptureSet)(using Context)
  extends Filtered(source, other.accountsFor(_)):
    addSub(other)

  def extrapolateCaptureRef(r: CaptureRef, tm: TypeMap, variance: Int)(using Context): CaptureSet =
    val r1 = tm(r)
    val upper = r1.captureSet
    def isExact =
      upper.isAlwaysEmpty || upper.isConst && upper.elems.size == 1 && upper.elems.contains(r1)
    if variance > 0 || isExact then upper
    else if variance < 0 then CaptureSet.empty
    else assert(false, i"trying to add $upper from $r via ${tm.getClass} in a non-variant setting")

  def mapRefs(xs: Refs, f: CaptureRef => CaptureSet)(using Context): CaptureSet =
    ((empty: CaptureSet) /: xs)((cs, x) => cs ++ f(x))

  def mapRefs(xs: Refs, tm: TypeMap, variance: Int)(using Context): CaptureSet =
    mapRefs(xs, extrapolateCaptureRef(_, tm, variance))

  type CompareResult = CompareResult.Type

  /** None = ok, Some(cs) = failure since not a subset of cs */
  object CompareResult:
    opaque type Type = CaptureSet
    val OK: Type = Const(emptySet)
    def fail(cs: CaptureSet): Type = cs
    extension (result: Type)
      def isOK: Boolean = result eq OK
      def blocking: CaptureSet = result
      def show: String = if result == OK then "OK" else result.toString
      inline def andAlso(op: => Type) = if result.isOK then op else result

  class VarState:
    private val elemsMap: util.EqHashMap[Var, Refs] = new util.EqHashMap
    private val depsMap: util.EqHashMap[Var, Deps] = new util.EqHashMap

    def elems(v: Var): Refs = elemsMap(v)
    def getElems(v: Var): Option[Refs] = elemsMap.get(v)
    def putElems(v: Var, elems: Refs): Boolean = { elemsMap(v) = elems; true }

    def deps(v: Var): Deps = depsMap(v)
    def getDeps(v: Var): Option[Deps] = depsMap.get(v)
    def putDeps(v: Var, deps: Deps): Boolean = { depsMap(v) = deps; true }

    def abort(): Unit =
      elemsMap.keysIterator.foreach(_.resetElems()(using this))
      depsMap.keysIterator.foreach(_.resetDeps()(using this))
  end VarState

  @sharable
  object FrozenState extends VarState:
    override def putElems(v: Var, refs: Refs) = false
    override def putDeps(v: Var, deps: Deps) = false
    override def abort(): Unit = ()

  @sharable
  object UnrecordedState extends VarState:
    override def putElems(v: Var, refs: Refs) = true
    override def putDeps(v: Var, deps: Deps) = true
    override def abort(): Unit = ()

  def varState(using state: VarState): VarState = state

  def ofClass(cinfo: ClassInfo, argTypes: List[Type])(using Context): CaptureSet =
    CaptureSet.empty
    /*
      def captureSetOf(tp: Type): CaptureSet = tp match
        case tp: TypeRef if tp.symbol.is(ParamAccessor) =>
          def mapArg(accs: List[Symbol], tps: List[Type]): CaptureSet = accs match
            case acc :: accs1 if tps.nonEmpty =>
              if acc == tp.symbol then tps.head.captureSet
              else mapArg(accs1, tps.tail)
            case _ =>
              empty
          mapArg(cinfo.cls.paramAccessors, argTypes)
        case _ =>
          tp.captureSet
      val css =
        for
          parent <- cinfo.parents if parent.classSymbol == defn.RetainingClass
          arg <- parent.argInfos
        yield captureSetOf(arg)
      css.foldLeft(empty)(_ ++ _)
    */

  def ofType(tp: Type)(using Context): CaptureSet =
    def recur(tp: Type): CaptureSet = tp.dealias match
      case tp: TermRef =>
        tp.captureSet
      case tp: TermParamRef =>
        tp.captureSet
      case _: TypeRef | _: TypeParamRef =>
        empty
      case CapturingType(parent, refs, _) =>
        recur(parent) ++ refs
      case AppliedType(tycon, args) =>
        val cs = recur(tycon)
        tycon.typeParams match
          case tparams @ (LambdaParam(tl, _) :: _) => cs.substParams(tl, args)
          case _ => cs
      case tp: TypeProxy =>
        recur(tp.underlying)
      case AndType(tp1, tp2) =>
        recur(tp1) ** recur(tp2)
      case OrType(tp1, tp2) =>
        recur(tp1) ++ recur(tp2)
      case tp: ClassInfo =>
        ofClass(tp, Nil)
      case _ =>
        empty
    recur(tp)
      .showing(i"capture set of $tp = $result", capt)
end CaptureSet
