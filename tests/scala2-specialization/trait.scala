trait Trait[@specialized(Specializable.Primitives) T]:
  def returns(): T
  def takes(t: T): Unit
  def both(t: T): T
