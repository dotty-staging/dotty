// Lazy vals and objects need fields, so a lambda cannot implement a trait that inherits them
trait LazyValSam extends LazyValParent:
  def apply(x: Int): Int

trait ObjectSam extends ObjectParent:
  def apply(x: Int): Int

@main def Test =
  val s: LazyValSam = x => x + 1
  assert(s.get + s.get == 84)
  assert(Counter.inits == 1)
  val t: ObjectSam = x => x + 1
  assert(t.get + t.get == 2)
  assert(Counter.inits == 2)
