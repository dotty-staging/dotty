//> using options -Wlint-tracked-param

import scala.language.experimental.modularity
import scala.language.future

abstract class C:
  type T
  def foo: T

class F(val x: C): // x is infered to be tracked
  val result: x.T = x.foo

class G(override val x: C) extends F(x) // warn
