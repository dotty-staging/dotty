package a

object ASuspendInlining {
  def sixtyFour: Double = A.power(2.0, 6) // cause a suspension in inlining
}
