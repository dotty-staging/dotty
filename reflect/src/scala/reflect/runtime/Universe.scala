package scala.reflect.runtime

import scala.quoted.*
import scala.reflect.api.JavaUniverse

object universe extends JavaUniverse:
  type Mirror = RuntimeMirror

  inline given materializeTypeTag[T]: TypeTag[T] = ${ CompatTagMacros.typeTagImpl[T] }

  override lazy val rootMirror: Mirror = runtimeMirror(getClass.getClassLoader)

  def currentMirror: Mirror =
    val cl = Thread.currentThread.getContextClassLoader
    runtimeMirror(if cl == null then getClass.getClassLoader else cl)

  override def runtimeMirror(classLoader: ClassLoader): Mirror =
    RuntimeMirror(classLoader)

  override protected def typeFromString(repr: String): Type =
    RuntimeType(repr, runtimeClassName(repr))

  private def runtimeClassName(repr: String): Option[String] =
    repr match
      case "scala.Boolean" => Some("boolean")
      case "scala.Byte" => Some("byte")
      case "scala.Short" => Some("short")
      case "scala.Char" => Some("char")
      case "scala.Int" => Some("int")
      case "scala.Long" => Some("long")
      case "scala.Float" => Some("float")
      case "scala.Double" => Some("double")
      case "scala.Unit" => Some("void")
      case name if name.startsWith("scala.Predef.") => Some(name.stripPrefix("scala.Predef."))
      case name if name.nonEmpty && !name.exists(ch => ch == '[' || ch == ' ' || ch == '&' || ch == '|') =>
        Some(name)
      case _ => None

  private def loadClass(name: String, classLoader: ClassLoader): Class[?] =
    name match
      case "boolean" => java.lang.Boolean.TYPE
      case "byte" => java.lang.Byte.TYPE
      case "short" => java.lang.Short.TYPE
      case "char" => java.lang.Character.TYPE
      case "int" => java.lang.Integer.TYPE
      case "long" => java.lang.Long.TYPE
      case "float" => java.lang.Float.TYPE
      case "double" => java.lang.Double.TYPE
      case "void" => java.lang.Void.TYPE
      case "String" => classOf[String]
      case "Object" => classOf[Object]
      case other => Class.forName(other, false, classLoader)

  private final case class RuntimeType(show: String, className: Option[String]) extends Type:
    override def typeSymbol: Symbol =
      className match
        case Some(name) => RuntimeClassSymbol(name, this)
        case None => NoSymbol

    override def member(name: Name): Symbol =
      className match
        case Some(clsName) =>
          val cls = loadClass(clsName, currentMirror.classLoader)
          cls.getMethods.find(_.getName == name.decodedName)
            .map(method => RuntimeMethodSymbol(TermName(method.getName), method.toString, RuntimeType(method.getReturnType.getName, Some(method.getReturnType.getName))))
            .orElse(cls.getDeclaredFields.find(_.getName == name.decodedName).map(field =>
              RuntimeTermSymbol(TermName(field.getName), field.toString, RuntimeType(field.getType.getName, Some(field.getType.getName)))
            ))
            .getOrElse(NoSymbol)
        case None => NoSymbol

  private final case class RuntimeClassSymbol(fullName: String, override val info: Type) extends ClassSymbol:
    val name: Name = TypeName(fullName.split('.').lastOption.getOrElse(fullName))

  private final case class RuntimeMethodSymbol(name: Name, fullName: String, override val info: Type) extends MethodSymbol

  private final case class RuntimeTermSymbol(name: Name, fullName: String, override val info: Type) extends TermSymbol

  private final case class RuntimePackageSymbol(fullName: String) extends Symbol:
    val name: Name = TermName(fullName.split('.').lastOption.getOrElse(fullName))

  final case class RuntimeMirror(classLoader: ClassLoader) extends JavaMirror:
    val universe: scala.reflect.runtime.universe.type = scala.reflect.runtime.universe

    override def staticClass(fullName: String): ClassSymbol =
      val cls = loadClass(fullName, classLoader)
      classSymbol(cls)

    override def staticModule(fullName: String): Symbol =
      val moduleName = if fullName.endsWith("$") then fullName else fullName + "$"
      RuntimeClassSymbol(moduleName, RuntimeType(moduleName, Some(moduleName)))

    override def staticPackage(fullName: String): Symbol =
      RuntimePackageSymbol(fullName)

    override def runtimeClass(tpe: Type): Class[?] =
      tpe match
        case RuntimeType(_, Some(className)) => loadClass(className, classLoader)
        case _ => throw new ClassNotFoundException(tpe.show)

    override def classSymbol(cls: Class[?]): ClassSymbol =
      RuntimeClassSymbol(cls.getName, RuntimeType(cls.getName, Some(cls.getName)))

    override def reflect(instance: Any): InstanceMirror =
      RuntimeInstanceMirror(this, instance)

    override def reflectClass(sym: ClassSymbol): ClassMirror =
      RuntimeClassMirror(this, sym)

  private final case class RuntimeInstanceMirror(mirror: RuntimeMirror, instance: Any) extends InstanceMirror:
    override def symbol: Symbol = mirror.classSymbol(instance.getClass)

    override def reflectMethod(sym: MethodSymbol): MethodMirror =
      RuntimeMethodMirror(mirror, Some(instance), sym)

    override def reflectField(sym: TermSymbol): FieldMirror =
      RuntimeFieldMirror(mirror, instance, sym)

  private final case class RuntimeClassMirror(mirror: RuntimeMirror, symbol: ClassSymbol) extends ClassMirror:
    override def reflectConstructor(sym: MethodSymbol): MethodMirror =
      RuntimeMethodMirror(mirror, None, sym)

  private final case class RuntimeMethodMirror(mirror: RuntimeMirror, instance: Option[Any], symbol: MethodSymbol) extends MethodMirror:
    override def apply(args: Any*): Any =
      instance match
        case Some(value) =>
          val method = value.getClass.getMethods.find(_.getName == symbol.name.decodedName)
            .getOrElse(throw new NoSuchMethodException(symbol.name.decodedName))
          method.invoke(value, args.map(_.asInstanceOf[AnyRef])*)
        case None =>
          val cls = mirror.runtimeClass(symbol.owner.info)
          val ctor = cls.getConstructors.find(_.getParameterCount == args.size)
            .getOrElse(throw new NoSuchMethodException(symbol.name.decodedName))
          ctor.newInstance(args.map(_.asInstanceOf[AnyRef])*)

  private final case class RuntimeFieldMirror(mirror: RuntimeMirror, instance: Any, symbol: TermSymbol) extends FieldMirror:
    private def field =
      val f = instance.getClass.getDeclaredField(symbol.name.decodedName)
      scala.reflect.ensureAccessible(f)

    override def get: Any = field.get(instance)
    override def set(value: Any): Unit = field.set(instance, value.asInstanceOf[AnyRef])
end universe
