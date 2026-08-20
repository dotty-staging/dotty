case class C(xs: Int*)
case class M(x: Int, s: String, rest: Int*)
case class G[T](xs: T*)
case class P(x: Int)(ys: String*) // second param list is not part of the product
case class N(s: String, xs: Int*)
case class NoVarargs(x: Int, y: Int)
case class Custom(xs: Int*):
  override def toString = s"Custom with ${xs.length} elems"

enum E:
  case Varargs(xs: Int*)
  case Plain

object Test:
  def main(args: Array[String]): Unit =
    println(C())
    println(C(1))
    println(C(1, 2, 3))
    println(C(List(4, 5)*))
    println(M(1, "a"))
    println(M(1, "a", 2, 3))
    println(G("a", "b"))
    println(G[Int]())
    println(P(1)("a", "b"))
    println(N(null, 1))
    println(NoVarargs(1, 2))
    println(Custom(1, 2))
    println(E.Varargs(1, 2))
    println(E.Plain)
