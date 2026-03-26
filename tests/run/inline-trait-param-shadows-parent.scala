inline trait A[T](x: T):
    def y = x
trait B extends A[Int]
class C extends B, A[Int](4)

object Test:
    def main(args: Array[String]): Unit = 
        val z = new C
        println("Testing")
