import scala.language.experimental.collectionLiterals
import scala.language.experimental.recordLiterals
import scala.language.experimental.companionScopeInference

object Test:
  enum Color:
    case Red, Green, Blue

  // untargeted literals type their elements without an expected type
  val xs = [Red] // error

  // a record literal never picks an enum case from the parent type; the
  // case must be named (`Rgb(r = 1, ...)`)
  enum ColorMode:
    case Plain
    case Rgb(r: Int, g: Int, b: Int)
  val cm: ColorMode = (r = 1, g = 2, b = 3) // error
