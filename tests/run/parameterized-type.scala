class ClassWithOption extends WithMap {
  val optionInClass: Option[String] = None
}
trait WithMap {
  val optionInTrait: Option[String] = None
}

object Test {
  def main(args: Array[String]) =
    classOf[ClassWithOption].getDeclaredFields.foreach { field =>
      println(field.toString + " parametrised " + field.getGenericType.isInstanceOf[java.lang.reflect.ParameterizedType])
    }
}
