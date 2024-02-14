//> using options -language:experimental.erasedDefinitions

class Ev extends Phantom

object Test {
  def foo0(a: Any): Any = a
  def foo1(a: Ev): Any = {
    foo0(
      a // error
    )
    foo0({
      println()
      a // error
    })
    foo1(a) // OK
    foo2( // error
      a // error
    )
    foo3( // error
      a
    )
    a // error
  }
  def foo2(a: Ev): Ev = {
    foo0(a) // OK
    foo1(a) // OK
    foo2(a) // OK
    foo3(a) // OK
    a // OK
  }
  def foo3(a: Ev): Ev = {
    foo0(a) // OK
    foo1(a) // OK
    foo2(a) // OK
    foo3(a) // OK
    a // OK
  }
}
