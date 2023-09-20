import scala.language.experimental
import scala.annotation.MacroAnnotation
import scala.quoted._

class useInlinedIdentity extends MacroAnnotation {
  def transform(using Quotes)(tree: quotes.reflect.Definition): List[quotes.reflect.Definition] =
    import quotes.reflect.*
    tree match
      case DefDef(name, params, tpt, Some(rhs)) =>
        val newRhs =
          given Quotes = tree.symbol.asQuotes
          '{ inlinedIdentity(${rhs.asExpr}) }.asTerm
        List(DefDef.copy(tree)(name, params, tpt, Some(newRhs)))
}

inline def inlinedIdentity(x: Any): x.type = x
