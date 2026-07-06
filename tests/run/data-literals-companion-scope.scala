import scala.language.experimental.collectionLiterals
import scala.language.experimental.recordLiterals
import scala.language.experimental.companionScopeInference

enum Color:
  case Red, Green, Blue

enum Geometry:
  case Circle, Rectangle, Triangle

case class Shape(geometry: Geometry, color: Color = Color.Red)

@main def Test =
  val colors: Seq[Color] = [Red, Green, Blue]
  assert(colors == Seq(Color.Red, Color.Green, Color.Blue))

  // tuple-form pairs propagate component expected types; `->` keys are
  // receivers and would need qualification
  val byColor: Map[Color, List[Geometry]] = [(Red, [Circle, Rectangle]), (Green, [Triangle])]
  assert(byColor(Color.Red) == List(Geometry.Circle, Geometry.Rectangle))
  assert(byColor(Color.Green) == List(Geometry.Triangle))

  val shape: Shape = (geometry = Circle)
  assert(shape == Shape(Geometry.Circle, Color.Red)) // default color applied

  val shapes: Vector[Shape] = [(geometry = Circle, color = Blue), (geometry = Triangle)]
  assert(shapes == Vector(Shape(Geometry.Circle, Color.Blue), Shape(Geometry.Triangle)))

  println("ok")
