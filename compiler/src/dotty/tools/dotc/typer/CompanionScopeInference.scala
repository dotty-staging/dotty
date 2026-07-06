package dotty.tools
package dotc
package typer

import config.Feature
import core.*
import Contexts.*
import Decorators.*
import Flags.*
import Names.*
import NameOps.*
import StdNames.nme
import Symbols.*
import Types.*
import ProtoTypes.*
import Inferencing.isFullyDefined
import Implicits.ContextualImplicits
import reporting.StoreReporter
import util.NoSourcePosition
import ast.untpd
import scala.collection.mutable

/** Shared helpers for SIP-80 companion scope inference.
 *
 *  Both `Typer.tryCompanionScopeInference` (the resolution fallback) and
 *  `ErrorReporting.typeMismatch` (the silent-shadow diagnostic hint) use the
 *  same expected-type reduction and companion lookup, so they live here.
 */
object CompanionScopeInference:

  /** Reduce `pt` to its principal class type for the purposes of companion
   *  lookup. Returns `NoType` when no useful target can be determined
   *  (proto types without a result, wildcard, uninstantiated type vars,
   *  type bounds, non-`Null` unions).
   */
  def principalTarget(tp: Type)(using Context): Type = tp match
    case tp: SelectionProto =>
      val recv = receiverTarget(tp)
      if recv.exists then principalTarget(recv)
      else principalTarget(tp.memberProto)
    case tp: IgnoredProto   => principalTarget(tp.ignored)
    case tp: FunProto       => principalTarget(tp.resultType)
    case tp: PolyProto      => principalTarget(tp.resType)
    case tp: ProtoType      => NoType
    case tp: AnnotatedType  => principalTarget(tp.parent)
    case tp: TypeBounds     => NoType
    case tp: TypeVar =>
      val inst = tp.instanceOpt
      if inst.exists then principalTarget(inst)
      else
        val hi =
          if ctx.typerState.constraint.contains(tp.origin)
          then TypeComparer.fullUpperBound(tp.origin)
          else NoType
        if !hi.exists || hi.isExactlyAny then NoType
        else principalTarget(hi)
    case tp if tp eq WildcardType => NoType
    case tp =>
      val w = tp.widenExpr
      w match
        case w: TypeVar => principalTarget(w)
        case OrType(lhs, rhs) if rhs.classSymbol == defn.NullClass => principalTarget(lhs)
        case OrType(lhs, rhs) if lhs.classSymbol == defn.NullClass => principalTarget(rhs)
        case _ => w.dropDependentRefinement

  /** Receiver-position extension of the target reduction (general rule, no
   *  operator special cases): when the identifier being resolved is the
   *  qualifier of a selection `X.op(args)`, `X` itself has no expected type —
   *  only the fully applied selection's result does. Candidates for `op` that
   *  are applicable without knowing the receiver are members provided by
   *  conversion-shaped implicits visible in the implicit context (implicit
   *  classes such as `Predef.ArrowAssoc`, implicit conversion defs,
   *  `given Conversion` values). For each candidate, instantiate its type
   *  parameters by unifying the member's final result type with the expected
   *  result of the selection; this determines the conversion's input type,
   *  which is the expected type of the receiver. The derivation is used only
   *  when all successful candidates agree on a single fully-defined receiver
   *  type.
   *
   *  Example: in `val m: Map[Color, List[Color]] = [Red -> [Green]]` the key
   *  `Red` is the receiver of `->`. `Predef.ArrowAssoc` provides
   *  `->[B](y: B): (A, B)`; unifying `(A, B)` with the expected element type
   *  `(Color, List[Color])` gives `A := Color`, so `Red` is looked up in
   *  `Color`'s companion.
   *
   *  All constraint probing runs in an exploring typer state; only the
   *  instantiated receiver type escapes.
   */
  def receiverTarget(selPt: SelectionProto)(using Context): Type =
    val opName = selPt.name
    if !opName.isTermName then NoType
    else
      val expectedRes = stripResultProto(selPt.memberProto)
      if !expectedRes.exists
        || expectedRes.isInstanceOf[ProtoType]
        || (expectedRes eq WildcardType)
        || !expectedRes.isValueType
        || expectedRes.isExactlyAny
      then NoType
      else
        val name = opName.toTermName
        val convCandidates =
          for
            convRef <- implicitContextRefs
            recv <- probeConversion(convRef, name, expectedRes)
          yield recv
        val extCandidates =
          for
            methRef <- extensionRefs(name)
            recv <- probeExtension(methRef, expectedRes)
          yield recv
        val distinct = (convCandidates ::: extCandidates).foldLeft(List.empty[Type]): (acc, tp) =>
          if acc.exists(_.frozen_=:=(tp)) then acc else tp :: acc
        distinct match
          case recv :: Nil => recv
          case _ => NoType

  /** Peel argument/result prototype layers down to the expected type of the
   *  fully applied selection. */
  private def stripResultProto(tp: Type)(using Context): Type = tp match
    case tp: FunProto     => stripResultProto(tp.resultType)
    case tp: PolyProto    => stripResultProto(tp.resType)
    case tp: IgnoredProto => stripResultProto(tp.ignored)
    case _ => tp

  /** All term references visible in the implicit context. */
  private def implicitContextRefs(using Context): List[TermRef] =
    def loop(ci: ContextualImplicits | Null, acc: List[TermRef]): List[TermRef] =
      if ci == null then acc
      else loop(ci.outerImplicits, acc ::: ci.refs.map(_.underlyingRef))
    loop(ctx.implicits, Nil)

  /** Extension methods named `opName` visible in scope (including via
   *  imports), looked up with a suppressed reporter so speculative failures
   *  and ambiguities do not surface. */
  private def extensionRefs(opName: TermName)(using Context): List[TermRef] =
    try
      val altImports = mutable.ListBuffer[TermRef]()
      val probeCtx = ctx.fresh.setExploreTyperState().setReporter(StoreReporter())
      val found = ctx.typer.findRef(
        opName, WildcardType, ExtensionMethod, EmptyFlags, NoSourcePosition, altImports)(using probeCtx)
      val roots = found match
        case ref: TermRef => ref :: altImports.toList
        case _ => altImports.toList
      roots.flatMap: ref =>
        ref.denot.alternatives.collect:
          case alt if alt.symbol.is(ExtensionMethod) => TermRef(ref.prefix, alt.symbol.asTerm)
    catch case scala.util.control.NonFatal(_) => Nil

  /** Instantiate any type-parameter sections of `tp` with fresh type
   *  variables in the current (exploring) constraint. */
  private def instantiated(tp: Type)(using Context): Type = tp match
    case tp: PolyType => instantiated(tp.instantiate(constrained(tp)))
    case _ => tp

  /** Extract the fully-instantiated receiver type after unification, checking
   *  that it is a single well-formed class type. `ForceDegree.flipBottom`
   *  instantiates a variable bounded only from above to its upper bound. */
  private def instantiatedReceiver(recv: Type)(using Context): Option[Type] =
    if isFullyDefined(recv, ForceDegree.flipBottom) then
      val recv1 = recv.stripTypeVar
      if recv1.exists && !recv1.isExactlyAny && !recv1.isExactlyNothing && recv1.classSymbol.exists
      then Some(recv1)
      else None
    else None

  /** If `convRef` is conversion-shaped — a method with a single explicit
   *  value parameter, or a value of type `Conversion[A, C]` — and its target
   *  type has a member `opName` whose final result type unifies with
   *  `expectedRes`, return the instantiated conversion input type.
   */
  private def probeConversion(convRef: TermRef, opName: TermName, expectedRes: Type)(using Context): Option[Type] =
    try explore:
      def conversionShape(tp: Type): Option[(Type, Type)] = instantiated(tp.widenDealias) match
        case et: ExprType => conversionShape(et.resultType)
        case mt: MethodType
        if mt.paramInfos.length == 1 && !mt.isImplicitMethod && !mt.isContextualMethod
           && !mt.isResultDependent =>
          Some((mt.paramInfos.head, mt.resultType))
        case tp =>
          tp.baseType(defn.ConversionClass).argInfos match
            case from :: to :: Nil => Some((from, to))
            case _ => None
      def finalResult(tp: Type): Type = instantiated(tp) match
        case mt: MethodType =>
          if mt.isResultDependent then NoType else finalResult(mt.resultType)
        case tp => tp
      conversionShape(convRef.widen) match
        case Some((recv, target)) if target.exists && target.classSymbol.exists =>
          val resultMatches = target.member(opName).alternatives.exists: alt =>
            val fr = finalResult(target.memberInfo(alt.symbol))
            fr.exists && (fr <:< expectedRes)
          if resultMatches then instantiatedReceiver(recv) else None
        case _ => None
    catch case scala.util.control.NonFatal(_) => None

  /** If extension method `methRef` — shape `[As](recv: T)[Bs](args): R`, with
   *  any interleaving of type and using clauses — has a final result type
   *  that unifies with `expectedRes`, return the instantiated receiver
   *  parameter type.
   */
  private def probeExtension(methRef: TermRef, expectedRes: Type)(using Context): Option[Type] =
    try explore:
      def loop(tp: Type, recv: Type): (Type, Type) = instantiated(tp) match
        case mt: MethodType if mt.isImplicitMethod || mt.isContextualMethod =>
          if mt.isResultDependent then (NoType, NoType) else loop(mt.resultType, recv)
        case mt: MethodType if !recv.exists =>
          if mt.paramInfos.length != 1 || mt.isResultDependent then (NoType, NoType)
          else loop(mt.resultType, mt.paramInfos.head)
        case mt: MethodType =>
          if mt.isResultDependent then (NoType, NoType) else loop(mt.resultType, recv)
        case tp => (recv, tp)
      val (recv, finalRes) = loop(methRef.widen, NoType)
      if recv.exists && finalRes.exists && !finalRes.isInstanceOf[MethodicType]
         && (finalRes <:< expectedRes)
      then instantiatedReceiver(recv)
      else None
    catch case scala.util.control.NonFatal(_) => None

  /** The companion module to search for `target`'s SIP-80 lookup. Prefers
   *  the alias's own companion via `prefix.member(name)` (so opaque type
   *  aliases pick their own companion), falling back to
   *  `classSymbol.companionModule`.
   */
  def companionFor(target: Type)(using Context): Symbol =
    val byPrefix = target match
      case ref: TypeRef =>
        val prefix = ref.prefix
        if prefix.exists && prefix.ne(NoPrefix) then
          val mem = prefix.member(ref.name.toTermName)
          if mem.exists then mem.suchThat(_.is(Module)).symbol else NoSymbol
        else NoSymbol
      case _ => NoSymbol
    if byPrefix.exists then byPrefix
    else
      val cls = target.classSymbol
      if cls.exists then cls.companionModule else NoSymbol

  /** Is `member` an anonymous given (and therefore ineligible per SIP-80)? */
  def isAnonymousGiven(sym: Symbol)(using Context): Boolean =
    sym.is(Given) && sym.name.toString.startsWith("given_")

  /** Source-style fully qualified path for `companion` — `Foo.Bar` rather
   *  than the module-class form `Foo$.Bar` that `Symbol.fullName` returns. */
  private def companionPath(companion: Symbol)(using Context): String =
    companion.showFullName

  /** Render a hint pointing the user at `companion.name` when companion
   *  inference would have produced a different (better) result than normal
   *  resolution. Returns `None` if no such alternative exists.
   */
  def shadowingHint(name: Name, pt: Type)(using Context): Option[String] =
    if !Feature.enabled(Feature.companionScopeInference) then None
    else if !name.isTermName then None
    else
      val target = principalTarget(pt)
      if !target.exists then None
      else
        val companion = companionFor(target)
        if !companion.exists then None
        else
          val termName = name.toTermName
          val member = companion.info.member(termName)
          if !member.exists || isAnonymousGiven(member.symbol) then None
          else
            val path = companionPath(companion)
            Some:
              i"\n\nNote: `$path` has a member named `$termName`; qualify as `$path.$termName` if you meant that."

  /** Render a hint for a "not found" error after companion scope inference also
   *  failed: name the companion that was searched. */
  def notFoundHint(name: Name, pt: Type)(using Context): Option[String] =
    if !Feature.enabled(Feature.companionScopeInference) then None
    else
      val target = principalTarget(pt)
      if !target.exists then None
      else
        val companion = companionFor(target)
        if !companion.exists then None
        else Some:
          i"\n\nSearched expected type `$target`'s companion; no term-level member named `$name`."

end CompanionScopeInference
