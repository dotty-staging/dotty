import scala.language.experimental.collectionLiterals
import scala.language.experimental.companionScopeInference

object Test:
  enum Color:
    case Red, Green, Blue

  // a `->` key is the receiver of `->`; no expected-type mechanism reaches it
  val byColor: Map[Color, List[Color]] = [Red -> [Green]] // error

  // untargeted literals type their elements without an expected type
  val xs = [Red] // error
