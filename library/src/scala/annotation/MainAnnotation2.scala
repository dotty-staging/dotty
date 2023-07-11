package scala.annotation

/** MainAnnotation provides the functionality for a compiler-generated main class.
 *  It links a compiler-generated main method (call it compiler-main) to a user
 *  written main method (user-main).
 *  The protocol of calls from compiler-main is as follows:
 *
 *    - create a `command` with the command line arguments,
 *    - for each parameter of user-main, a call to `command.argGetter`,
 *      or `command.varargGetter` if is a final varargs parameter,
 *    - a call to `command.run` with the closure of user-main applied to all arguments.
 *
 *  Example:
 *  ```scala sc:nocompile
 *  /** Sum all the numbers
 *   *
 *   *  @param first Fist number to sum
 *   *  @param rest The rest of the numbers to sum
 *   */
 *  @myMain def sum(first: Int, second: Int = 0, rest: Int*): Int = first + second + rest.sum
 *  ```
 *  generates
 *  ```scala sc:nocompile
 *  object foo {
 *    def main(args: Array[String]): Unit = {
 *      val mainAnnot = new myMain()
 *      val info = new Info(
 *        name = "foo.main",
 *        documentation = "Sum all the numbers",
 *        parameters = Seq(
 *          new Parameter("first", "scala.Int", hasDefault=false, isVarargs=false, "Fist number to sum"),
 *          new Parameter("rest", "scala.Int" , hasDefault=false, isVarargs=true, "The rest of the numbers to sum")
 *        )
 *      )
 *      val mainArgsOpt = mainAnnot.command(info, args)
 *      if mainArgsOpt.isDefined then
 *        val mainArgs = mainArgsOpt.get
 *        val args0 = mainAnnot.argGetter[Int](info.parameters(0), mainArgs(0), None) // using parser Int
 *        val args1 = mainAnnot.argGetter[Int](info.parameters(1), mainArgs(1), Some(() => sum$default$1())) // using parser Int
 *        val args2 = mainAnnot.varargGetter[Int](info.parameters(2), mainArgs.drop(2)) // using parser Int
 *        mainAnnot.run(() => sum(args0(), args1(), args2()*))
 *    }
 *  }
 *  ```
 *
 *  @param Parser The class used for argument string parsing and arguments into a `T`
 *  @param Result The required result type of the main method.
 *                If this type is Any or Unit, any type will be accepted.
 */
@experimental
trait MainAnnotation2[Parser[_], Result] extends MacroAnnotation:
  import MainAnnotation2.{Info, Parameter}
  import scala.quoted.*

  /** Process the command arguments before parsing them.
   *
   *  Return `Some` of the sequence of arguments that will be parsed to be passed to the main method.
   *  This sequence needs to have the same length as the number of parameters of the main method (i.e. `info.parameters.size`).
   *  If there is a varags parameter, then the sequence must be at least of length `info.parameters.size - 1`.
   *
   *  Returns `None` if the arguments are invalid and parsing and run should be stopped.
   *
   *  @param info The information about the command (name, documentation and info about parameters)
   *  @param args The command line arguments
   */
  def command(info: Info, args: Seq[String]): Option[Seq[String]]

  /** The getter for the `idx`th argument of type `T`
   *
   *   @param idx The index of the argument
   *   @param defaultArgument Optional lambda to instantiate the default argument
   */
  def argGetter[T](param: Parameter, arg: String, defaultArgument: Option[() => T])(using Parser[T]): () => T

  /** The getter for a final varargs argument of type `T*` */
  def varargGetter[T](param: Parameter, args: Seq[String])(using Parser[T]): () => Seq[T]

  /** Run `program` if all arguments are valid if all arguments are valid
   *
   *  @param program A function containing the call to the main method and instantiation of its arguments
   */
  def run(program: () => Result): Unit

  /**
   * Generate proxy classes for main functions.
   * A function like
   *
   *     /**
   *       * Lorem ipsum dolor sit amet
   *       * consectetur adipiscing elit.
   *       *
   *       * @param x my param x
   *       * @param ys all my params y
   *       */
   *     @myMain(80) def f(
   *       @myMain.Alias("myX") x: S,
   *       y: S,
   *       ys: T*
   *     ) = ...
   *
   *  would be translated to something like
   *
   *     final class f {
   *       static def main(args: Array[String]): Unit = {
   *         val annotation = new myMain(80)
   *         val info = new Info(
   *           name = "f",
   *           documentation = "Lorem ipsum dolor sit amet consectetur adipiscing elit.",
   *           parameters = Seq(
   *             new scala.annotation.MainAnnotation.Parameter("x", "S", false, false, "my param x", Seq(new scala.main.Alias("myX"))),
   *             new scala.annotation.MainAnnotation.Parameter("y", "S", true, false, "", Seq()),
   *             new scala.annotation.MainAnnotation.Parameter("ys", "T", false, true, "all my params y", Seq())
   *           )
   *         ),
   *         val command = annotation.command(info, args)
   *         if command.isDefined then
   *           val cmd = command.get
   *           val args0: () => S = annotation.argGetter[S](info.parameters(0), cmd(0), None)
   *           val args1: () => S = annotation.argGetter[S](info.parameters(1), mainArgs(1), Some(() => sum$default$1()))
   *           val args2: () => Seq[T] = annotation.varargGetter[T](info.parameters(2), cmd.drop(2))
   *           annotation.run(() => f(args0(), args1(), args2()*))
   *       }
   *     }
   */
  def transform(using Quotes)(tree: quotes.reflect.Definition): List[quotes.reflect.Definition] =
    import quotes.reflect._
    tree match
      case DefDef(name, argss, _, _) =>
        val parents = List(TypeTree.of[Object])
        def decls(cls: Symbol): List[Symbol] =
          List(Symbol.newMethod(cls, "main", MethodType(List("args"))(_ => List(TypeRepr.of[Array[String]]), _ => TypeRepr.of[Unit]), Flags.JavaStatic, Symbol.noSymbol))

        val cls = Symbol.newClass(Symbol.spliceOwner.owner, name, parents = parents.map(_.tpe), decls, selfType = None)
        val mainSym = cls.declaredMethod("main").head

        def mainBody(paramss: List[List[Tree]])(using Quotes): Option[Term] = {
          /** Creates a list of references and definitions of arguments.
           *  The goal is to create the
           *    `val args0: () => S = annotation.argGetter[S](0, cmd(0), None)`
           *  part of the code.
           */
          def argValDefs(mt: MethodType, annotation: Expr[MainAnnotation2[?, ?]], info: Expr[Info], cmd: Expr[Seq[String]])(using Quotes): List[ValDef] =
            for ((formal, paramName), idx) <- mt.paramTypes.zip(mt.paramNames).zipWithIndex yield
              // val isRepeated = formal.isRepeatedParam
              // val formalType = if isRepeated then formal.argTypes.head else formal
              // val getterName = if isRepeated then nme.varargGetter else nme.argGetter
              // val defaultValueGetterOpt = defaultValueSymbols.get(idx) match
              //   case None => ref(defn.NoneModule.termRef)
              //   case Some(dvSym) =>
              //       val value = unitToValue(ref(dvSym.termRef))
              //       Apply(ref(defn.SomeClass.companionModule.termRef), value)
              // val argGetter0 = TypeApply(Select(Ident(nme.annotation), getterName), TypeTree(formalType) :: Nil)
              // val index = Literal(Constant(idx))
              // val paramInfo = Apply(Select(Ident(nme.info), nme.parameters), index)
              // val argGetter =
              //   if isRepeated then Apply(argGetter0, List(paramInfo, Apply(Select(Ident(nme.cmd), nme.drop), List(index))))
              //   else Apply(argGetter0, List(paramInfo, Apply(Ident(nme.cmd), List(index)), defaultValueGetterOpt))
              // ValDef(argName, TypeTree(), argGetter)

              println(">>>>>>>>>")
              println(tree.symbol.owner)
              val annotType = TypeRepr.typeConstructorOf(this.getClass)
              // TODO get the Parser type

              // Implicits.search(formal) match
              //   case searchResult: ImplicitSearchSuccess =>
              //     searchResult.tree
              //   case searchResult: ImplicitSearchFailure =>
              //     searchResult.explanation


              val body = '{ $annotation.argGetter($info.parameters(${Expr(idx)}), $cmd(0), None)(using ???) }
              val argSym = Symbol.newVal(mainSym, s"arg$idx", TypeRepr.of[Nothing], Flags.EmptyFlags, Symbol.noSymbol)
              ValDef(argSym, Some(body.asTerm))
          end argValDefs

          val argsParam = paramss.head.head.asExprOf[Array[String]]
          val body = '{
            val annotation: MainAnnotation2[?, ?] = ??? // TODO instantiate current annotation
            val info = new Info(
              name = "f",
              documentation = "Lorem ipsum dolor sit amet consectetur adipiscing elit.",
              parameters = Seq(
  //            new scala.annotation.MainAnnotation.Parameter("x", "S", false, false, "my param x", Seq(new scala.main.Alias("myX"))),
  //            new scala.annotation.MainAnnotation.Parameter("y", "S", true, false, "", Seq()),
  //            new scala.annotation.MainAnnotation.Parameter("ys", "T", false, true, "all my params y", Seq())
              )
            )
            val command = annotation.command(info, $argsParam.toSeq)
            if command.isDefined then
              val cmd = command.get
              ${
                val args = argValDefs(tree.symbol.info.asInstanceOf[MethodType], 'annotation, 'info,'cmd)
                println(args.map(_.show))
                // val args0: () => S = annotation.argGetter[S](info.parameters(0), cmd(0), None)
                // val args1: () => S = annotation.argGetter[S](info.parameters(1), cmd(1), Some(() => sum$default$1()))
                // val args2: () => Seq[T] = annotation.varargGetter[T](info.parameters(2), cmd.drop(2))
                // annotation.run(() => f(args0(), args1(), args2()*))
                Block(args, '{???}.asTerm).asExpr
              }
          }
          Some(body.asTerm)
        }
        val mainDef = DefDef(mainSym, mainBody(_)(using mainSym.asQuotes))
        val clsDef = ClassDef(cls, parents, body = List(mainDef))

        println(tree.show)
        println(" ")
        println(mainDef.show)

        List(clsDef, tree)
      case _ =>
        report.error("Annotation only supports `def`s")
        List(tree)



end MainAnnotation2

@experimental
object MainAnnotation2:

  /** Information about the main method
   *
   *  @param name The name of the main method
   *  @param documentation The documentation of the main method without the `@param` documentation (see Parameter.documentaion)
   *  @param parameters Information about the parameters of the main method
   */
  @experimental // MiMa does not check scope inherited @experimental
  final class Info(
    val name: String,
    val documentation: String,
    val parameters: Seq[Parameter],
  ):

    /** If the method ends with a varargs parameter */
    def hasVarargs: Boolean = parameters.nonEmpty && parameters.last.isVarargs

  end Info

  /** Information about a parameter of a main method
   *
   *  @param name The name of the parameter
   *  @param typeName The name of the parameter's type
   *  @param hasDefault If the parameter has a default argument
   *  @param isVarargs If the parameter is a varargs parameter (can only be true for the last parameter)
   *  @param documentation The documentation of the parameter (from `@param` documentation in the main method)
   *  @param annotations The annotations of the parameter that extend `ParameterAnnotation`
   */
  @experimental // MiMa does not check scope inherited @experimental
  final class Parameter(
    val name: String,
    val typeName: String,
    val hasDefault: Boolean,
    val isVarargs: Boolean,
    val documentation: String,
    val annotations: Seq[ParameterAnnotation],
  )

  /** Marker trait for annotations that will be included in the Parameter annotations. */
  @experimental // MiMa does not check scope inherited @experimental
  trait ParameterAnnotation extends StaticAnnotation

end MainAnnotation2
