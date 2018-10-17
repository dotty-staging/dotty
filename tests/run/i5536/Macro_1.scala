import scala.quoted._

object scalatest {
  inline def assert(condition: => Boolean): Unit = ${assertImpl('condition)}

  def assertImpl(condition: Expr[Boolean])(implicit st: StagingContext): Expr[Unit] = {
    import st.reflection._

    val tree = condition.unseal
    def exprStr: String = condition.show

    tree.underlyingArgument match {
      case Apply(Select(lhs, op), rhs :: Nil) =>
        val left = lhs.seal
        val right = rhs.seal
        op match {
          case "===" =>
            '{
              val _left   = $left
              val _right  = $right
              val _result = _left == _right
              scala.Predef.assert(_result)
            }
        }
    }
  }
}
