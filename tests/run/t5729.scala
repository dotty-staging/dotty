trait T[X]
object Test extends App {
  def join(in: Seq[T[?]]): Int = 1
  def join[S](in: Seq[T[S]]): String = "x"
  val x = join(null: Seq[T[?]])
  assert(x == 1)  // first alt chosen, since second requires a capture conversion in adapt
}
