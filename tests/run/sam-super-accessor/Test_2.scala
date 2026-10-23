// Lambdas cannot implement the super accessors of their SAM traits,
// so they have to be expanded to anonymous classes.

trait T extends B:
  override def m: Int = super.m + 2
  def apply(x: Int): Int

trait U extends S

@main def Test =
  val s: S = (x: Int) => x // super accessor of S is unpickled from TASTy
  val t: T = (x: Int) => x // super accessor of T is added in this run
  val u: U = (x: Int) => x // super accessor of S is inherited
  assert(s.m == 2 && s(3) == 3)
  assert(t.m == 3 && t(3) == 3)
  assert(u.m == 2 && u(3) == 3)
