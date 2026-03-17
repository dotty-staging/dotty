inline trait A[T](x: T):
    def y = x
trait B extends A[Int]
trait D extends A[Int]
trait E extends B
trait F extends D
class C extends E, F
// A[Int](4)


object Test:
    def main(args: Array[String]): Unit = 
        val z = new C
        println("Testing")
