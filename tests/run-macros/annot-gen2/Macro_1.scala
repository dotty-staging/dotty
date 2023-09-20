import scala.language.experimental
import scala.annotation.MacroAnnotation
import scala.quoted._

class hello extends MacroAnnotation {
  def transform(using Quotes)(tree: quotes.reflect.Definition): List[quotes.reflect.Definition] =
    import quotes.reflect._
    tree match
      case DefDef(name, params, tpt, Some(t)) =>
        given Quotes = tree.symbol.asQuotes
        val rhs = '{
          ${t.asExprOf[String]} + "hello"
        }.asTerm
        val newDef = DefDef.copy(tree)(name, params, tpt, Some(rhs))
        List(newDef)
}
