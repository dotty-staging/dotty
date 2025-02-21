package example

import scala.scalajs.js

trait JSTrait extends js.Object


trait ObjectMixin {
  class JSTraitImpl extends JSTrait
}

object MyObject extends ObjectMixin

object Program {
  def main(args: Array[String]): Unit = {
    val x: JSTrait = new MyObject.JSTraitImpl()
    x match {
      case x: MyObject.JSTraitImpl => println("Is impl type")
      case _ => println("Is not impl type")
    }
  }
}
