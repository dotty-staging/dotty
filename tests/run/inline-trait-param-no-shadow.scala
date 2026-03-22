// TODO: Decide if we want to allow this or not (might ban trait extends inline trait pattern)

inline trait A[T](x: T):
    val y = x
trait B extends A[Int]
class C extends B

object Test:
    def main(args: Array[String]): Unit = 
        val z = new C
