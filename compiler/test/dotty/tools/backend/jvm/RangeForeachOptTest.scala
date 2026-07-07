package dotty.tools
package backend.jvm

import dotty.DottyBytecodeTest
import org.junit.Test
import org.junit.Assert.*

import scala.tools.asm.Opcodes.*

/** Checks that `foreach` (and therefore `for ... do` loops) on statically
 *  constructed ranges compiles to a while loop without any allocation:
 *  no `Range` is constructed, no closure is spawned, and captured local
 *  vars are not boxed into `IntRef`s.
 */
class RangeForeachOptTest extends DottyBytecodeTest {
  import dotty.AsmConverters.*

  @Test def literalRanges = {
    testAllocationFree("(1 to 10).foreach(x => acc += x)")
    testAllocationFree("(1 until 10).foreach(x => acc += x)")
    testAllocationFree("Range(1, 10).foreach(x => acc += x)")
    testAllocationFree("Range(1, 10, 3).foreach(x => acc += x)")
    testAllocationFree("Range(10, 0, -1).foreach(x => acc += x)")
    testAllocationFree("Range.inclusive(1, 10).foreach(x => acc += x)")
    testAllocationFree("Range.inclusive(10, 0, -2).foreach(x => acc += x)")
    testAllocationFree("(10 to 0 by -1).foreach(x => acc += x)")
    testAllocationFree("(1 to 10 by 3).foreach(x => acc += x)")
    testAllocationFree("(1 until 10 by 3).foreach(x => acc += x)")
    testAllocationFree("1.to(10, 2).foreach(x => acc += x)")
    testAllocationFree("10.until(0, -2).foreach(x => acc += x)")
  }

  @Test def variableRanges = {
    testAllocationFree("(a to b).foreach(x => acc += x)")
    testAllocationFree("(a until b).foreach(x => acc += x)")
    testAllocationFree("(a to b by s).foreach(x => acc += x)")
    testAllocationFree("Range(a, b, s).foreach(x => acc += x)")
    testAllocationFree("Range.inclusive(a, b, s).foreach(x => acc += x)")
  }

  @Test def forLoops = {
    testAllocationFree("for (x <- 1 to 10) acc += x")
    testAllocationFree("for (x <- 10 until 0 by -3) acc += x")
    testAllocationFree("for (x <- a to b by s) acc += x")
  }

  private def testAllocationFree(code: String) = {
    val source =
      s"""class Foo {
         |  def test(a: Int, b: Int, s: Int): Int = {
         |    var acc = 0
         |    $code
         |    acc
         |  }
         |}
       """.stripMargin

    checkBCode(source) { dir =>
      val clsIn = dir.lookupName("Foo.class", directory = false).nn.input
      val clsNode = loadClassNode(clsIn)
      val meth = getMethod(clsNode, "test")

      val instructions = instructionsFromMethod(meth)

      instructions.foreach {
        case TypeOp(NEW, desc) =>
          // the step-is-zero guard for a non-literal step throws IllegalArgumentException
          assertEquals(s"`$code` allocates $desc:\n${instructions.mkString("\n")}",
            "java/lang/IllegalArgumentException", desc)
        case Invoke(_, owner, name, _, _) =>
          assertFalse(s"`$code` calls $owner.$name:\n${instructions.mkString("\n")}",
            owner.startsWith("scala/collection") || name == "foreach" || name == "intWrapper")
        case _: InvokeDynamic =>
          fail(s"`$code` spawns a closure:\n${instructions.mkString("\n")}")
        case _ => ()
      }
      assertTrue(s"`$code` should contain a loop (backward jump):\n${instructions.mkString("\n")}",
        instructions.exists { case _: Jump => true; case _ => false })
    }
  }
}
