// Check Java calls have been cast to non-nullable.

def test[T <: AnyRef] =
  val j: J = new J

  val s = j.f()
  val sn = s == null
  val sn2 = s != null
  val seqn = s eq null
  val seqn2 = null eq s


  val t = j.g[T]()
  val tn = t == null
  val tn2 = t != null
  val teqn = t eq null
  val teqn2 = null eq t