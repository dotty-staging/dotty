import util.boundary, boundary.{Label, break}

object nullable:

  extension [T](r: T | Null)

    /** `_.?` exists with null to next enclosing `nullable` boundary */
    transparent inline def ? (using Label[Null]): T =
      if r == null then break(null) else r.nn

  inline def apply[T](inline body: Label[Null] ?=> T): T | Null =
    boundary:
      val result = body
      result

class C:
  var count = 0
  def getA(): C | Null =
    if count == 2 then null
    else
      count += 1
      println("getA")
      this
  def getC(): C | Null =
    if count == 2 then null
    else
      count += 1
      println("getC")
      this

import nullable.?

@main def nullableTest() =
  val c = C()
  val r = nullable:
    c.getA().?.getC().?.getA().?.getC()
  println(r)


