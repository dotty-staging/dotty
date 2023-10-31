package tasty

object definitions {

// ====== Trees ======================================

  sealed trait Tree // Top level statement

// ------ Statements ---------------------------------

  sealed trait Statement extends Tree

  case class PackageClause(pkg: Term, body: List[Tree]) extends Tree

  case class Import(expr: Term, selector: List[ImportSelector]) extends Statement

  enum ImportSelector {
    case SimpleSelector(id: Id)
    case RenameSelector(id1: Id, id2: Id)
    case OmitSelector(id1: Id)
  }

  case class Id(name: String) extends Positioned     // untyped ident

// ------ Definitions ---------------------------------

  trait Definition {
    def name: String
    def owner: Definition = ???
  }

  // Does DefDef need a `def tpe: MethodType | PolyType`?
  case class ValDef(name: String, tpt: TypeTree, rhs: Option[Term]) extends Definition {
    def flags: FlagSet = ???
    def privateWithin: Option[Type] = ???
    def protectedWithin: Option[Type] = ???
    def annots: List[Term] = ???
  }
  case class DefDef(name: String, typeParams: List[TypeDef], paramss: List[List[ValDef]],
                    returnTpt: TypeTree, rhs: Option[Term]) extends Definition {
    def flags: FlagSet = ???
    def privateWithin: Option[Type] = ???
    def protectedWithin: Option[Type] = ???
    def annots: List[Term] = ???
  }
  case class TypeDef(name: String, rhs: TypeTree | TypeBoundsTree) extends Definition {
    def flags: FlagSet = ???
    def privateWithin: Option[Type] = ???
    def protectedWithin: Option[Type] = ???
    def annots: List[Term] = ???
  }
  case class ClassDef(name: String, constructor: DefDef, parents: List[Term | TypeTree],
                      self: Option[ValDef], body: List[Statement]) extends Definition {
    def flags: FlagSet = ???
    def privateWithin: Option[Type] = ???
    def protectedWithin: Option[Type] = ???
    def annots: List[Term] = ???
  }
  case class PackageDef(name: String, override val owner: PackageDef) extends Definition {
    def members: List[Statement] = ???
  }

// ------ Terms ---------------------------------

  /** Trees denoting terms */
  enum Term extends Statement {
    def tpe: Type = ???
    case Ident(name: String, override val tpe: Type)
    case Select(prefix: Term, name: String, signature: Option[Signature])
    case Literal(value: Constant)
    case This(id: Option[Id])
    case New(tpt: TypeTree)
    case Throw(expr: Term)
    case NamedArg(name: String, arg: Term)
    case Apply(fn: Term, args: List[Term])
    case TypeApply(fn: Term, args: List[TypeTree])
    case Super(thiz: Term, mixin: Option[Id])
    case Typed(expr: Term, tpt: TypeTree)
    case Assign(lhs: Term, rhs: Term)
    case Block(stats: List[Statement], expr: Term)
    case Inlined(call: Option[Term], bindings: List[Definition], expr: Term)
    case Lambda(method: Term, tpt: Option[TypeTree])
    case If(cond: Term, thenPart: Term, elsePart: Term)
    case Match(scrutinee: Term, cases: List[CaseDef])
    case Try(body: Term, catches: List[CaseDef], finalizer: Option[Term])
    case Return(expr: Term)
    case Repeated(args: List[Term])
    case SelectOuter(from: Term, levels: Int, target: Type) // can be generated by inlining
    case While(cond: Term, body: Term)
  }

  /** Trees denoting types */
  enum TypeTree extends Positioned {
    def tpe: Type = ???
    case Synthetic()
    case Ident(name: String, override val tpe: Type)
    case TermSelect(prefix: Term, name: String)
    case TypeSelect(prefix: TypeTree, name: String)
    case Singleton(ref: Term)
    case Refined(underlying: TypeTree, refinements: List[Definition])
    case Applied(tycon: TypeTree, args: List[TypeTree | TypeBoundsTree])
    case Annotated(tpt: TypeTree, annotation: Term)
    case And(left: TypeTree, right: TypeTree)
    case Or(left: TypeTree, right: TypeTree)
    case ByName(tpt: TypeTree)
    case TypeLambda(tparams: List[TypeDef], body: Type | TypeBoundsTree)
    case Bind(name: String, bounds: TypeBoundsTree)
  }

  /** Trees denoting type bounds */
  case class TypeBoundsTree(loBound: TypeTree, hiBound: TypeTree) extends Tree {
    def tpe: Type.TypeBounds = ???
  }

  /** Trees denoting type inferred bounds */
  case class SyntheticBounds() extends Tree {
    def tpe: Type.TypeBounds = ???
  }

  /** Trees denoting patterns */
  enum Pattern extends Positioned {
    def tpe: Type = ???
    case Value(v: Term)
    case Bind(name: String, pat: Pattern)
    case Unapply(unapply: Term, implicits: List[Term], pats: List[Pattern])
    case Alternative(pats: List[Pattern])
    case TypeTest(tpt: TypeTree)
  }

  /** Tree denoting pattern match case */
  case class CaseDef(pat: Pattern, guard: Option[Term], rhs: Term) extends Tree

// ====== Types ======================================

  sealed trait Type

  object Type {
    private val PlaceHolder = ConstantType(Constant.Unit)

    case class ConstantType(value: Constant) extends Type
    case class SymRef(sym: Definition, qualifier: Type | NoPrefix = NoPrefix) extends Type
    case class TypeRef(name: String, qualifier: Type | NoPrefix = NoPrefix) extends Type // NoPrefix means: select from _root_
    case class TermRef(name: String, qualifier: Type | NoPrefix = NoPrefix) extends Type // NoPrefix means: select from _root_
    case class SuperType(thistp: Type, underlying: Type) extends Type
    case class Refinement(underlying: Type, name: String, tpe: Type | TypeBounds) extends Type
    case class AppliedType(tycon: Type, args: List[Type | TypeBounds]) extends Type
    case class AnnotatedType(underlying: Type, annotation: Term) extends Type
    case class AndType(left: Type, right: Type) extends Type
    case class OrType(left: Type, right: Type) extends Type
    case class ByNameType(underlying: Type) extends Type
    case class ParamRef(binder: LambdaType[?, ?], idx: Int) extends Type
    case class ThisType(tp: Type) extends Type
    case class RecursiveThis(binder: RecursiveType) extends Type

    case class RecursiveType private (private var _underlying: Type) extends Type {
      def underlying = _underlying
    }
    object RecursiveType {
      def apply(underlyingExp: RecursiveType => Type) = {
        val rt = new RecursiveType(PlaceHolder) {}
        rt._underlying = underlyingExp(rt)
        rt
      }
    }

    abstract class LambdaType[ParamInfo, This <: LambdaType[ParamInfo, This]]
    extends Type {
      val companion: LambdaTypeCompanion[ParamInfo, This]
      private[Type] var _pinfos: List[ParamInfo]
      private[Type] var _restpe: Type

      def paramNames: List[String]
      def paramInfos: List[ParamInfo] = _pinfos
      def resultType: Type = _restpe
    }

    abstract class LambdaTypeCompanion[ParamInfo, This <: LambdaType[ParamInfo, This]] {
      def apply(pnames: List[String], ptypes: List[ParamInfo], restpe: Type): This

      def apply(pnames: List[String], ptypesExp: This => List[ParamInfo], restpeExp: This => Type): This = {
        val lambda = apply(pnames, Nil, PlaceHolder)
        lambda._pinfos = ptypesExp(lambda)
        lambda._restpe = restpeExp(lambda)
        lambda
      }
    }

    case class MethodType(paramNames: List[String], private[Type] var _pinfos: List[Type], private[Type] var _restpe: Type)
    extends LambdaType[Type, MethodType] {
      override val companion = MethodType
      def isImplicit = (companion `eq` ImplicitMethodType) || (companion `eq` ErasedImplicitMethodType)
      def isErased = (companion `eq` ErasedMethodType) || (companion `eq` ErasedImplicitMethodType)
    }

    case class PolyType(paramNames: List[String], private[Type] var _pinfos: List[TypeBounds], private[Type] var _restpe: Type)
    extends LambdaType[TypeBounds, PolyType] {
      override val companion = PolyType
    }

    case class TypeLambda(paramNames: List[String], private[Type] var _pinfos: List[TypeBounds], private[Type] var _restpe: Type)
    extends LambdaType[TypeBounds, TypeLambda] {
      override val companion = TypeLambda
    }

    object TypeLambda extends LambdaTypeCompanion[TypeBounds, TypeLambda]
    object PolyType   extends LambdaTypeCompanion[TypeBounds, PolyType]
    object MethodType extends LambdaTypeCompanion[Type, MethodType]

    class SpecializedMethodTypeCompanion extends LambdaTypeCompanion[Type, MethodType] { self =>
      def apply(pnames: List[String], ptypes: List[Type], restpe: Type): MethodType =
        new MethodType(pnames, ptypes, restpe) { override val companion = self }
    }
    object ImplicitMethodType       extends SpecializedMethodTypeCompanion
    object ErasedMethodType         extends SpecializedMethodTypeCompanion
    object ErasedImplicitMethodType extends SpecializedMethodTypeCompanion

    case class TypeBounds(loBound: Type, hiBound: Type)

    case class NoPrefix()
    object NoPrefix extends NoPrefix
  }

// ====== Modifiers ==================================

  enum Modifier {
    case Flags(flags: FlagSet)
    case QualifiedPrivate(boundary: Type)
    case QualifiedProtected(boundary: Type)
    case Annotation(tree: Term)
  }

  trait FlagSet {
    def isProtected: Boolean
    def isAbstract: Boolean
    def isFinal: Boolean
    def isSealed: Boolean
    def isCase: Boolean
    def isImplicit: Boolean
    def isErased: Boolean
    def isLazy: Boolean
    def isOverride: Boolean
    def isInline: Boolean
    def isMacro: Boolean                 // inline method containing toplevel splices
    def isStatic: Boolean                // mapped to static Java member
    def isObject: Boolean                // an object or its class (used for a ValDef or a ClassDef extends Modifier respectively)
    def isTrait: Boolean                 // a trait (used for a ClassDef)
    def isLocal: Boolean                 // used in conjunction with Private/private[Type] to mean private[this] extends Modifier protected[this]
    def isSynthetic: Boolean             // generated by Scala compiler
    def isArtifact: Boolean              // to be tagged Java Synthetic
    def isMutable: Boolean               // when used on a ValDef: a var
    def isLabel: Boolean                 // method generated as a label
    def isFieldAccessor: Boolean         // a getter or setter
    def isCaseAccessor: Boolean          // getter for class parameter
    def isCovariant: Boolean             // type parameter marked “+”
    def isContravariant: Boolean         // type parameter marked “-”
    def isScala2X: Boolean               // Imported from Scala2.x
    def hasDefault: Boolean              // Parameter with default
    def isStable: Boolean                // Method that is assumed to be stable
  }

  case class Signature(paramSigs: List[String], resultSig: String)

// ====== Positions ==================================

  case class Position(firstOffset: Int, lastOffset: Int, sourceFile: String) {
    def startLine: Int = ???
    def startColumn: Int = ???
    def endLine: Int = ???
    def endColumn: Int = ???
  }

  trait Positioned {
    def pos: Position = ???
  }

// ====== Constants ==================================

  enum Constant(val value: Any) {
    case Unit                        extends Constant(())
    case Null                        extends Constant(null)
    case Boolean(v: scala.Boolean)   extends Constant(v)
    case Byte(v: scala.Byte)         extends Constant(v)
    case Short(v: scala.Short)       extends Constant(v)
    case Char(v: scala.Char)         extends Constant(v)
    case Int(v: scala.Int)           extends Constant(v)
    case Long(v: scala.Long)         extends Constant(v)
    case Float(v: scala.Float)       extends Constant(v)
    case Double(v: scala.Double)     extends Constant(v)
    case String(v: java.lang.String) extends Constant(v)
    case Class(v: Type)              extends Constant(v)
    case Enum(v: Type)               extends Constant(v)
  }
}

// --- A sample extractor ------------------

// The abstract class, that's what we export to macro users
abstract class Tasty {

  type Type
  trait TypeAPI {
    // exported type fields
  }
  implicit def TypeDeco(x: Type): TypeAPI

  type Symbol
  trait SymbolAPI {
    // exported symbol fields
  }
  implicit def SymbolDeco(s: Symbol): SymbolAPI

  type Context
  trait ContextAPI {
    val owner: Symbol
    // more exported fields
  }
  implicit def ContextDeco(x: Context): ContextAPI

  type Position
  trait PositionAPI {
    val start: Int
    val end: Int
    // more fields
  }
  implicit def PositionDeco(p: Position): PositionAPI

  trait TypedPositioned {
    val pos: Position
    val tpe: Type
  }

  type Pattern
  implicit def PatternDeco(p: Pattern): TypedPositioned

  type Term
  implicit def TermDeco(t: Term): TypedPositioned

  type CaseDef
  implicit def CaseDefDeco(c: CaseDef): TypedPositioned

  val CaseDef: CaseDefExtractor
  abstract class CaseDefExtractor {
    def apply(pat: Pattern, guard: Term, rhs: Term)(implicit ctx: Context): CaseDef
    def unapply(x: CaseDef): Some[(Pattern, Term, Term)]
  }
  // and analogously for all other concrete trees, patterns, types, etc
}

// The concrete implementation - hidden from users.
object ReflectionImpl extends Tasty {
  import definitions.*
  import dotty.tools.dotc.*
  import ast.tpd
  import core.{Types, Symbols, Contexts}
  import util.Spans

  type Type = Types.Type
  implicit class TypeDeco(x: Type) extends TypeAPI {}

  type Symbol = Symbols.Symbol
  implicit class SymbolDeco(s: Symbol) extends SymbolAPI {}

  type Context = Contexts.Context
  implicit class ContextDeco(c: Context) extends ContextAPI {
    val owner = c.owner
  }

  type Position = Spans.Span
  implicit class PositionDeco(p: Position) extends PositionAPI {
    val start = p.start
    val end = p.end
  }

  type Pattern = tpd.Tree
  implicit class PatternDeco(p: Pattern) extends TypedPositioned {
    val pos = p.span
    val tpe = p.tpe
  }

  type Term = tpd.Tree
  implicit class TermDeco(t: Term) extends TypedPositioned {
    val pos = t.span
    val tpe = t.tpe
  }

  type CaseDef = tpd.CaseDef
  implicit class CaseDefDeco(c: CaseDef) extends TypedPositioned {
    val pos = c.span
    val tpe = c.tpe
  }

  object CaseDef extends CaseDefExtractor {
    def apply(pat: Pattern, guard: Term, rhs: Term)(implicit ctx: Context): CaseDef =
      tpd.CaseDef(pat, guard, rhs)
    def unapply(x: CaseDef): Some[(Pattern, Term, Term)] =
      Some((x.pat, x.guard, x.body))
  }
}

/* Dependencies:

    the reflect library (which is probably part of stdlib) contains a

      val tasty: Tasty

    this val is implemented reflectively, loading ReflectionImpl on demand. ReflectionImpl in turn
    depends on `tools.dotc`.

*/


/* If the dotty implementations all inherit the ...API traits,
   and the API traits inherit thmeselves from ProductN, we can
   also do the following, faster implementation.
   This still does full information hiding, but should be almost
   as fast as native access.

object ReflectionImpl extends TastyAST {
  import definitions.*
  import dotty.tools.dotc.*
  import ast.tpd
  import core.{Types, Symbols, Contexts}
  import util.{Positions}

  type Type = Types.Type
  implicit def TypeDeco(x: Type) = x

  type Symbol = Symbols.Symbol
  implicit def SymbolDeco(s: Symbol) = s

  type Context = Contexts.Context
  implicit def ContextDeco(c: Context) = c

  type Position = Positions.Position
  implicit def PositionDeco(p: Position) = p

  type Pattern = tpd.Tree
  implicit def PatternDeco(p: Pattern) = p

  type Term = tpd.Tree
  implicit def TermDeco(t: Term) = t

  type CaseDef = tpd.CaseDef
  implicit def CaseDefDeco(c: CaseDef) = c

  object CaseDef extends CaseDefExtractor {
    def apply(pat: Pattern, guard: Term, rhs: Term)(implicit ctx: Context): CaseDef =
      tpd.CaseDef(pat, guard, rhs)
    def unapply(x: CaseDef): CaseDefAPI = x
  }
}

This approach is fast because all accesses work without boxing. But there are also downsides:

1. The added reflect supertypes for the dotty types might have a negative performance
   impact for normal compilation.

2. There would be an added dependency from compiler to reflect library, which
   complicates things.
*/
