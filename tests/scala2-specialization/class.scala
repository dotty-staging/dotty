class SpecializableClass[@specialized(Specializable.Return) T](x: T):
  def getX: T = x
  println("hello from constructor")
