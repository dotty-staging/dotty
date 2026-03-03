package java.util

/** Stub EnumSet backed by a regular HashSet */
class EnumSet[E <: java.lang.Enum[E]] private () extends java.util.HashSet[E] {
  // forEach is inherited from HashSet/AbstractCollection
}

object EnumSet {
  def noneOf[E <: java.lang.Enum[E]](elementType: Class[E]): EnumSet[E] = new EnumSet[E]()
  def of[E <: java.lang.Enum[E]](e: E): EnumSet[E] = {
    val set = new EnumSet[E]()
    set.add(e)
    set
  }
  def of[E <: java.lang.Enum[E]](e1: E, e2: E): EnumSet[E] = {
    val set = new EnumSet[E]()
    set.add(e1)
    set.add(e2)
    set
  }
}
