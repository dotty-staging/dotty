package b

import a.{A, AModule, ACaseClass, ASealed, AEnum}

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
    // test case class ACaseClass
    val j = ACaseClass(1, "2")
    val k = j.copy(i = 2, s = "3") // copy is a synthetic member

  locally:
    // test sealed trait ASealed
    val l = ASealed.A1
    val m = l.productPrefix

  locally:
    // test sealed trait AEnum
    val n = AEnum.A3
    val o = AEnum.A4
}
