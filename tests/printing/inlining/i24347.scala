case class Point(x: Int, y: Int)
case class Line(p1: Point, p2: Point)

inline def size[T]: Int =
  inline compiletime.erasedValue[T] match
    case i: Int => 1
    case p: Point => 2
    case vec: Line => size[Point] + size[Point]
    case _ => compiletime.error("Unsupported type")

inline def encode(mem: Array[Int], baseOffset: Int, inline relativeOffset: Int, v: Any): Unit =
  inline v match
    case v: Int =>
      mem(baseOffset + relativeOffset) = v
    case p: Point =>
      encode(mem, baseOffset, relativeOffset, p.x)
      encode(mem, baseOffset, relativeOffset + size[Int], p.y)
    case vec: Line =>
      encode(mem, baseOffset, relativeOffset, vec.p1)
      encode(mem, baseOffset, relativeOffset + size[Point], vec.p2)
    case _ =>
      compiletime.error("Unsupported type")

inline def encode(mem: Array[Int], baseOffset: Int, v: Any): Unit =
  encode(mem, baseOffset, 0, v)

@main def Main =
  val l: Array[Int] = Array(0, 0, 0, 0)
  val offset: Int = ???
  encode(l, offset, Line(Point(10, 20), Point(30, 40)))
  println(l.toList)
