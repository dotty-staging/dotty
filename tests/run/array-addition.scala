object Test {
  def prettyPrintArray(x: Array[?]) = println("Array(" + x.mkString(", ") + ")")

  def main(args: Array[String]): Unit = {
    prettyPrintArray(Array(1,2,3) :+ 4)
    prettyPrintArray(1 +: Array(2,3,4))
    prettyPrintArray(Array[Int]() :+ 1)
    prettyPrintArray(1 +: Array[Int]())
  }
}

