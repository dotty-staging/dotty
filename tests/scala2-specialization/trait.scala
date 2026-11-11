trait Trait[@specialized(Specializable.Primitives) T]:
  def returns(): T
  def takes(t: T): Unit
  def both(t: T): T
  @annotation.unspecialized def not(t: T): T
  @annotation.unspecialized def notWithBody(t: T): T = { takes(t); returns() }
  def alreadyConcrete(n: Int): String
