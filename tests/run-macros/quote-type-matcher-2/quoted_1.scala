import scala.quoted._

object Macros {

  inline def lift[A]: String = ${ matchesExpr('[A]) }

  private def matchesExpr(tp: TypeTag[_])(given QuoteContext): Expr[String] = {
    def lift(tp: TypeTag[_]): String = tp match {
      case '[Int] => "%Int%"
      case '[List[$t]] => s"%List[${lift(t)}]%"
      case '[Option[$t]] => s"%Option[${lift(t)}]%"
      case '[Function1[$t, $u]] => s"%${lift(t)} => ${lift(u)}%"
      case _ => tp.show
    }
    Expr(lift(tp))
  }

}
