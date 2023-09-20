import scala.language.experimental
import scala.annotation.MacroAnnotation
import scala.quoted._
import scala.collection.mutable

class addClass extends MacroAnnotation:
  def transform(using Quotes)(tree: quotes.reflect.Definition): List[quotes.reflect.Definition] =
    import quotes.reflect._
    tree match
      case DefDef(name, List(TermParamClause(Nil)), tpt, Some(rhs)) =>
        val parents = List(TypeTree.of[Object])
        def decls(cls: Symbol): List[Symbol] =
          List(Symbol.newMethod(cls, "run", MethodType(Nil)(_ => Nil, _ => TypeRepr.of[Unit]), Flags.EmptyFlags, Symbol.noSymbol))

        val mod = Symbol.newModule(Symbol.spliceOwner, Symbol.freshName("Bar"), Flags.EmptyFlags, Flags.EmptyFlags, parents.map(_.tpe), decls, Symbol.noSymbol)
        val cls = mod.moduleClass

        val runSym = cls.declaredMethod("run").head

        val runDef = DefDef(runSym, _ => Some(rhs))

        val (modVal, clsDef) = ClassDef.module(mod, parents, body = List(runDef))

        val newDef = DefDef.copy(tree)(name, List(TermParamClause(Nil)), tpt, Some(Apply(Select(Ref(mod), runSym), Nil)))
        List(modVal, clsDef, newDef)
      case _ =>
        report.error("Annotation only supports `def` with one argument")
        List(tree)
