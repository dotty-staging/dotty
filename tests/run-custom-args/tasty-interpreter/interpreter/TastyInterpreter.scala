package scala.tasty.interpreter

import scala.quoted._
import scala.tasty.inspector.TastyInspector

class TastyInterpreter extends TastyInspector {

  protected def processCompilationUnit(using QuoteContext)(root: qctx.reflect.Tree): Unit = {
    import qctx.reflect._
    object Traverser extends TreeTraverser {

      override def traverseTree(tree: Tree)(using Owner): Unit = tree match {
        // TODO: check the correct sig and object enclosement for main
        case DefDef("main", _, _, _, Some(rhs)) =>
          val interpreter = new jvm.Interpreter

          interpreter.eval(rhs)(using Map.empty)
        // TODO: recurse only for PackageDef, ClassDef
        case tree =>
          super.traverseTree(tree)
      }
    }
    Traverser.traverseTree(root)
  }
}
