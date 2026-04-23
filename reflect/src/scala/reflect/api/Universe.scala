package scala.reflect.api

/** Minimal Scala 2-shaped reflection API facade for the Scala 3 reflect
 *  compatibility module.
 *
 *  This deliberately models the stable source surface first. Implementations may
 *  be backed by TASTy, Scala 3 compiler symbols, or JVM reflection depending on
 *  the entry point that created the value.
 */
abstract class Universe { self =>
  type Mirror <: scala.reflect.api.Mirror[self.type]

  def rootMirror: Mirror

  sealed trait Name extends Serializable:
    def decodedName: String
    final override def toString: String = decodedName

  final case class TermName(decodedName: String) extends Name
  final case class TypeName(decodedName: String) extends Name

  object nme:
    val CONSTRUCTOR: TermName = TermName("<init>")

  object tpnme:
    val EMPTY: TypeName = TypeName("")

  final case class FlagSet(private val bits: Long) extends AnyVal:
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
    val name: Name = TermName("<none>")
    val fullName: String = "<none>"

  trait Type extends Serializable:
    def show: String
    def typeSymbol: Symbol = NoSymbol
    def =:=(that: Type): Boolean = show == that.show
    def <:<(that: Type): Boolean = (this =:= that) || that.show == "scala.Any"
    def dealias: Type = this
    def widen: Type = this
    def member(name: Name): Symbol = NoSymbol
    def decl(name: Name): Symbol = member(name)
    final override def toString: String = show

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

  trait WeakTypeTag[T] extends Equals with Serializable:
    def mirror: Mirror
    def tpe: Type
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

  def typeOf[T](using tag: TypeTag[T]): Type = tag.tpe
  def weakTypeOf[T](using tag: WeakTypeTag[T]): Type = tag.tpe

  def show(tree: Tree): String = tree.show
  def showRaw(value: Any): String = String.valueOf(value)

  def reify[T](expr: T): Expr[T] =
    unsupported("reify requires a Scala 3 macro rewrite; legacy Scala 2 reification is not implemented")

  protected def typeFromString(repr: String): Type

  protected final case class SimpleWeakTypeTag[T](mirror: Mirror, tpe: Type) extends WeakTypeTag[T]
  protected final case class SimpleTypeTag[T](mirror: Mirror, tpe: Type) extends TypeTag[T]

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

  def runtimeMirror(classLoader: ClassLoader): Mirror
}

trait Mirror[U <: Universe & Singleton] extends Serializable:
  def universe: U
  def classLoader: ClassLoader
  def staticClass(fullName: String): universe.ClassSymbol
  def staticModule(fullName: String): universe.Symbol
  def staticPackage(fullName: String): universe.Symbol
