//> using options -Wlint-tracked-param

import scala.language.experimental.modularity
import scala.language.future

abstract class C:
  type T
  def foo: T

class F0(val x: C): // x is infered to be tracked
  val result: x.T = x.foo

class F1(tracked val x: C):
  val result: x.T = x.foo

class G0(val y: C) extends F0(y) // warn

class G00(override val x: C) extends F0(x) // warn

class G1(override val x: C) extends F1(x) // warn

class G11(val y: C) extends F1(y)
