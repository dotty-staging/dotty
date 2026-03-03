package java.lang.ref

abstract class Reference[T] {
  def get: T | Null
  def clear(): Unit
  def isEnqueued: Boolean
  def enqueue(): Boolean
}
