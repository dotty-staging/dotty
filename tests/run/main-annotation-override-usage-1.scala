class myMain extends main:
  override def usage(commandName: String, args: Seq[Argument]): Unit =
    val argInfos = args map (
      _ match {
        case s: SimpleArgument => s.name
        case o: OptionalArgument[?] => s"${o.name}?"
        case v: VarArgument => s"${v.name}*"
      }
    )
    println(s"My shiny command works like this: $commandName ${argInfos.mkString(" ")}")

object myProgram:

  /** Adds two numbers */
  @myMain def add(num: Int, inc: Int = 1): Unit =
    println(s"$num + $inc = ${num + inc}")

end myProgram

object Test:
  def callMain(args: Array[String]): Unit =
    val clazz = Class.forName("add")
    val method = clazz.getMethod("main", classOf[Array[String]])
    method.invoke(null, args)

  def main(args: Array[String]): Unit =
    callMain(Array("--help"))
end Test
