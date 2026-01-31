trait MyIterable[A] {
  def it: Iterator[A]
  def foreach[U](f: A => U): Unit =
    val i = it
    while (i.hasNext) do f(i.next)
}
class R extends MyIterable[Int] {
  def it = Iterator.empty[Int]
  val start: Int = 0
  val end: Int = 1
  val step: Int = 1
  def isEmpty = false
  // `f` still typed as Any => Any at runtime so no specialization happens
  inline override def foreach[U](f: Int => U): Unit = scala.util.boundary {
    val lastElem = 1
    if (!isEmpty) {
      var i = start
      while (true) {
        f(i)
        if (i == lastElem) scala.util.boundary.break()
        i = i + step
      }
    }
  }
}
