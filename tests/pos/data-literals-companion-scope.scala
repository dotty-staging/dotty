import scala.language.experimental.collectionLiterals
import scala.language.experimental.recordLiterals
import scala.language.experimental.companionScopeInference

// SIP-80 composed with collection and record literals: data literals
// manufacture expected types at exactly the positions companion scope
// inference consumes them, so enum cases and companion members need no
// qualification inside literals.
object Test:
  enum Color:
    case Red, Green, Blue

  enum Geometry:
    case Circle, Rectangle, Triangle

  case class Shape(geometry: Geometry, color: Color = Color.Red)

  case class License(name: String)
  object License:
    val MIT: License = License("MIT")
    val Apache2: License = License("Apache-2.0")

  // enum cases through the collection literal's Elem expected type
  val colors: Seq[Color] = [Red, Green, Blue]
  val colorSet: Set[Color] = [Red, Blue]

  // companion vals, not just enum cases
  val licenses: Seq[License] = [MIT, Apache2]

  // nesting: both features recurse together. A `->` key is a receiver, which
  // no expected-type mechanism reaches, so it needs qualification ...
  val byColor: Map[Color, List[Geometry]] = [Color.Red -> [Circle, Rectangle]]
  // ... while tuple-form pairs propagate component expected types, so bare
  // cases work in both positions
  val byColor2: Map[Color, List[Geometry]] = [(Red, [Circle, Rectangle]), (Green, [Triangle])]

  // record literals pin constructor parameter types; defaults still apply
  val shape: Shape = (geometry = Circle)
  val shape2: Shape = (geometry = Rectangle, color = Blue)

  // the full composition: record literals inside collection literals,
  // enum cases resolved through both layers
  val shapes: Vector[Shape] = [(geometry = Circle, color = Blue), (geometry = Triangle)]

  // as arguments
  def paint(colors: Seq[Color]): Int = colors.size
  val n = paint([Red, Green])
