//> using options -experimental

// import scala.annotation.unroll

trait Unrolled(x: Int, s: String)(y: Int):
  def res: Int = x + y

class Foo(x: Int, s: String, y: Int) extends Unrolled(x, s)(y)
