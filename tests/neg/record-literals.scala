import scala.language.experimental.recordLiterals

object Test:
  case class Point(x: Int, y: Int)

  // positional tuples do not convert to case classes
  val p: Point = (1, 2) // error

  // unknown fields are errors at the call
  val q: Point = (x = 1, z = 2) // error

  // named tuple values do not convert, only literals are interpreted
  val nt = (x = 1, y = 2)
  val r: Point = nt // error

  // `()` is the Unit value, never a record literal: empty or all-default
  // construction must name the class
  case class Empty()
  case class Config(verbose: Boolean = false, level: Int = 0)
  val e: Empty = () // error
  val cfg: Config = () // error
  val cfg2: Config = (verbose = true) // ok: partial, level defaulted
  val cfg3: Config = Config() // ok: explicit form
