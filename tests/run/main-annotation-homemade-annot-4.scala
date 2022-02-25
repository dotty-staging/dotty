import scala.annotation.*

@mainManyArgs(1, "B", 3) def foo() = println("Hello world!")

object Test:
  def main(args: Array[String]) =
    val clazz = Class.forName("foo")
    val method = clazz.getMethod("main", classOf[Array[String]])
    method.invoke(null, Array[String]())
end Test

@experimental
class mainManyArgs(i1: Int, s2: String, i3: Int) extends MainAnnotation:
  import MainAnnotation.*

  override type Parser[T] = util.CommandLineParser.FromString[T]
  override type Result = Any

  def command(info: CommandInfo, args: Array[String]): Command[Parser, Result] =
    new Command[Parser, Result]:
      override def argGetter[T](idx: Int, optDefaultGetter: Option[() => T])(using p: Parser[T]): () => T = ???

      override def varargGetter[T](using p: Parser[T]): () => Seq[T] = ???

      override def run(program: () => Result): Unit = program()
  end command
