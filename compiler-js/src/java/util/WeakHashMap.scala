package java.util

/** Stub WeakHashMap backed by regular HashMap — no weak semantics on Scala.js */
class WeakHashMap[K, V] extends java.util.HashMap[K, V] {
  def this(initialCapacity: Int) = this()
  def this(initialCapacity: Int, loadFactor: Float) = this()
}
