import annotation.constructorOnly

class A

class C(out: A^)
class D(@constructorOnly out: A^)

def test(o: A^) =
  val c = C(o)
  val _: C^{o} = c
  val _: C = c // error
  val cd = () => C(o)
  val _: () ->{o} C^{o} = cd
  val _: () -> C = cd // error

  val d = D(o)
  val _: D = d
  val dd = () => D(o)
  val _: () ->{o} D = dd
  val _: () -> D = dd // error


