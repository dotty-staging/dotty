// The synthetic companion object of a case class needs a field but does not reset
// `NoInits`, so a lambda cannot implement a trait that inherits it
trait CaseClassParent:
  private case class C(x: Int)
  def companion: AnyRef = C

trait S extends CaseClassParent:
  def apply(x: Int): Int

@main def Test =
  val s: S = x => x + 1
  assert(s(1) == 2)
  assert(s.companion eq s.companion)
