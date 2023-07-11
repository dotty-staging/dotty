// scalajs: --skip

import scala.annotation.*
import collection.mutable
import scala.util.CommandLineParser.FromString

/** Sum all the numbers
 *
 *  @param first Fist number to sum
 *  @param rest The rest of the numbers to sum
 */
@myMain def sum(first: Int, second: Int): Int = first + second


object Test:
  def callMain(args: Array[String]): Unit =
    val clazz = Class.forName("sum")
    val method = clazz.getMethod("main", classOf[Array[String]])
    method.invoke(null, args)

  def main(args: Array[String]): Unit =
    callMain(Array("23", "2", "3"))
end Test
