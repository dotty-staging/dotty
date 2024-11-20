//> using options -Wlint-tracked-param

import scala.language.experimental.modularity
import scala.language.future

abstract class C:
  type T
  def foo: T

class F(val x: C):
  val result: x.T = x.foo

class G(override val x: C) extends F(x)

def Test =
  val c = new C:
    type T = Int
    def foo = 42

  val g = new G(c) // current limitation of infering in Namer, should emit a lint
  val _: Int = g.result
