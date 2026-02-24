inline trait A:
  sealed class InnerA:
    val x = 1
  def generate(x: Int) = InnerA()

class B extends A:
  val y = generate(7)
