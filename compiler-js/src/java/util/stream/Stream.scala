package java.util.stream

/** Stub Stream for Scala.js */
trait Stream[T] extends AutoCloseable {
  def toArray[A](generator: java.util.function.IntFunction[Array[A]]): Array[A]
  def toArray(generator: java.util.function.IntFunction[Array[Object]]): Array[Object]
  def forEach(action: java.util.function.Consumer[? >: T]): Unit
  def iterator(): java.util.Iterator[T]
  def close(): Unit = ()
}
