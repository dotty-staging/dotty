package java.lang.ref

class ReferenceQueue[T] {
  def poll(): Reference[_ <: T] | Null = null
  def remove(timeout: Long): Reference[_ <: T] | Null = null
  def remove(): Reference[_ <: T] | Null = null
}
