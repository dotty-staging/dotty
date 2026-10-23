// In these SAM types, the SAM method also overrides a method that it does not
// override in its owner. Lambdas of these types must implement both.

trait F[T]:
  def f(x: T): T

trait G:
  def f(x: String): String

trait H:
  def f(x: Int): Int

trait R[T]:
  def g(x: String): T

trait U:
  def g(x: String): Unit

trait A:
  def f(x: Int): Int

trait B[T] extends A:
  def f(x: T): T

trait FG extends F[String], G
trait GF extends G, F[String]
trait FGSub extends FG
trait FH extends F[Int], H
trait HF extends H, F[Int]
trait RU extends R[Unit], U
trait UR extends U, R[Unit]
trait C extends B[Int]

@main def Test =
  val fg: FG = x => x + "!"
  assert((fg: F[String]).f("a") == "a!")
  assert((fg: G).f("a") == "a!")

  val gf: GF = x => x + "!"
  assert((gf: F[String]).f("a") == "a!")
  assert((gf: G).f("a") == "a!")

  val fgSub: FGSub = x => x + "!"
  assert((fgSub: F[String]).f("a") == "a!")
  assert((fgSub: G).f("a") == "a!")

  val fh: FH = x => x + 1
  assert((fh: F[Int]).f(1) == 2)
  assert((fh: H).f(1) == 2)

  val hf: HF = x => x + 1
  assert((hf: F[Int]).f(1) == 2)
  assert((hf: H).f(1) == 2)

  val c: C = x => x + 1
  assert((c: A).f(1) == 2)
  assert((c: B[Int]).f(1) == 2)

  var log = ""
  val ru: RU = x => log += x
  (ru: R[Unit]).g("a")
  (ru: U).g("b")
  val ur: UR = x => log += x
  (ur: R[Unit]).g("c")
  (ur: U).g("d")
  assert(log == "abcd")
