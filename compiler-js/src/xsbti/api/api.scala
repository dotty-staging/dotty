package xsbti.api

// Stub xsbti.api types for Scala.js
// These are minimal implementations matching the zinc compiler-interface API

// Base types
trait Type
trait Definition
trait ClassDefinition extends Definition
trait Access
trait Qualifier
trait PathComponent

// Simple marker types
class EmptyType extends Type
object EmptyType:
  def create(): EmptyType = new EmptyType
  def of(): EmptyType = new EmptyType

// Qualifier types
class ThisQualifier extends Qualifier
object ThisQualifier:
  def create(): ThisQualifier = new ThisQualifier

class Unqualified extends Qualifier
object Unqualified:
  def create(): Unqualified = new Unqualified

class IdQualifier(val value: String) extends Qualifier
object IdQualifier:
  def of(value: String): IdQualifier = new IdQualifier(value)

// Access types
class Public extends Access
object Public:
  def create(): Public = new Public

class Private(val qualifier: Qualifier) extends Access
object Private:
  def create(qualifier: Qualifier): Private = new Private(qualifier)
  def of(qualifier: Qualifier): Private = new Private(qualifier)

class Protected(val qualifier: Qualifier) extends Access
object Protected:
  def create(qualifier: Qualifier): Protected = new Protected(qualifier)
  def of(qualifier: Qualifier): Protected = new Protected(qualifier)

// Modifiers
class Modifiers(
  val isAbstract: Boolean,
  val isOverride: Boolean,
  val isFinal: Boolean,
  val isSealed: Boolean,
  val isImplicit: Boolean,
  val isLazy: Boolean,
  val isMacro: Boolean,
  val isSuperAccessor: Boolean
)

// Type classes
class Annotated(val baseType: Type, val annotations: Array[Annotation]) extends Type
object Annotated:
  def of(baseType: Type, annotations: Array[Annotation]): Annotated =
    new Annotated(baseType, annotations)

class Constant(val baseType: Type, val value: String) extends Type
object Constant:
  def of(baseType: Type, value: String): Constant = new Constant(baseType, value)

class Projection(val prefix: Type, val id: String) extends Type
object Projection:
  def of(prefix: Type, id: String): Projection = new Projection(prefix, id)

class ParameterRef(val id: String) extends Type
object ParameterRef:
  def of(id: String): ParameterRef = new ParameterRef(id)

class Existential(val baseType: Type, val clause: Array[TypeParameter]) extends Type
object Existential:
  def of(baseType: Type, clause: Array[TypeParameter]): Existential =
    new Existential(baseType, clause)

class Parameterized(val baseType: Type, val typeArguments: Array[Type]) extends Type
object Parameterized:
  def of(baseType: Type, typeArguments: Array[Type]): Parameterized =
    new Parameterized(baseType, typeArguments)

class Polymorphic(val baseType: Type, val parameters: Array[TypeParameter]) extends Type
object Polymorphic:
  def of(baseType: Type, parameters: Array[TypeParameter]): Polymorphic =
    new Polymorphic(baseType, parameters)

class Singleton(val path: Path) extends Type
object Singleton:
  def of(path: Path): Singleton = new Singleton(path)

class Structure(
  val parents: Lazy[Array[Type]],
  val declared: Lazy[Array[ClassDefinition]],
  val inherited: Lazy[Array[ClassDefinition]]
) extends Type
object Structure:
  def of(parents: Lazy[Array[Type]], declared: Lazy[Array[ClassDefinition]], inherited: Lazy[Array[ClassDefinition]]): Structure =
    new Structure(parents, declared, inherited)

// Path types
class Path(val components: Array[PathComponent]) extends Type
object Path:
  def of(components: Array[PathComponent]): Path = new Path(components)

class Id(val id: String) extends PathComponent
object Id:
  def of(id: String): Id = new Id(id)

class This extends PathComponent
object This:
  def create(): This = new This

class Super(val qualifier: Path) extends PathComponent
object Super:
  def of(qualifier: Path): Super = new Super(qualifier)

// Annotation
class Annotation(val base: Type, val arguments: Array[AnnotationArgument])
object Annotation:
  def of(base: Type, arguments: Array[AnnotationArgument]): Annotation =
    new Annotation(base, arguments)

class AnnotationArgument(val name: String, val value: String)
object AnnotationArgument:
  def of(name: String, value: String): AnnotationArgument =
    new AnnotationArgument(name, value)

// Variance
enum Variance:
  case Invariant, Covariant, Contravariant

// Parameter modifier
enum ParameterModifier:
  case Plain, ByName, Repeated

// Type parameter
class TypeParameter(
  val id: String,
  val annotations: Array[Annotation],
  val typeParameters: Array[TypeParameter],
  val variance: Variance,
  val lowerBound: Type,
  val upperBound: Type
)
object TypeParameter:
  def of(id: String, annotations: Array[Annotation], typeParameters: Array[TypeParameter],
         variance: Variance, lowerBound: Type, upperBound: Type): TypeParameter =
    new TypeParameter(id, annotations, typeParameters, variance, lowerBound, upperBound)

// Method parameter
class MethodParameter(val name: String, val tpe: Type, val hasDefault: Boolean, val modifier: ParameterModifier)
object MethodParameter:
  def of(name: String, tpe: Type, hasDefault: Boolean, modifier: ParameterModifier): MethodParameter =
    new MethodParameter(name, tpe, hasDefault, modifier)

class ParameterList(val parameters: Array[MethodParameter], val isImplicit: Boolean)
object ParameterList:
  def of(parameters: Array[MethodParameter], isImplicit: Boolean): ParameterList =
    new ParameterList(parameters, isImplicit)

// Definition type
enum DefinitionType:
  case Trait, ClassDef, Module, PackageModule

// Definition classes
class Val(val name: String, val access: Access, val modifiers: Modifiers,
          val annotations: Array[Annotation], val tpe: Type) extends ClassDefinition
object Val:
  def of(name: String, access: Access, modifiers: Modifiers,
         annotations: Array[Annotation], tpe: Type): Val =
    new Val(name, access, modifiers, annotations, tpe)

class Var(val name: String, val access: Access, val modifiers: Modifiers,
          val annotations: Array[Annotation], val tpe: Type) extends ClassDefinition
object Var:
  def of(name: String, access: Access, modifiers: Modifiers,
         annotations: Array[Annotation], tpe: Type): Var =
    new Var(name, access, modifiers, annotations, tpe)

class Def(val name: String, val access: Access, val modifiers: Modifiers,
          val annotations: Array[Annotation], val typeParameters: Array[TypeParameter],
          val valueParameters: Array[ParameterList], val returnType: Type) extends ClassDefinition
object Def:
  def of(name: String, access: Access, modifiers: Modifiers,
         annotations: Array[Annotation], typeParameters: Array[TypeParameter],
         valueParameters: Array[ParameterList], returnType: Type): Def =
    new Def(name, access, modifiers, annotations, typeParameters, valueParameters, returnType)

class TypeAlias(val name: String, val access: Access, val modifiers: Modifiers,
                val annotations: Array[Annotation], val typeParameters: Array[TypeParameter],
                val tpe: Type) extends ClassDefinition with TypeMember
object TypeAlias:
  def of(name: String, access: Access, modifiers: Modifiers,
         annotations: Array[Annotation], typeParameters: Array[TypeParameter],
         tpe: Type): TypeAlias =
    new TypeAlias(name, access, modifiers, annotations, typeParameters, tpe)

class TypeDeclaration(val name: String, val access: Access, val modifiers: Modifiers,
                      val annotations: Array[Annotation], val typeParameters: Array[TypeParameter],
                      val lowerBound: Type, val upperBound: Type) extends ClassDefinition with TypeMember
object TypeDeclaration:
  def of(name: String, access: Access, modifiers: Modifiers,
         annotations: Array[Annotation], typeParameters: Array[TypeParameter],
         lowerBound: Type, upperBound: Type): TypeDeclaration =
    new TypeDeclaration(name, access, modifiers, annotations, typeParameters, lowerBound, upperBound)

trait TypeMember extends ClassDefinition

// ClassLike
class ClassLike(
  val name: String,
  val access: Access,
  val modifiers: Modifiers,
  val annotations: Array[Annotation],
  val definitionType: DefinitionType,
  val selfType: Lazy[Type],
  val structure: Lazy[Structure],
  val savedAnnotations: Array[String],
  val childrenOfSealedClass: Array[Type],
  val topLevel: Boolean,
  val typeParameters: Array[TypeParameter]
) extends ClassDefinition
object ClassLike:
  def of(name: String, access: Access, modifiers: Modifiers,
         annotations: Array[Annotation], definitionType: DefinitionType,
         selfType: Lazy[Type], structure: Lazy[Structure],
         savedAnnotations: Array[String], childrenOfSealedClass: Array[Type],
         topLevel: Boolean, typeParameters: Array[TypeParameter]): ClassLike =
    new ClassLike(name, access, modifiers, annotations, definitionType,
                  selfType, structure, savedAnnotations, childrenOfSealedClass,
                  topLevel, typeParameters)

class ClassLikeDef(val name: String, val access: Access, val modifiers: Modifiers,
                   val annotations: Array[Annotation], val typeParameters: Array[TypeParameter],
                   val definitionType: DefinitionType) extends ClassDefinition
object ClassLikeDef:
  def of(name: String, access: Access, modifiers: Modifiers,
         annotations: Array[Annotation], typeParameters: Array[TypeParameter],
         definitionType: DefinitionType): ClassLikeDef =
    new ClassLikeDef(name, access, modifiers, annotations, typeParameters, definitionType)

// Lazy wrapper
trait Lazy[T]:
  def get(): T

object SafeLazy:
  def strict[T](value: T): Lazy[T] = new Lazy[T]:
    def get(): T = value
