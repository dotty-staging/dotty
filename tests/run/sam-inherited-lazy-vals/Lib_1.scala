object Counter:
  var inits = 0

trait LazyValParent:
  private lazy val cached = { Counter.inits += 1; 42 }
  def get: Int = cached

trait ObjectParent:
  private object O:
    Counter.inits += 1
    val v = 1
  def get: Int = O.v
