import language.experimental.captureChecking

class A

def test1(a: A^, b: A^) =
  type at = a.type
  val c1: at^{a} = a
  val c2: c1.type = c1
  val c3: c1.type = a

def test2(a: A^, b: A^) =
  type at = a.type
  val d1: at^{b} = a
  val d2: d1.type = d1
  val d3: d1.type = a
  val d4: at^{b} = d2
