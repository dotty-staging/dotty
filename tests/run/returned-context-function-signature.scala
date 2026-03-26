// scalajs: --skip
// (this is a JVM-only test)

object CtxFns {
  abstract class Context:
    def puts[T](t: T): Unit

  def puts[T](t: T): Context ?=> Unit = summon[Context].puts(t)
}

object Test:
  def main(args: Array[String]): Unit =
    classOf[CtxFns.type].getDeclaredMethods.sortBy(_.getName).foreach(m =>
      println(m)
      println(m.getParameterTypes().mkString(",") + " -> " + m.getReturnType.toString)
      println(m.getGenericParameterTypes().mkString(",") + " -> " + m.getGenericReturnType.toString)
    )
