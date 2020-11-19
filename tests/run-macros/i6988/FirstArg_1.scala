package foo

case class FirstArg(value: Any, source: String)
object FirstArg {
  inline given create as FirstArg = ${Macros.argsImpl}
}

object Macros {
  import scala.quoted._

  def argsImpl(using qctx: QuoteContext) : Expr[FirstArg] = {
    import qctx.reflect._

    def enclosingClass(cur: Symbol = Owner.current.symbol): Symbol =
      if (cur.isClassDef) cur
      else enclosingClass(cur.owner)

    def enclosingParamList(owner: Symbol): Seq[Seq[Symbol]] =
      if owner.isClassDef then
        owner.tree match
          case tdef: ClassDef =>
            tdef.constructor.paramss map { _ map {_.symbol }}
      else enclosingParamList(owner.owner)

    def literal(value: String): Expr[String] =
      Literal(Constant.String(value)).asExpr.asInstanceOf[Expr[String]]
    val paramss = enclosingParamList(Owner.current.symbol)
    val firstArg = paramss.flatten.head
    val ref = Select.unique(This(enclosingClass()), firstArg.name)
    '{ FirstArg(${ref.asExpr}, ${Expr(firstArg.name)}) }
  }
}
