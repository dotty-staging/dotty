class O:
  trait Q:
    def outer: O = O.this

object Global:
  val o = O()

// A lambda cannot implement a trait that inherits a trait with an outer reference
trait S extends Global.o.Q:
  def apply(x: Int): Int

@main def Test =
  val s: S = x => x + 1
  assert(s(1) == 2)
  assert(s.outer eq Global.o)
