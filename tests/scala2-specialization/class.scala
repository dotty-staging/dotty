class SpecializableClass[@specialized(Specializable.Return) T](x: T, arr: Array[T]):
  def getX: T = x
  val len = arr.length
  var idx = 0
  var specializedVar: T = x
  final def iAmFinal(): T = x
