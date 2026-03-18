import scala.unchecked

sealed trait Tree
final class DefDef extends Tree

object Repro:
  val trees: List[Tree] = Nil

  val (allDefs: List[DefDef] @unchecked, allStepBlocks: List[Tree]) =
    trees.partition {
      case _: DefDef => true
      case _ => false
    }
