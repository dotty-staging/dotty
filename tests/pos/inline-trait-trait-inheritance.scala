inline trait A(x: Int):
    val y = x

trait C extends A

class D extends C, A(15)
