package java.lang.reflect

class Constructor[T] private[lang] () {
  def getParameters: scala.Array[Parameter] = scala.Array.empty
  def newInstance(initargs: AnyRef*): T =
    throw new UnsupportedOperationException("Constructor.newInstance not supported on Scala.js")
  def getParameterCount: Int = 0
}
