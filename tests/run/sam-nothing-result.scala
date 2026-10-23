// The SAM method returns `Nothing` but overrides a method returning `Unit`, which a
// LambdaMetaFactory bridge cannot implement
trait A:
  def f(): Unit
trait B extends A:
  def f(): Nothing

trait G[T]:
  def g(): T
trait GU extends G[Unit]:
  def g(): Nothing

def message(op: => Unit): String =
  try { op; "" } catch case e: RuntimeException => e.getMessage

@main def Test =
  val b: B = () => throw RuntimeException("b")
  val gu: GU = () => throw RuntimeException("gu")
  assert(message((b: A).f()) == "b")
  assert(message((gu: G[Unit]).g()) == "gu")
