inline trait A:
  lazy val x = 1

class B extends A:
  def f = x