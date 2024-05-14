package b

import a.{A, AModule, ACaseClass}

object B {

  locally:
    // test standalone class A
    val a = new A
    val f: 2 = a.foo(1)
    val g: (1,2,3) = a.foo

  locally:
    // test standalone object AModule
    val h: 2 = AModule.foo(1)
    val i: (1,2,3) = AModule.foo

  locally:
    // test standalone case class ACaseClass
    val j = ACaseClass(1, "2")
    val k = j.copy(i = 2, s = "3") // copy is a synthetic member
}
