package dotty.tools.dotc
package transform

import core.*
import Contexts.*
import Decorators.*
import DenotTransformers.IdentityDenotTransformer
import Flags.*
import NameKinds.UnboxedMethName
import NullOpsDecorator.*
import Phases.*
import Symbols.*
import Types.*
import MegaPhase.*
import ast.TreeTypeMap

import scala.collection.mutable

object UnboxedOptions:
  val name: String = "unboxedOptions"
  val description: String = "generate unboxed Option entry points and boxed bridges"

/** An experimental ABI transformation, enabled by `-Yunboxed-options`.
 *
 *  For every class-level method `f` whose erased signature mentions `Option`
 *  in a parameter or result position, this phase adds a companion entry point
 *  `f$unboxed` in which every such `Option` becomes a "slot" whose type is the
 *  erasure of the Option's type argument (the argument's upper bound, for
 *  abstract arguments), so that the JIT sees precise types:
 *
 *    - `Option[String]`      -> slot `String`
 *    - `Option[Int]`         -> slot `java.lang.Integer` (the payload is the box)
 *    - `Option[Option[T]]`   -> slot `Option` (nested options flatten)
 *    - `Option[T]` unbounded -> slot `Object`
 *
 *  Encodings, implemented by `scala.runtime.UnboxedOptions`:
 *
 *    - precise slots (type != Object): `None ↦ null`, `Some(v) ↦ v`.
 *      `Some(null)` cannot be represented (pigeonhole: an S-typed slot has
 *      |S|+1 values but needs payloads + None + Some(null)); the conversion
 *      throws `IllegalArgumentException`. Under `-Yexplicit-nulls` a
 *      non-nullable payload type makes this statically unreachable; nullable
 *      payloads (`String | Null`) fall back to the generic Object slot.
 *    - generic slots (type == Object): total encoding preserving all values:
 *      `None ↦ None` (its own sentinel), `Some(v) ↦ v`, payloads that are
 *      `null`/`None`/`Wrapped` get one `Wrapped` cell, `null` passes through.
 *
 *  For a concrete, non-accessor method the implementation moves to `f$unboxed`
 *  (re-boxing its Option parameters on entry for now) and the original `f`
 *  becomes a bridge:
 *
 *      def f(x: Option): Option = fromRep(this.f$unboxed(toRep(x)))
 *
 *  Accessors keep their implementation in the boxed method (so that later
 *  phases such as Constructors still see the shapes they expect) and get a
 *  reverse bridge `f$unboxed = toRep(this.f())` instead. Deferred methods get
 *  a deferred variant.
 *
 *  Because slots depend on the static Option argument, an override may have a
 *  different variant descriptor than the method it overrides (e.g.
 *  `Repo[String]#get$unboxed(): String` vs `Repo[T]#get$unboxed(): Object`).
 *  Every concrete method therefore also emits bridges for the distinct
 *  inherited variant descriptors of the methods it overrides, delegating to
 *  its own boxed entry point. This mirrors what `Erasure` does for type
 *  parameters, and keeps virtual dispatch on `$unboxed` names correct.
 *
 *  Because a bridge dispatches to `this.f$unboxed` virtually, a `super.f`
 *  call into a class whose implementation moved would bounce back down to the
 *  callee's own variant and loop. Therefore all super calls to methods whose
 *  body moves in this run are rewritten to call the super variant directly:
 *
 *      super.f(e) ==> fromRep(super.f$unboxed(toRep(e)))
 *
 *  The optimizer can later be taught to call `$unboxed` entry points across
 *  method boundaries directly, eliminating `Some` allocations at call sites.
 *
 *  Known limitations:
 *    - `Some(null)` and `null` Options crossing a specialized boundary throw
 *      (see above); the boxed originals accepted them.
 *    - when a class compiled with this flag overrides a method that was
 *      compiled with the flag in a *previous* run (i.e. loaded from
 *      tasty/classfiles), super calls to that method are not rewritten (the
 *      variant symbol is not visible) and may loop at runtime. Fixing this
 *      requires marking flag-compiled classfiles, e.g. with an attribute.
 */
class UnboxedOptions extends MiniPhase with IdentityDenotTransformer { thisPhase =>
  import ast.tpd.*

  override def phaseName: String = UnboxedOptions.name
  override def description: String = UnboxedOptions.description
  override def isEnabled(using Context): Boolean = ctx.settings.YunboxedOptions.value
  override def changesMembers: Boolean = true
  override def runsAfter: Set[String] = Set(Mixin.name, Memoize.name)

  /** Memoized unboxed variants. Shared across the compilation units of a run
   *  so that super-call rewriting in one unit can refer to the variant of a
   *  method whose own unit has not been transformed yet; the defining unit
   *  will find the memoized symbol and emit the corresponding DefDef.
   *  (Phase instances are created afresh for each run.)
   */
  private val variants = MutableSymbolMap[TermSymbol]()

  /** Cached per-method slot types: Option positions carry their specialized
   *  slot type, other positions their erased type unchanged.
   */
  private val slotsCache = MutableSymbolMap[(List[Type], Type)]()

  private def isErasedOption(tp: Type)(using Context): Boolean = tp match
    case tp: TypeRef => tp.symbol eq defn.OptionClass
    case _ => false

  private def affectsSig(info: Type)(using Context): Boolean = info match
    case mt: MethodType => mt.paramInfos.exists(isErasedOption) || isErasedOption(mt.resultType)
    case _ => false

  // ----- slot computation ---------------------------------------------------

  /** The slot type for an Option position whose pre-erasure type is `preTp`:
   *  the erasure of the Option's type argument, boxed for primitives, the box
   *  class for derived value classes, and Object for generic, nullable,
   *  bottom, or unrecognizable arguments.
   */
  private def slotFor(preTp: Type)(using Context): Type = atPhase(erasurePhase) {
    preTp.baseType(defn.OptionClass).argInfos match
      case arg :: Nil =>
        val payload = arg.stripNull()
        if payload ne arg then defn.ObjectType // nullable payload: needs the total generic encoding
        else
          val cls = payload.widenDealias.classSymbol
          if cls.isDerivedValueClass then cls.typeRef // the runtime payload is the box
          else
            TypeErasure.erasure(payload) match
              case er: JavaArrayType => er
              case er if er.isPrimitiveValueType => defn.boxedType(er)
              case er =>
                val ecls = er.classSymbol
                if !ecls.exists
                   || (ecls eq defn.ObjectClass) || (ecls eq defn.AnyClass)
                   || (ecls eq defn.NothingClass) || (ecls eq defn.NullClass)
                then defn.ObjectType
                else er
      case _ => defn.ObjectType
  }

  private def computeSlots(sym: Symbol)(using Context): (List[Type], Type) =
    val mt = sym.info.asInstanceOf[MethodType]
    def objectSlots =
      (mt.paramInfos.map(pi => if isErasedOption(pi) then defn.ObjectType else pi),
       if isErasedOption(mt.resultType) then defn.ObjectType else mt.resultType)
    if sym.initial.validFor.firstPhaseId > erasurePhase.id then
      objectSlots // symbol created during or after erasure: no generic info to specialize on
    else
      val preInfo = atPhase(erasurePhase)(sym.info)
      def flatParams(tp: Type): List[Type] = tp match
        case tp: PolyType => flatParams(tp.resultType)
        case tp: MethodType => tp.paramInfos ::: flatParams(tp.resultType)
        case _ => Nil
      val preParams = flatParams(preInfo)
      if preParams.length != mt.paramInfos.length then
        objectSlots // e.g. context-function results integrate extra params at erasure
      else
        val params = mt.paramInfos.lazyZip(preParams).map { (er, pre) =>
          if isErasedOption(er) then slotFor(pre) else er
        }
        val result =
          if isErasedOption(mt.resultType) then slotFor(preInfo.finalResultType)
          else mt.resultType
        (params, result)

  private def optionSlots(sym: Symbol)(using Context): (List[Type], Type) =
    slotsCache.getOrElseUpdate(sym, computeSlots(sym))

  private def unboxedInfo(sym: Symbol)(using Context): MethodType =
    val mt = sym.info.asInstanceOf[MethodType]
    val (params, result) = optionSlots(sym)
    mt.derivedLambdaType(paramInfos = params, resType = result).asInstanceOf[MethodType]

  // ----- eligibility --------------------------------------------------------

  private def eligible(sym: Symbol)(using Context): Boolean =
    sym.is(Method, butNot = Bridge)
    && !sym.isConstructor
    && !sym.is(JavaDefined)
    && !sym.name.is(UnboxedMethName)
    && !sym.hasAnnotation(defn.NativeAnnot)
    && !sym.hasAnnotation(defn.ScalaStaticAnnot)
    && affectsSig(sym.info)

  private def sameSig(a: Type, b: Type)(using Context): Boolean = (a, b) match
    case (a: MethodType, b: MethodType) =>
      a.paramInfos.corresponds(b.paramInfos)(_ =:= _) && a.resultType =:= b.resultType
    case _ => false

  /** Two same-name overloads may map to the same unboxed signature, e.g.
   *  `f(x: Option[Any]): Option[Int]` and `f(x: AnyRef): Option[Int]`. In that
   *  case no variant is generated for either of them.
   */
  private def clashes(sym: Symbol)(using Context): Boolean =
    val myInfo = unboxedInfo(sym)
    sym.owner.info.decls.lookupAll(sym.name).exists(alt =>
      (alt ne sym) && alt.is(Method) && eligible(alt) && sameSig(unboxedInfo(alt), myInfo))

  private def generatesVariant(sym: Symbol)(using Context): Boolean =
    sym.owner.isClass
    && eligible(sym)
    && sym.isDefinedInCurrentRun
    && !clashes(sym)

  /** Does the implementation of `sym` move to its unboxed variant, leaving the
   *  boxed method as a bridge? If so, super calls to `sym` must be rewritten
   *  to target the variant.
   */
  private def movesBody(sym: Symbol)(using Context): Boolean =
    generatesVariant(sym) && !sym.is(Deferred) && !sym.is(Accessor)

  private def variantOf(sym: Symbol)(using Context): TermSymbol =
    variants.getOrElseUpdate(sym, {
      newSymbol(
        owner = sym.owner,
        name = UnboxedMethName(sym.name.asTermName),
        flags = (sym.flags &~ (Accessor | ParamAccessor | CaseAccessor | Lazy | Inline)) | Synthetic,
        info = unboxedInfo(sym),
        privateWithin = sym.privateWithin,
        coord = sym.coord
      ).enteredAfter(thisPhase).asTerm
    })

  // ----- representation conversions ----------------------------------------

  private def isGenericSlot(slot: Type)(using Context): Boolean =
    slot.isRef(defn.ObjectClass)

  /** `opt` (an Option-typed tree) converted to the representation of `slot`. */
  private def toRep(opt: Tree, slot: Type)(using Context): Tree =
    if isGenericSlot(slot) then ref(defn.UnboxedOptions_unbox).appliedTo(opt)
    else ref(defn.UnboxedOptions_unboxPrecise).appliedTo(opt).asInstance(slot)

  /** `rep` (a `slot`-typed representation) converted back to an Option. */
  private def fromRep(rep: Tree, slot: Type)(using Context): Tree =
    if isGenericSlot(slot) then ref(defn.UnboxedOptions_box).appliedTo(rep)
    else ref(defn.UnboxedOptions_boxPrecise).appliedTo(rep)

  // ----- transformations ----------------------------------------------------

  /** Rewrite `super.f(args)` to `fromRep(super.f$unboxed(toRep(args)))` when
   *  the implementation of `f` moves to its variant in this run.
   */
  override def transformApply(tree: Apply)(using Context): Tree = tree.fun match
    case sel @ Select(sup: Super, _) if movesBody(sel.symbol) =>
      val target = variantOf(sel.symbol)
      val mt = sel.symbol.info.asInstanceOf[MethodType]
      val (paramSlots, resultSlot) = optionSlots(sel.symbol)
      val args = tree.args.lazyZip(mt.paramInfos).lazyZip(paramSlots).map { (arg, formal, slot) =>
        if isErasedOption(formal) then toRep(arg, slot) else arg
      }
      val call = sup.select(target).appliedToTermArgs(args)
      if isErasedOption(mt.resultType) then fromRep(call, resultSlot) else call
    case _ =>
      tree

  /** Bridges implementing inherited `$unboxed` descriptors that differ from
   *  this method's own variant signature. Also emitted when the method itself
   *  generates no variant (e.g. a covariant override whose result erases to
   *  `Some`) but overrides methods that do.
   */
  private def inheritedBridges(sym: Symbol, ownInfo: List[MethodType])(using Context): List[Tree] =
    if sym.initial.validFor.firstPhaseId > erasurePhase.id then Nil
    else
      val overridden = atPhase(erasurePhase)(sym.allOverriddenSymbols.toList).filter(generatesVariant)
      if overridden.isEmpty then Nil
      else
        val done = mutable.ListBuffer[MethodType](ownInfo*)
        overridden.flatMap { o =>
          val oInfo = unboxedInfo(o)
          if done.exists(sameSig(_, oInfo)) then Nil
          else
            done += oInfo
            val omt = o.info.asInstanceOf[MethodType]
            val (oParamSlots, oResultSlot) = optionSlots(o)
            val bsym = newSymbol(
              owner = sym.owner,
              name = UnboxedMethName(sym.name.asTermName),
              flags = (sym.flags &~ (Accessor | ParamAccessor | CaseAccessor | Lazy | Inline | Deferred))
                | Synthetic | Bridge,
              info = oInfo,
              privateWithin = sym.privateWithin,
              coord = sym.coord
            ).enteredAfter(thisPhase).asTerm
            DefDef(bsym, argss => {
              val call = This(sym.owner.asClass).select(sym).appliedToTermArgs(
                argss.flatten.lazyZip(omt.paramInfos).lazyZip(oParamSlots).map { (r, formal, slot) =>
                  if isErasedOption(formal) then fromRep(r, slot) else r
                })
              if isErasedOption(omt.resultType) then toRep(call, oResultSlot) else call
            }) :: Nil
        }

  override def transformDefDef(tree: DefDef)(using Context): Tree =
    val sym = tree.symbol
    if !sym.owner.isClass || sym.isConstructor || !sym.is(Method, butNot = Bridge)
       || sym.is(JavaDefined) || sym.name.is(UnboxedMethName)
    then tree
    else
      val hasOwn = generatesVariant(sym)
      val concrete = !sym.is(Deferred) && !tree.rhs.isEmpty
      lazy val bridges =
        if concrete then inheritedBridges(sym, if hasOwn then unboxedInfo(sym) :: Nil else Nil)
        else Nil
      if !hasOwn then
        if !concrete || bridges.isEmpty then tree
        else Thicket(tree :: bridges)
      else
        val unboxedSym = variantOf(sym)
        val mt = sym.info.asInstanceOf[MethodType]
        val (paramSlots, resultSlot) = optionSlots(sym)
        val resultIsOption = isErasedOption(mt.resultType)

        def convertArgs(refs: List[Tree], convert: (Tree, Type) => Tree): List[Tree] =
          refs.lazyZip(mt.paramInfos).lazyZip(paramSlots).map { (r, formal, slot) =>
            if isErasedOption(formal) then convert(r, slot) else r
          }

        if !concrete then
          Thicket(tree :: DefDef(unboxedSym, EmptyTree) :: Nil)
        else if sym.is(Accessor) then
          // Keep the accessor implementation where later phases expect it;
          // the variant is a reverse bridge into the boxed accessor.
          val unboxedDef = DefDef(unboxedSym, argss => {
            val call = This(sym.owner.asClass).select(sym)
              .appliedToTermArgs(convertArgs(argss.flatten, fromRep))
            if resultIsOption then toRep(call, resultSlot) else call
          })
          Thicket(tree :: unboxedDef :: bridges)
        else
          // Move the implementation to the variant, re-boxing Option parameters
          // on entry, and turn the boxed method into a bridge.
          val unboxedDef = DefDef(unboxedSym, argss => {
            val oldParams = tree.termParamss.flatten.map(_.symbol)
            val valDefs = List.newBuilder[ValDef]
            val substTo = oldParams.lazyZip(argss.flatten).lazyZip(paramSlots).map { (old, nref, slot) =>
              if isErasedOption(old.info) then
                val reboxed = newSymbol(unboxedSym, old.name.asTermName, Synthetic,
                  defn.OptionClass.typeRef, coord = old.coord)
                valDefs += ValDef(reboxed, fromRep(nref, slot))
                reboxed
              else nref.symbol
            }
            val moved = TreeTypeMap(
              oldOwners = sym :: Nil,
              newOwners = unboxedSym :: Nil,
              substFrom = oldParams,
              substTo = substTo
            ).transform(tree.rhs)
            val retargetReturns = new TreeMap {
              override def transform(t: Tree)(using Context): Tree = t match
                case r: Return if r.from.symbol == sym =>
                  val expr = transform(r.expr)
                  cpy.Return(r)(
                    if resultIsOption then toRep(expr, resultSlot) else expr,
                    Ident(unboxedSym.termRef))
                case _ =>
                  super.transform(t)
            }
            val body = retargetReturns.transform(moved)
            seq(valDefs.result(), if resultIsOption then toRep(body, resultSlot) else body)
          })
          val bridgeCall = This(sym.owner.asClass).select(unboxedSym)
            .appliedToTermArgs(convertArgs(tree.termParamss.flatten.map(p => ref(p.symbol)), toRep))
          val bridgeRhs = if resultIsOption then fromRep(bridgeCall, resultSlot) else bridgeCall
          Thicket(cpy.DefDef(tree)(rhs = bridgeRhs) :: unboxedDef :: bridges)
}
