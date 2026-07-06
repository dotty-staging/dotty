import scala.language.experimental.collectionLiterals
import scala.language.experimental.recordLiterals
import scala.language.experimental.companionScopeInference

enum Color:
  case Red, Green, Blue

enum Geometry:
  case Circle, Rectangle, Triangle

case class Shape(geometry: Geometry, color: Color = Color.Red)

enum ColorMode:
  case Plain
  case Rgb(r: Int, g: Int, b: Int)

@main def Test =
  val colors: Seq[Color] = [Red, Green, Blue]
  assert(colors == Seq(Color.Red, Color.Green, Color.Blue))

  // `->` keys resolve via the receiver-position rule; tuple-form pairs via
  // component expected types
  val byColor: Map[Color, List[Geometry]] = [Red -> [Circle, Rectangle], Green -> [Triangle]]
  assert(byColor(Color.Red) == List(Geometry.Circle, Geometry.Rectangle))
  assert(byColor(Color.Green) == List(Geometry.Triangle))
  val byColor2: Map[Color, List[Geometry]] = [(Red, [Circle]), (Blue, [Triangle])]
  assert(byColor2(Color.Blue) == List(Geometry.Triangle))

  val shape: Shape = (geometry = Circle)
  assert(shape == Shape(Geometry.Circle, Color.Red)) // default color applied

  val shapes: Vector[Shape] = [(geometry = Circle, color = Blue), (geometry = Triangle)]
  assert(shapes == Vector(Shape(Geometry.Circle, Color.Blue), Shape(Geometry.Triangle)))

  // parameterized enum cases as factories, in element and field positions
  val modes: Seq[ColorMode] = [Plain, Rgb(255, 0, 0)]
  assert(modes == Seq(ColorMode.Plain, ColorMode.Rgb(255, 0, 0)))
  val rgb: ColorMode.Rgb = (r = 1, g = 2, b = 3)
  assert(rgb == ColorMode.Rgb(1, 2, 3))

  println("ok")
