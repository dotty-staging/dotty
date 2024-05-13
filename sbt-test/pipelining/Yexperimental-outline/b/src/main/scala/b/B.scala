package b

import a.{A, AModule}

object B {
  val a = new A
  val f: 2 = a.foo(1)
  val g: (1,2,3) = a.foo
  val h: 2 = AModule.foo(1)
  val i: (1,2,3) = AModule.foo
}
