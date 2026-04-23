package scala.reflect.compat

import org.junit.Assert.*
import org.junit.Test

import scala.reflect.runtime.universe.*

class RuntimeUniverseTest:
  @Test def materializesTypeTags(): Unit =
    val tag = summon[TypeTag[List[String]]]
    val weak = summon[WeakTypeTag[Int]]

    assertTrue(tag.tpe.show.contains("List"))
    assertEquals(typeOf[List[String]], tag.tpe)
    assertEquals("scala.Int", weak.tpe.show)

  @Test def reflectsJavaMethodInvocation(): Unit =
    val mirror = scala.reflect.runtime.currentMirror
    val stringClass = mirror.staticClass("java.lang.String")
    val length = stringClass.toType.member(TermName("length")).asMethod

    val result = mirror.reflect("abc").reflectMethod(length)()

    assertEquals(3, result.asInstanceOf[Int])
