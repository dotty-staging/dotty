import scala.collection.mutable
import scala.annotation.MainAnnotation

@myMain()("A")
def foo1(): Unit = println("I was run!")

@myMain(0)("This should not be printed")
def foo2() = throw new Exception("This should not be run")

@myMain(1)("Purple smart", "Blue fast", "White fashion", "Yellow quiet", "Orange honest", "Pink loud")
def foo3() = println("Here are some colors:")

@myMain()()
def foo4() = println("This will be printed, but nothing more.")

object Test:
  val allClazzes: Seq[Class[?]] =
    LazyList.from(1).map(i => scala.util.Try(Class.forName("foo" + i.toString))).takeWhile(_.isSuccess).map(_.get)

  def callMains(): Unit =
    for (clazz <- allClazzes)
      val method = clazz.getMethod("main", classOf[Array[String]])
      method.invoke(null, Array[String]())

  def main(args: Array[String]) =
    callMains()
end Test

/** Code mostly copied from {{scala.main}}. */
class myMain(runs: Int = 3)(after: String*) extends MainAnnotation:
  import MainAnnotation._
  import main.{Arg}

  private val maxLineLength = 120

  override type ArgumentParser[T] = util.CommandLineParser.FromString[T]
  override type MainResultType = Any

  private enum ArgumentKind {
    case SimpleArgument, OptionalArgument, VarArgument
  }

  override def command(args: Array[String], commandName: String, docComment: String) =
    new Command[ArgumentParser, MainResultType]:
      private val argMarker = "--"
      private val shortArgMarker = "-"

      private var argNames = new mutable.ArrayBuffer[String]
      private var argShortNames = new mutable.ArrayBuffer[Option[Char]]
      private var argTypes = new mutable.ArrayBuffer[String]
      private var argDocs = new mutable.ArrayBuffer[String]
      private var argKinds = new mutable.ArrayBuffer[ArgumentKind]

      /** A buffer for all errors */
      private var errors = new mutable.ArrayBuffer[String]

      /** Issue an error, and return an uncallable getter */
      private def error(msg: String): () => Nothing =
        errors += msg
        () => throw new AssertionError("trying to get invalid argument")

      /** The next argument index */
      private var argIdx: Int = 0

      private def argAt(idx: Int): Option[String] =
        if idx < args.length then Some(args(idx)) else None

      private def isArgNameAt(idx: Int): Boolean =
        val arg = args(argIdx)
        val isFullName = arg.startsWith(argMarker)
        val isShortName = arg.startsWith(shortArgMarker) && arg.length == 2 && shortNameIsValid(arg(1))

        isFullName || isShortName

      private def nextPositionalArg(): Option[String] =
        while argIdx < args.length && isArgNameAt(argIdx) do argIdx += 2
        val result = argAt(argIdx)
        argIdx += 1
        result

      private def shortNameIsValid(shortName: Char): Boolean =
        shortName == 0 || shortName.isLetter

      private def convert[T](argName: String, arg: String, p: ArgumentParser[T]): () => T =
        p.fromStringOption(arg) match
          case Some(t) => () => t
          case None => error(s"invalid argument for $argName: $arg")

      private def argUsage(pos: Int): String =
        val name = argNames(pos)
        val namePrint = argShortNames(pos).map(short => s"[$shortArgMarker$short | $argMarker$name]").getOrElse(s"[$argMarker$name]")

        argKinds(pos) match {
          case ArgumentKind.SimpleArgument => s"$namePrint <${argTypes(pos)}>"
          case ArgumentKind.OptionalArgument => s"[$namePrint <${argTypes(pos)}>]"
          case ArgumentKind.VarArgument => s"[<${argTypes(pos)}> [<${argTypes(pos)}> [...]]]"
        }

      private def wrapLongLine(line: String, maxLength: Int): List[String] = {
        def recurse(s: String, acc: Vector[String]): Seq[String] =
          val lastSpace = s.trim.nn.lastIndexOf(' ', maxLength)
          if ((s.length <= maxLength) || (lastSpace < 0))
            acc :+ s
          else {
            val (shortLine, rest) = s.splitAt(lastSpace)
            recurse(rest.trim.nn, acc :+ shortLine)
          }

        recurse(line, Vector()).toList
      }

      private def wrapArgumentUsages(argsUsage: List[String], maxLength: Int): List[String] = {
        def recurse(args: List[String], currentLine: String, acc: Vector[String]): Seq[String] =
          (args, currentLine) match {
            case (Nil, "") => acc
            case (Nil, l) => (acc :+ l)
            case (arg :: t, "") => recurse(t, arg, acc)
            case (arg :: t, l) if l.length + 1 + arg.length <= maxLength => recurse(t, s"$l $arg", acc)
            case (arg :: t, l) => recurse(t, arg, acc :+ l)
          }

        recurse(argsUsage, "", Vector()).toList
      }

      private inline def shiftLines(s: Seq[String], shift: Int): String = s.map(" " * shift + _).mkString("\n")

      private def usage(): Unit =
        val usageBeginning = s"Usage: $commandName "
        val argsOffset = usageBeginning.length
        val argUsages = wrapArgumentUsages((0 until argNames.length).map(argUsage).toList, maxLineLength - argsOffset)

        println(usageBeginning + argUsages.mkString("\n" + " " * argsOffset))

      private def explain(): Unit =
        if (docComment.nonEmpty)
          println(wrapLongLine(docComment, maxLineLength).mkString("\n"))
        if (argNames.nonEmpty) {
          val argNameShift = 2
          val argDocShift = argNameShift + 2

          println("Arguments:")
          for (pos <- 0 until argNames.length)
            val argDoc = StringBuilder(" " * argNameShift)
            argDoc.append(s"${argNames(pos)} - ${argTypes(pos)}")

            argKinds(pos) match {
              case ArgumentKind.OptionalArgument => argDoc.append(" (optional)")
              case ArgumentKind.VarArgument => argDoc.append(" (vararg)")
              case _ =>
            }

            if (argDocs(pos).nonEmpty) {
              val shiftedDoc =
                argDocs(pos).split("\n").nn
                            .map(line => shiftLines(wrapLongLine(line.nn, maxLineLength - argDocShift), argDocShift))
                            .mkString("\n")
              argDoc.append("\n").append(shiftedDoc)
            }

            println(argDoc)
        }

      private def indicesOfArg(argName: String, shortArgName: Option[Char]): Seq[Int] =
        def allIndicesOf(s: String, from: Int): Seq[Int] =
          val i = args.indexOf(s, from)
          if i < 0 then Seq() else i +: allIndicesOf(s, i + 1)

        val indices = allIndicesOf(s"$argMarker$argName", 0)
        val indicesShort = shortArgName.map(shortName => allIndicesOf(s"$shortArgMarker$shortName", 0)).getOrElse(Seq())
        (indices ++: indicesShort).filter(_ >= 0)

      private def getArgGetter[T](paramInfos: ParameterInfos[_], getDefaultGetter: () => () => T)(using p: ArgumentParser[T]): () => T =
        val argName = getEffectiveName(paramInfos)
        indicesOfArg(argName, getShortName(paramInfos)) match {
          case s @ (Seq() | Seq(_)) =>
            val argOpt = s.headOption.map(idx => argAt(idx + 1)).getOrElse(nextPositionalArg())
            argOpt match {
              case Some(arg) => convert(argName, arg, p)
              case None => getDefaultGetter()
            }
          case s =>
            val multValues = s.flatMap(idx => argAt(idx + 1))
            error(s"more than one value for $argName: ${multValues.mkString(", ")}")
        }

      private inline def getEffectiveName(paramInfos: ParameterInfos[_]): String =
        paramInfos.annotations.collectFirst{ case arg: Arg if arg.name.length > 0 => arg.name }.getOrElse(paramInfos.name)

      private inline def getShortName(paramInfos: ParameterInfos[_]): Option[Char] =
        paramInfos.annotations.collectFirst{ case arg: Arg if arg.shortName != 0 => arg.shortName }

      private def registerArg(paramInfos: ParameterInfos[_], argKind: ArgumentKind): Unit =
        argNames += getEffectiveName(paramInfos)
        argTypes += paramInfos.typeName
        argDocs += paramInfos.documentation.getOrElse("")
        argKinds += argKind

        val shortName = getShortName(paramInfos)
        shortName.foreach(c => if !shortNameIsValid(c) then throw IllegalArgumentException(s"Invalid short name: $shortArgMarker$c"))
        argShortNames += shortName

      override def argGetter[T](paramInfos: ParameterInfos[T])(using p: ArgumentParser[T]): () => T =
        val name = getEffectiveName(paramInfos)
        val (defaultGetter, argumentKind) = paramInfos.defaultValueOpt match {
          case Some(value) => (() => value, ArgumentKind.OptionalArgument)
          case None => (() => error(s"missing argument for $name"), ArgumentKind.SimpleArgument)
        }
        registerArg(paramInfos, argumentKind)
        getArgGetter(paramInfos, defaultGetter)

      override def varargGetter[T](paramInfos: ParameterInfos[T])(using p: ArgumentParser[T]): () => Seq[T] =
        registerArg(paramInfos, ArgumentKind.VarArgument)
        def remainingArgGetters(): List[() => T] = nextPositionalArg() match
          case Some(arg) => convert(getEffectiveName(paramInfos), arg, p) :: remainingArgGetters()
          case None => Nil
        val getters = remainingArgGetters()
        () => getters.map(_())

      override def run(f: => MainResultType): Unit =
        def checkShortNamesUnique(): Unit =
          val shortNameToIndices = argShortNames.collect{ case Some(short) => short }.zipWithIndex.groupBy(_._1).view.mapValues(_.map(_._2))
          for ((shortName, indices) <- shortNameToIndices if indices.length > 1)
            error(s"$shortName is used as short name for multiple parameters: ${indices.map(idx => argNames(idx)).mkString(", ")}")

        def flagUnused(): Unit = nextPositionalArg() match
          case Some(arg) =>
            error(s"unused argument: $arg")
            flagUnused()
          case None =>
            for
              arg <- args
              if arg.startsWith(argMarker) && !argNames.contains(arg.drop(2))
            do
              error(s"unknown argument name: $arg")
        end flagUnused

        if args.contains(s"${argMarker}help") then
          usage()
          println()
          explain()
        else
          flagUnused()
          checkShortNamesUnique()
          if errors.nonEmpty then
            for msg <- errors do println(s"Error: $msg")
            usage()
          else
            for (_ <- 1 to runs)
              f
              if after.length > 0 then println(after.mkString(", "))
      end run
  end command
end myMain