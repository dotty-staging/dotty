//> using options -experimental

import scala.annotation.unroll

trait Unrolled(x: Int, s: String)(y: Int, @unroll z: Int = 10):
  def res: Int = x + y + z

class Bar(x: Int, s: String, y: Int, z: Int) extends Unrolled(x, s)(y, z)
