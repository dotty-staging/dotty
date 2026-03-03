package java.nio.file

class InvalidPathException(input: String, reason: String, index: Int)
    extends IllegalArgumentException(s"$reason at index $index: $input") {
  def this(input: String, reason: String) = this(input, reason, -1)
  def getInput(): String = input
  def getReason(): String = reason
  def getIndex(): Int = index
}
