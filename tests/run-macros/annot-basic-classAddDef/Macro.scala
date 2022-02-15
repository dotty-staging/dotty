import scala.annotation.experimental
import scala.quoted._
import scala.annotation.MacroAnnotation

@experimental
class change extends MacroAnnotation {
  override def transform(using Quotes)(tree: quotes.reflect.Definition): List[quotes.reflect.Definition] =
    import quotes.reflect._
    tree match
      case ClassDef(name, constr, parents, self, body) =>
        val changeSymbol = Symbol.newVal(tree.symbol, "changed", TypeRepr.of[String], Flags.EmptyFlags, Symbol.noSymbol)
        val changeVal = ValDef(changeSymbol, Some(Literal(StringConstant(" changed by macro annotation"))))
        val newBody = changeVal +: body.map {
          case stat @ DefDef("toString", paramss, tpt, Some(t)) =>
            given Quotes = tree.symbol.asQuotes
            val rhs = '{
              ${t.asExprOf[String]} + ${Ref(changeSymbol).asExprOf[String]}
            }.asTerm
            DefDef.copy(stat)("toString", paramss, tpt, Some(rhs))
          case stat => stat
        }
        List(ClassDef.copy(tree)(name, constr, parents, self, newBody))
}
