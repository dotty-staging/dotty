package dotty.tools
package dotc
package ast

import core._
import util.Spans._, Types._, Contexts._, Constants._, Names._, Flags._
import Symbols._, StdNames._, Trees._
import Decorators._
import util.{Property, SourceFile}
import typer.ErrorReporting._
import transform.SyntheticMembers.ExtendsSingletonMirror

import scala.annotation.internal.sharable
import dotty.tools.dotc.core.NameKinds.DefaultGetterName

/** Helper methods to desugar enums */
object DesugarEnums {
  import untpd._

  enum CaseKind:
    case Simple, SimpleFactory, Object, Class

  final case class EnumConstraints(seen: Set[CaseKind], enumCases: List[(Int, RefTree)]):
    require(!(cached && enumCases.isEmpty))
    def requiresCreator = seen.contains(CaseKind.Simple)
    def requiresFactory = seen.contains(CaseKind.SimpleFactory)
    def isEnumeration   = !seen.contains(CaseKind.Class)
    def cached          = !seen.contains(CaseKind.Class)
  end EnumConstraints

  /** Attachment containing the number of enum cases, the smallest kind that was seen so far,
   *  and a list of all the value cases with their ordinals.
   */
  val EnumCaseCount: Property.Key[(Int, Set[CaseKind], List[(Int, TermName)])] = Property.Key()

  /** Attachment signalling that when this definition is desugared, it should add any additional
   *  lookup methods for enums.
   */
  val DefinesEnumLookupMethods: Property.Key[PreScaffoldingData] = Property.Key()

  /** The type of data necessary to build enum scaffolding from the surrounding context,
   *  currently a list of expanded constructor methods for the enum base class
   */
  type PreScaffoldingData = (List[DefDef], List[DefDef])

  /** The enumeration class that belongs to an enum case. This works no matter
   *  whether the case is still in the enum class or it has been transferred to the
   *  companion object.
   */
  def enumClass(using Context): Symbol = {
    val cls = ctx.owner
    if (cls.is(Module)) cls.linkedClass else cls
  }

  def enumCompanion(using Context): Symbol = {
    val cls = ctx.owner
    if (cls.is(Module)) cls.sourceModule else cls.linkedClass.sourceModule
  }

  /** Is `tree` an (untyped) enum case? */
  def isEnumCase(tree: Tree)(using Context): Boolean = tree match {
    case tree: MemberDef => tree.mods.isEnumCase
    case PatDef(mods, _, _, _) => mods.isEnumCase
    case _ => false
  }

  /** A reference to the enum class `E`, possibly followed by type arguments.
   *  Each covariant type parameter is approximated by its lower bound.
   *  Each contravariant type parameter is approximated by its upper bound.
   *  It is an error if a type parameter is non-variant, or if its approximation
   *  refers to pther type parameters.
   */
  def interpolatedEnumArgs(span: Span)(using Context): List[Tree] = {
    val tparams = enumClass.typeParams
    def isGround(tp: Type) = tp.subst(tparams, tparams.map(_ => NoType)) eq tp
    val targs = tparams map { tparam =>
      if (tparam.is(Covariant) && isGround(tparam.info.bounds.lo))
        tparam.info.bounds.lo
      else if (tparam.is(Contravariant) && isGround(tparam.info.bounds.hi))
        tparam.info.bounds.hi
      else {
        def problem =
          if (!tparam.isOneOf(VarianceFlags)) "is invariant"
          else "has bounds that depend on a type parameter in the same parameter list"
        errorType(em"""cannot determine type argument for enum parent $enumClass,
                      |type parameter $tparam $problem""", ctx.source.atSpan(span))
      }
    }
    targs.map(TypeTree(_).withSpan(span))
  }

  /** A type tree referring to `enumClass` */
  def enumClassRef(using Context): Tree =
    if (enumClass.exists) TypeTree(enumClass.typeRef) else TypeTree()

  /** Add implied flags to an enum class or an enum case */
  def addEnumFlags(cdef: TypeDef)(using Context): TypeDef =
    if (cdef.mods.isEnumClass) cdef.withMods(cdef.mods.withAddedFlags(Abstract | Sealed, cdef.span))
    else if (isEnumCase(cdef)) cdef.withMods(cdef.mods.withAddedFlags(Final, cdef.span))
    else cdef

  private def valuesDot(name: PreName)(implicit src: SourceFile) =
    Select(Ident(nme.DOLLAR_VALUES), name.toTermName)

  private def ArrayLiteral(values: List[Tree], tpt: Tree)(using Context): Tree =
    val clazzOf = TypeApply(ref(defn.Predef_classOf.termRef), tpt :: Nil)
    val ctag    = Apply(TypeApply(ref(defn.ClassTagModule_apply.termRef), tpt :: Nil), clazzOf :: Nil)
    val apply   = Select(ref(defn.ArrayModule.termRef), nme.apply)
    Apply(Apply(TypeApply(apply, tpt :: Nil), values), ctag :: Nil)

  /**  The following lists of definitions for an enum type E and known value cases e_0, ..., e_n:
   *
   *   private val $values = Array[E](this.e_0,...,this.e_n)(ClassTag[E](classOf[E]))
   *   def values = $values.clone
   *   def valueOf($name: String) = $name match {
   *     case "e_0" => this.e_0
   *     ...
   *     case "e_n" => this.e_n
   *     case _ => throw new IllegalArgumentException("case not found: " + $name)
   *   }
   */
  private def enumScaffolding(enumValues: List[RefTree])(using Context): List[Tree] = {
    val rawEnumClassRef = rawRef(enumClass.typeRef)
    extension (tpe: NamedType) def ofRawEnum = AppliedTypeTree(ref(tpe), rawEnumClassRef)

    val privateValuesDef =
      ValDef(nme.DOLLAR_VALUES, TypeTree(), ArrayLiteral(enumValues, rawEnumClassRef))
        .withFlags(Private | Synthetic)

    val valuesDef =
      DefDef(nme.values, Nil, defn.ArrayType.ofRawEnum, valuesDot(nme.clone_))
        .withFlags(Synthetic)

    val valuesOfBody: Tree =
      val defaultCase =
        val msg = Apply(Select(Literal(Constant("enum case not found: ")), nme.PLUS), Ident(nme.nameDollar))
        CaseDef(Ident(nme.WILDCARD), EmptyTree,
          Throw(New(TypeTree(defn.IllegalArgumentExceptionType), List(msg :: Nil))))
      val stringCases = enumValues.map(enumValue =>
        CaseDef(Literal(Constant(enumValue.name.toString)), EmptyTree, enumValue)
      ) ::: defaultCase :: Nil
      Match(Ident(nme.nameDollar), stringCases)
    val valueOfDef = DefDef(nme.valueOf, List(param(nme.nameDollar, defn.StringType) :: Nil),
      TypeTree(), valuesOfBody)
        .withFlags(Synthetic)

    privateValuesDef ::
    valuesDef ::
    valueOfDef :: Nil
  }

  private def enumLookupMethods(
      ctors: List[DefDef], defaultGetters: List[DefDef], constraints: EnumConstraints)(using Context): List[Tree] =
    def scaffolding: List[Tree] =
      if constraints.isEnumeration then enumScaffolding(constraints.enumCases.map(_._2)) else Nil
    def valueCtor: List[Tree] = if constraints.requiresCreator then enumValueCreator :: Nil else Nil
    def valueFactories: List[Tree] =
      if constraints.requiresFactory then
        ctors.flatMap(derivedEnumValueCreator) ::: defaultGetters.map(derivedDefaultGetter)
      else
        Nil
    def fromOrdinal: Tree =
      def throwArg(ordinal: Tree) =
        Throw(New(TypeTree(defn.NoSuchElementExceptionType), List(Select(ordinal, nme.toString_) :: Nil)))
      if !constraints.cached then
        fromOrdinalMeth(throwArg)
      else
        def default(ordinal: Tree) =
          CaseDef(Ident(nme.WILDCARD), EmptyTree, throwArg(ordinal))
        if constraints.isEnumeration then
          fromOrdinalMeth(ordinal =>
            Try(Apply(valuesDot(nme.apply), ordinal), default(ordinal) :: Nil, EmptyTree))
        else
          fromOrdinalMeth(ordinal =>
            Match(ordinal,
              constraints.enumCases.map((i, enumValue) => CaseDef(Literal(Constant(i)), EmptyTree, enumValue))
              :+ default(ordinal)))

    if !enumClass.exists then
      // in the case of a double definition of an enum that only defines class cases (see tests/neg/i4470c.scala)
      // it seems `enumClass` might be `NoSymbol`; in this case we provide no scaffolding.
      Nil
    else
      scaffolding ::: valueCtor ::: valueFactories ::: fromOrdinal :: Nil
  end enumLookupMethods

  /** A creation method for a value of enum type `E`, which is defined as follows:
   *
   *   private def $new(_$ordinal: Int, $name: String) = new E with scala.runtime.EnumValue {
   *     def ordinal = _$ordinal // if `E` does not derive from `java.lang.Enum`
   *   }
   */
  private def enumValueCreator(using Context) = {
    val creator = New(Template(
      constr = emptyConstructor,
      parents = enumClassRef :: scalaRuntimeDot(tpnme.EnumValue) :: Nil,
      derived = Nil,
      self = EmptyValDef,
      body = Nil
    ).withAttachment(ExtendsSingletonMirror, ()))
    DefDef(nme.DOLLAR_NEW,
        List(List(param(nme.ordinalDollar_, defn.IntType), param(nme.nameDollar, defn.StringType))),
        TypeTree(), creator).withFlags(Private | Synthetic)
  }

  /** Copies a constructor's parameter lists to `def $new<paramss>(_$ordinal, $name) : E[tps] = ...` */
  private def derivedEnumValueCreator(ctor: DefDef)(using Context): Option[DefDef] = ctor match
    case DefDef(_, Nil, _, _) => None
    case DefDef(_, paramss, _, _) =>

      def ctorArgs(targs: List[Tree], args: List[List[Tree]], paramss: List[ParamClause]): (List[Tree], List[List[Tree]]) = (paramss: @unchecked) match
        case Nil => (targs, args.reverse)
        case ValDefs(vdefs) :: rest =>
          ctorArgs(targs, vdefs.map(vdef => Ident(vdef.name)) :: args, rest)
        case TypeDefs(tdefs) :: rest =>
          assert(targs.isEmpty, "unexpected multiple type parameter lists in constructor")
          ctorArgs(tdefs.map(tdef => Ident(tdef.name)), args, rest)
      end ctorArgs

      def ctorApply(acc: Tree, argss: List[List[Tree]]): Tree = argss match
        case Nil => acc
        case args :: rest => ctorApply(Apply(acc, args), rest)

      val derivedParamss: List[ParamClause] = desugar.copyParamss(paramss)

      val (targs0, args) = ctorArgs(Nil, Nil, derivedParamss)

      val returnTpt = targs0 match
        case Nil => enumClassRef
        case targs => AppliedTypeTree(enumClassRef, targs)

      val init = args match
        case Nil => returnTpt
        case _ => Select(New(returnTpt), nme.CONSTRUCTOR)

      val creator = New(Template(
        constr = emptyConstructor,
        parents = ctorApply(init, args) :: scalaRuntimeDot(tpnme.EnumValue) :: Nil,
        derived = Nil,
        self = EmptyValDef,
        body = Nil
      ).withAttachment(ExtendsSingletonMirror, ()))

      val ddef = DefDef(nme.DOLLAR_NEW,
        (derivedParamss :+ List(param(nme.ordinalDollar_, defn.IntType), param(nme.nameDollar, defn.StringType))),
        returnTpt, creator).withFlags(Private | Synthetic)
      Some(ddef)
  end derivedEnumValueCreator

  private def derivedDefaultGetter(getter: DefDef)(using Context): DefDef =
    val DefDef(DefaultGetterName(_, n), paramss, tpt, rhs) = getter: @unchecked
    val paramss1 = desugar.copyParamss(paramss)
    DefDef(DefaultGetterName(nme.DOLLAR_NEW, n), paramss1, tpt, rhs)
      .withMods(getter.mods.withFlags(Private | Synthetic))
  end derivedDefaultGetter

  /** Is a type parameter in `enumTypeParams` referenced from an enum class case that has
   *  given type parameters `caseTypeParams`, value parameters `vparamss` and parents `parents`?
   *  Issues an error if that is the case but the reference is illegal.
   *  The reference could be illegal for two reasons:
   *   - explicit type parameters are given
   *   - it's a value case, i.e. no value parameters are given
   */
  def typeParamIsReferenced(
    enumTypeParams: List[TypeSymbol],
    caseTypeParams: List[TypeDef],
    vparamss: List[List[ValDef]],
    parents: List[Tree])(using Context): Boolean = {

    object searchRef extends UntypedTreeAccumulator[Boolean] {
      var tparamNames = enumTypeParams.map(_.name).toSet[Name]
      def underBinders(binders: List[MemberDef], op: => Boolean): Boolean = {
        val saved = tparamNames
        tparamNames = tparamNames -- binders.map(_.name)
        try op
        finally tparamNames = saved
      }
      def apply(x: Boolean, tree: Tree)(using Context): Boolean = x || {
        tree match {
          case Ident(name) =>
            val matches = tparamNames.contains(name)
            if (matches && (caseTypeParams.nonEmpty || vparamss.isEmpty))
              report.error(em"illegal reference to type parameter $name from enum case", tree.srcPos)
            matches
          case LambdaTypeTree(lambdaParams, body) =>
            underBinders(lambdaParams, foldOver(x, tree))
          case RefinedTypeTree(parent, refinements) =>
            val refinementDefs = refinements collect { case r: MemberDef => r }
            underBinders(refinementDefs, foldOver(x, tree))
          case _ => foldOver(x, tree)
        }
      }
      def apply(tree: Tree)(using Context): Boolean =
        underBinders(caseTypeParams, apply(false, tree))
    }

    def typeHasRef(tpt: Tree) = searchRef(tpt)
    def valDefHasRef(vd: ValDef) = typeHasRef(vd.tpt)
    def parentHasRef(parent: Tree): Boolean = parent match {
      case Apply(fn, _) => parentHasRef(fn)
      case TypeApply(_, targs) => targs.exists(typeHasRef)
      case Select(nu, nme.CONSTRUCTOR) => parentHasRef(nu)
      case New(tpt) => typeHasRef(tpt)
      case parent => parent.isType && typeHasRef(parent)
    }

    vparamss.nestedExists(valDefHasRef) || parents.exists(parentHasRef)
  }

  /** A pair consisting of
   *   - the next enum tag
   *   - optional scaffolding containing the necessary definitions for singleton enum cases,
   *     determined by `definesLookups`.
   */
  def nextOrdinal(name: Name, kind: CaseKind, definesLookups: Option[PreScaffoldingData])(using Context): (Int, List[Tree]) = {
    val (ordinal, seen, seenCases) =
      ctx.tree.removeAttachment(EnumCaseCount).getOrElse((0, Set.empty, Nil))
    val nextSeen = seen + kind
    val cases = name match
      case name: TermName => (ordinal, name) :: seenCases
      case _              => seenCases
    definesLookups match
      case Some((ctors, ctorDefaultGetters)) =>
        val thisRef = This(EmptyTypeIdent)
        val cachedValues = cases.reverse.map((i, name) => (i, Select(thisRef, name)))
        (ordinal, enumLookupMethods(ctors, ctorDefaultGetters, EnumConstraints(nextSeen, cachedValues)))
      case _ =>
        ctx.tree.pushAttachment(EnumCaseCount, (ordinal + 1, nextSeen, cases))
        (ordinal, Nil)
  }

  def param(name: TermName, typ: Type)(using Context): ValDef = param(name, TypeTree(typ))
  def param(name: TermName, tpt: Tree)(using Context): ValDef = ValDef(name, tpt, EmptyTree).withFlags(Param)

  def ordinalMeth(body: Tree)(using Context): DefDef =
    DefDef(nme.ordinal, Nil, TypeTree(defn.IntType), body).withAddedFlags(Synthetic)

  def ordinalMethLit(ord: Int)(using Context): DefDef =
    ordinalMeth(Literal(Constant(ord)))

  def fromOrdinalMeth(body: Tree => Tree)(using Context): DefDef =
    DefDef(nme.fromOrdinal, (param(nme.ordinal, defn.IntType) :: Nil) :: Nil,
      rawRef(enumClass.typeRef), body(Ident(nme.ordinal))).withFlags(Synthetic)

  private def isSimpleParent(fn: Tree, enumClass: Symbol)(using Context): Boolean = fn match
    case Ident(name) => name == enumClass.name
    case AppliedTypeTree(Ident(name), _) => name == enumClass.name
    case other => appliedCore(other) match
      case Select(New(Ident(name)), nme.CONSTRUCTOR) => name == enumClass.name
      case Select(New(AppliedTypeTree(Ident(name), _)), nme.CONSTRUCTOR) => name == enumClass.name
      case _ => false

  /** Expand a module definition representing a parameterless enum case */
  def expandEnumModule(name: TermName, impl: Template, mods: Modifiers, definesLookups: Option[PreScaffoldingData], span: Span)(using Context): Tree = {
    assert(impl.body.isEmpty)
    val cls = enumClass
    if (!cls.exists) EmptyTree
    else if (impl.parents.isEmpty)
      expandSimpleEnumCase(name, mods, definesLookups, span)
    else impl.parents match {
      case app :: Nil if isSimpleParent(app, cls) =>
        val (tag, scaffolding) = nextOrdinal(name, CaseKind.SimpleFactory, definesLookups)
        val creator = Apply(reapplyArgs(app, Ident(nme.DOLLAR_NEW)), List(Literal(Constant(tag)), Literal(Constant(name.toString))))
        val vdef = ValDef(name, TypeTree(), creator).withMods(mods.withAddedFlags(EnumValue, span))
        flatTree(vdef :: scaffolding).withSpan(span)
      case _ =>
        val (tag, scaffolding) = nextOrdinal(name, CaseKind.Object, definesLookups)
        val impl1 = cpy.Template(impl)(parents = impl.parents :+ scalaRuntimeDot(tpnme.EnumValue), body = Nil)
          .withAttachment(ExtendsSingletonMirror, ())
        val vdef = ValDef(name, TypeTree(), New(impl1)).withMods(mods.withAddedFlags(EnumValue, span))
        flatTree(vdef :: scaffolding).withSpan(span)
    }
  }

  /** Expand a simple enum case */
  def expandSimpleEnumCase(name: TermName, mods: Modifiers, definesLookups: Option[PreScaffoldingData], span: Span)(using Context): Tree =
    if (!enumClass.exists) EmptyTree
    else if (enumClass.typeParams.nonEmpty) {
      val targs = interpolatedEnumArgs(span)
      val (tag, scaffolding) = nextOrdinal(name, CaseKind.SimpleFactory, definesLookups)
      val creator = Apply(TypeApply(Ident(nme.DOLLAR_NEW), targs), List(Literal(Constant(tag)), Literal(Constant(name.toString))))
      val vdef = ValDef(name, TypeTree(), creator).withMods(mods.withAddedFlags(EnumValue, span))
      flatTree(vdef :: scaffolding).withSpan(span)
    }
    else {
      val (tag, scaffolding) = nextOrdinal(name, CaseKind.Simple, definesLookups)
      val creator = Apply(Ident(nme.DOLLAR_NEW), List(Literal(Constant(tag)), Literal(Constant(name.toString))))
      val vdef = ValDef(name, enumClassRef, creator).withMods(mods.withAddedFlags(EnumValue, span))
      flatTree(vdef :: scaffolding).withSpan(span)
    }
}
