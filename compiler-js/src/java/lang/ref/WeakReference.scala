package java.lang.ref

class WeakReference[T](referent: T, queue: ReferenceQueue[? >: T] | Null) extends Reference[T] {
  def this(referent: T) = this(referent, null)

  private var ref: T | Null = referent

  def get: T | Null = ref
  def clear(): Unit = ref = null
  def isEnqueued: Boolean = false
  def enqueue(): Boolean = false
}
