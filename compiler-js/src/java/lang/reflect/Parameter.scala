package java.lang.reflect

class Parameter private[lang] () {
  def getName: String = "arg"
  def getType: Class[?] = classOf[Object]
}
