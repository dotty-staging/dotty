trait B:
  def m: Int = 1

trait S extends B:
  override def m: Int = super.m + 1
  def apply(x: Int): Int
