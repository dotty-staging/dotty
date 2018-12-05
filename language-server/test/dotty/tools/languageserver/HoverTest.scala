package dotty.tools.languageserver

import org.junit.Test

import dotty.tools.languageserver.util.Code._

class HoverTest {
  def hoverContent(typeInfo: String, comment: String = ""): Option[String] =
    Some((
      if (comment == "")
        s"""```scala
           |$typeInfo
           |```"""
      else
        s"""```scala
           |$typeInfo
           |```
           |$comment""").stripMargin)

  @Test def hoverOnWhiteSpace0: Unit =
    code"$m1 $m2".withSource.hover(m1 to m2, None)

  @Test def hoverOnClassShowsDoc: Unit = {
    code"""$m1 /** foo */ ${m2}class Foo $m3 $m4""".withSource
      .hover(m1 to m2, None)
      .hover(m2 to m3, hoverContent("Foo", "foo"))
      .hover(m3 to m4, None)
  }

  @Test def hoverOnClass0: Unit = {
    code"""$m1 ${m2}class Foo $m3 $m4""".withSource
      .hover(m1 to m2, None)
      .hover(m2 to m3, hoverContent("Foo"))
      .hover(m3 to m4, None)
  }

  @Test def hoverOnClass1: Unit = {
    code"""$m1 ${m2}class Foo { } $m3 $m4""".withSource
      .hover(m1 to m2, None)
      .hover(m2 to m3, hoverContent("Foo"))
      .hover(m3 to m4, None)
  }

  @Test def hoverOnValDef0: Unit = {
    code"""class Foo {
          |  ${m1}val x = ${m2}8$m3; ${m4}x$m5
          |}""".withSource
      .hover(m1 to m2, hoverContent("Int"))
      .hover(m2 to m3, hoverContent("Int(8)"))
      .hover(m4 to m5, hoverContent("Int"))
  }

  @Test def hoverOnValDef1: Unit = {
    code"""class Foo {
          |  ${m1}final val x = 8$m2; ${m3}x$m4
          |}""".withSource
      .hover(m1 to m2, hoverContent("Int(8)"))
      .hover(m3 to m4, hoverContent("Int(8)"))
  }

  @Test def hoverOnDefDef0: Unit = {
    code"""class Foo {
          |  ${m1}def x = ${m2}8$m3; ${m4}x$m5
          |}""".withSource
      .hover(m1 to m2, hoverContent("Int"))
      .hover(m2 to m3, hoverContent("Int(8)"))
      .hover(m4 to m5, hoverContent("Int"))
  }

  @Test def hoverMissingRef0: Unit = {
    code"""class Foo {
          |  ${m1}x$m2
          |}""".withSource
      .hover(m1 to m2, None)
  }

  @Test def hoverFun0: Unit = {
    code"""class Foo {
          |  def x: String = $m1"abc"$m2
          |  ${m3}x$m4
          |
          |  def y(): Int = 9
          |  ${m5}y($m6)$m7
          |}
        """.withSource
      .hover(m1 to m2, hoverContent("String(\"abc\")" ))
      .hover(m3 to m4, hoverContent("String"))
      .hover(m5 to m6, hoverContent("(): Int"))
      .hover(m6 to m7, hoverContent("Int"))
  }

  @Test def documentationIsCooked: Unit = {
    code"""/** A class: $$Variable
          | *  @define Variable Test
          | */
          |class ${m1}Foo${m2}
          |/** $$Variable */
          |class ${m3}Bar${m4} extends Foo
        """.withSource
      .hover(m1 to m2, hoverContent("Foo", "A class: Test"))
      .hover(m3 to m4, hoverContent("Bar", "Test"))
  }

  @Test def documentationIsFormatted: Unit = {
    code"""class Foo(val x: Int, val y: Int) {
          |  /**
          |   * Does something
          |   *
          |   * @tparam T A first type param
          |   * @tparam U Another type param
          |   * @param fizz Again another number
          |   * @param buzz A String
          |   * @param ev   An implicit boolean
          |   * @return Something
          |   * @throws java.lang.NullPointerException if you're unlucky
          |   * @throws java.lang.InvalidArgumentException if the argument is invalid
          |   * @see java.nio.file.Paths#get()
          |   * @note A note
          |   * @example myFoo.bar[Int, String](0, "hello, world")
          |   * @author John Doe
          |   * @version 1.0
          |   * @since 0.1
          |   * @usecase def bar(fizz: Int, buzz: String): Any
          |   */
          |  def ${m1}bar${m2}[T, U](fizz: Int, buzz: String)(implicit ev: Boolean): Any = ???
          |}""".withSource
      .hover(
        m1 to m2,
        hoverContent("[T, U](fizz: Int, buzz: String)(implicit ev: Boolean): Any",
                     """Does something
                       |
                       |**Type Parameters**
                       | - **T** A first type param
                       | - **U** Another type param
                       |
                       |**Parameters**
                       | - **fizz** Again another number
                       | - **buzz** A String
                       | - **ev** An implicit boolean
                       |
                       |**Returns**
                       | - Something
                       |
                       |**Throws**
                       | - **java.lang.NullPointerException** if you're unlucky
                       | - **java.lang.InvalidArgumentException** if the argument is invalid
                       |
                       |**See Also**
                       | - java.nio.file.Paths#get()
                       |
                       |**Examples**
                       | - ```scala
                       |   myFoo.bar[Int, String](0, "hello, world")
                       |   ```
                       |
                       |**Note**
                       | - A note
                       |
                       |**Authors**
                       | - John Doe
                       |
                       |**Since**
                       | - 0.1
                       |
                       |**Version**
                       | - 1.0""".stripMargin))
  }

  @Test def i5482: Unit = {
    code"""object Test {
          |  def bar: Int = 2 / 1
          |  /** hello */
          |  def ${m1}baz${m2}: Int = ???
          |}""".withSource
      .hover(m1 to m2, hoverContent("Int", "hello"))
  }

  @Test def hoverSelf: Unit = {
    code"""class Foo { ${m1}self${m2} =>
          |  val bizz = ${m3}self${m4}
          |}""".withSource
      .hover(m1 to m2, hoverContent("Foo"))
      .hover(m3 to m4, hoverContent("(Foo & Foo)(Foo.this)"))
  }
}
