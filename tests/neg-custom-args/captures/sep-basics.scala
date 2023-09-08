import language.experimental.captureChecking

trait Ref:
  def use(): Unit

def f(x: Ref^, sep{x} y: Ref^): Unit = ()
def g(x: Ref^, sep{x} y: Ref^, sep{x, y} z: Ref^): Unit = ()
def h(x: Ref^, sep{} y: Ref^{x}): Unit = ()
def foo(x: Ref^, sep y: Ref^): Unit = ()

def check[T, U](x: () => T)(sep{x} y: () => U): Unit = ()

def test1(a: Ref^, sep{a} b: Ref^, sep{a, b} c: Ref^): Unit =
  check(() => a)(() => b)
  check(() => a)(() => a) // error
  check(() => { a.use(); b.use() })(() => c)
  check(() => { a.use(); b.use() })(() =>{ b.use(); c.use()  }) // error

def test2(a: Ref^, sep{a} b: Ref^, sep{a, b} c: Ref^): Unit =
  val f1 = () => a.use()
  val f2 = () => b.use()
  val f3 = () => { f2(); c.use() }
  check(() => f1)(() => a)  // error
  check(() => f1)(() => b)  // ok
  check(() => f2)(() => b)  // error
  check(() => f1)(() => f3) // ok

  val g: () => Unit = ???
  check(g)(() => a) // error
  check(g)(f1) // error
  check(g)(f2) // error
  check(g)(f3) // error
