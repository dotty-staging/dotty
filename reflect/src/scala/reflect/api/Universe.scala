package scala.reflect.api

trait Types { self: Universe =>
  abstract class TypeApi extends Serializable:
    def show: String
    def typeSymbol: Symbol = NoSymbol
    def =:=(that: TypeApi): Boolean = show == that.show
    def <:<(that: TypeApi): Boolean = (this =:= that) || that.show == "scala.Any"
    def dealias: TypeApi = this
    def widen: TypeApi = this
    def member(name: Name): Symbol = NoSymbol
    def decl(name: Name): Symbol = member(name)
    final override def toString: String = show
}

trait Names { self: Universe =>
  abstract class NameApi extends Serializable:
    def isTermName: Boolean
    def isTypeName: Boolean
    def toTermName: TermNameApi
    def toTypeName: TypeNameApi
    def decoded: String
    def encoded: String = decoded
    def decodedName: NameApi = this
    def encodedName: NameApi = this

  trait TermNameApi
  trait TypeNameApi

  abstract class TermNameExtractor:
    def apply(name: String): TermNameApi
    def unapply(name: TermNameApi): Option[String]

  abstract class TypeNameExtractor:
    def apply(name: String): TypeNameApi
    def unapply(name: TypeNameApi): Option[String]

  def stringToTermName(name: String): TermNameApi
  def stringToTypeName(name: String): TypeNameApi
  def newTermName(name: String): TermNameApi
  def newTypeName(name: String): TypeNameApi
  def TermName: TermNameExtractor
  def TypeName: TypeNameExtractor
}

trait TypeTags { self: Universe =>
  trait WeakTypeTag[T] extends Equals with Serializable:
    def mirror: Mirror
    def tpe: TypeApi
    def in[U <: Universe & Singleton](otherMirror: scala.reflect.api.Mirror[U]): otherMirror.universe.WeakTypeTag[T] =
      otherMirror.universe.WeakTypeTag.fromString(otherMirror.asInstanceOf[otherMirror.universe.Mirror], tpe.show)
    override def canEqual(that: Any): Boolean = that.isInstanceOf[WeakTypeTag[?]]
    override def equals(that: Any): Boolean = that match
      case tag: WeakTypeTag[?] => mirror == tag.mirror && tpe == tag.tpe
      case _ => false
    override def hashCode(): Int = 31 * mirror.hashCode + tpe.hashCode
    override def toString: String = s"WeakTypeTag[$tpe]"

  object WeakTypeTag:
    def apply[T](mirror: Mirror, tpe: Type): WeakTypeTag[T] =
      SimpleWeakTypeTag(mirror, tpe)
    def fromString[T](mirror: Mirror, repr: String): WeakTypeTag[T] =
      apply(mirror, typeFromString(repr))

  trait TypeTag[T] extends WeakTypeTag[T]:
    override def in[U <: Universe & Singleton](otherMirror: scala.reflect.api.Mirror[U]): otherMirror.universe.TypeTag[T] =
      otherMirror.universe.TypeTag.fromString(otherMirror.asInstanceOf[otherMirror.universe.Mirror], tpe.show)
    override def toString: String = s"TypeTag[$tpe]"

  object TypeTag:
    def apply[T](mirror: Mirror, tpe: Type): TypeTag[T] =
      SimpleTypeTag(mirror, tpe)
    def fromString[T](mirror: Mirror, repr: String): TypeTag[T] =
      apply(mirror, typeFromString(repr))

  protected final case class SimpleWeakTypeTag[T](mirror: Mirror, tpe: Type) extends WeakTypeTag[T]
  protected final case class SimpleTypeTag[T](mirror: Mirror, tpe: Type) extends TypeTag[T]

  protected def typeFromString(repr: String): Type
}

abstract class TypeCreator extends Serializable:
  def apply[U <: Universe & Singleton](mirror: scala.reflect.api.Mirror[U]): scala.reflect.api.Types#TypeApi

/** Minimal Scala 2-shaped reflection API facade for the Scala 3 reflect
 *  compatibility module.
 *
 *  This deliberately models the stable source surface first. Implementations may
 *  be backed by TASTy, Scala 3 compiler symbols, or JVM reflection depending on
 *  the entry point that created the value.
 */
abstract class Universe extends Names with Types with TypeTags { self =>
  type Mirror <: scala.reflect.api.Mirror[self.type]

  def rootMirror: Mirror

  sealed trait Name extends NameApi:
    final override def decodedName: Name = this
    final override def encodedName: Name = this
    final override def toString: String = decoded

  final case class TermName(decoded: String) extends Name with TermNameApi:
    override def isTermName: Boolean = true
    override def isTypeName: Boolean = false
    override def toTermName: TermName = this
    override def toTypeName: TypeName = new TypeName(decoded)

  object TermName extends TermNameExtractor:
    override def unapply(name: TermNameApi): Option[String] =
      Some(name.asInstanceOf[Name].decoded)

  final case class TypeName(decoded: String) extends Name with TypeNameApi:
    override def isTermName: Boolean = false
    override def isTypeName: Boolean = true
    override def toTermName: TermName = new TermName(decoded)
    override def toTypeName: TypeName = this

  object TypeName extends TypeNameExtractor:
    override def unapply(name: TypeNameApi): Option[String] =
      Some(name.asInstanceOf[Name].decoded)

  override def stringToTermName(name: String): TermName = newTermName(name)
  override def stringToTypeName(name: String): TypeName = newTypeName(name)
  override def newTermName(name: String): TermName = new TermName(name)
  override def newTypeName(name: String): TypeName = new TypeName(name)

  object nme:
    val CONSTRUCTOR: TermName = new TermName("<init>")

  object tpnme:
    val EMPTY: TypeName = new TypeName("")

  final case class FlagSet(private val bits: Long) extends Serializable:
    def |(that: FlagSet): FlagSet = FlagSet(bits | that.bits)
    def &(that: FlagSet): FlagSet = FlagSet(bits & that.bits)
    def isEmpty: Boolean = bits == 0L
    override def toString: String = if isEmpty then "<no flags>" else bits.toString

  val NoFlags: FlagSet = FlagSet(0L)
  val PRIVATE: FlagSet = FlagSet(1L << 0)
  val PROTECTED: FlagSet = FlagSet(1L << 1)
  val FINAL: FlagSet = FlagSet(1L << 2)
  val ABSTRACT: FlagSet = FlagSet(1L << 3)
  val MODULE: FlagSet = FlagSet(1L << 4)
  val PARAM: FlagSet = FlagSet(1L << 5)
  val CASE: FlagSet = FlagSet(1L << 6)

  trait Symbol extends Serializable:
    def name: Name
    def fullName: String
    def owner: Symbol = NoSymbol
    def info: Type = NoType
    def flags: FlagSet = NoFlags
    def isClass: Boolean = false
    def isModule: Boolean = false
    def isMethod: Boolean = false
    def isTerm: Boolean = false
    def asClass: ClassSymbol =
      throw new UnsupportedOperationException(s"$this is not a class symbol")
    def asMethod: MethodSymbol =
      throw new UnsupportedOperationException(s"$this is not a method symbol")
    def asTerm: TermSymbol =
      throw new UnsupportedOperationException(s"$this is not a term symbol")
    final override def toString: String = fullName

  trait ClassSymbol extends Symbol:
    override def isClass: Boolean = true
    override def asClass: ClassSymbol = this
    def toType: Type = info

  trait MethodSymbol extends Symbol:
    override def isMethod: Boolean = true
    override def asMethod: MethodSymbol = this

  trait TermSymbol extends Symbol:
    override def isTerm: Boolean = true
    override def asTerm: TermSymbol = this

  object NoSymbol extends Symbol:
    val name: Name = new TermName("<none>")
    val fullName: String = "<none>"

  abstract class Type extends TypeApi:
    override def typeSymbol: Symbol = NoSymbol
    override def =:=(that: TypeApi): Boolean = this.show == that.show
    override def <:<(that: TypeApi): Boolean = (this =:= that) || that.show == "scala.Any"
    override def dealias: Type = this
    override def widen: Type = this
    override def member(name: Name): Symbol = NoSymbol
    override def decl(name: Name): Symbol = member(name)

  object NoType extends Type:
    val show: String = "<notype>"

  trait Tree extends Serializable:
    def tpe: Type = NoType
    def symbol: Symbol = NoSymbol
    def show: String = toString

  final case class Literal(value: Constant) extends Tree:
    override def show: String = value.toString

  final case class Constant(value: Any) extends Serializable:
    override def toString: String = String.valueOf(value)

  trait Position extends Serializable:
    def source: String
    def line: Int
    def column: Int
    override def toString: String =
      if line < 0 then "<no position>" else s"$source:$line:$column"

  object NoPosition extends Position:
    val source: String = "<no source>"
    val line: Int = -1
    val column: Int = -1

  trait Expr[+T] extends Serializable:
    def tree: Tree
    def staticType: Type = tree.tpe

  object Expr:
    def apply[T](tree: Tree): Expr[T] = SimpleExpr(tree)

  private final case class SimpleExpr[+T](tree: Tree) extends Expr[T]

  def typeOf[T](using tag: TypeTag[T]): Type = tag.tpe.asInstanceOf[Type]
  def weakTypeOf[T](using tag: WeakTypeTag[T]): Type = tag.tpe.asInstanceOf[Type]

  def show(tree: Tree): String = tree.show
  def showRaw(value: Any): String = String.valueOf(value)

  def reify[T](expr: T): Expr[T] =
    unsupported("reify requires a Scala 3 macro rewrite; legacy Scala 2 reification is not implemented")

  protected def unsupported(message: String): Nothing =
    throw new UnsupportedOperationException(message)
}

trait JavaUniverse extends Universe { self =>
  type Mirror <: JavaMirror

  trait JavaMirror extends scala.reflect.api.Mirror[self.type]:
    def classLoader: ClassLoader
    def runtimeClass(tpe: Type): Class[?]
    def classSymbol(cls: Class[?]): ClassSymbol
    def reflect(instance: Any): InstanceMirror
    def reflectClass(sym: ClassSymbol): ClassMirror

  trait InstanceMirror:
    def instance: Any
    def symbol: Symbol
    def reflectMethod(sym: MethodSymbol): MethodMirror
    def reflectField(sym: TermSymbol): FieldMirror

  trait ClassMirror:
    def symbol: ClassSymbol
    def reflectConstructor(sym: MethodSymbol): MethodMirror

  trait MethodMirror:
    def symbol: MethodSymbol
    def apply(args: Any*): Any

  trait FieldMirror:
    def symbol: TermSymbol
    def get: Any
    def set(value: Any): Unit

  def currentMirror: Mirror
  def runtimeMirror(classLoader: ClassLoader): Mirror
}

trait Mirror[U <: Universe & Singleton] extends Serializable:
  val universe: U
  def classLoader: ClassLoader
  def staticClass(fullName: String): universe.ClassSymbol
  def staticModule(fullName: String): universe.Symbol
  def staticPackage(fullName: String): universe.Symbol
