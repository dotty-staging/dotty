inline trait A[T](x: T):
    def y = x

trait B extends A[Int]
trait D extends A[Int]

// These two are not really necessary for the case but add to the "indirectness"
trait E extends B
trait F extends D

class C extends E, F

object Test:
    def main(args: Array[String]): Unit = 
        val z = new C
        println("Testing")
