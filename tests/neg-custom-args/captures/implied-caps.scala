import caps.*

class OutputChannel uses caps.any:  // this makes every instance capoture `any`
  def println(): Unit = ()

class OutputChannel2 extends SharedCapability:  // this doesn't. We only add `^` to explicit types.` Surprising?
  def println(): Unit = ()


class A(chan: OutputChannel^):
  def report() =
    chan.println()

class B(chan: OutputChannel^):
  chan.println()

  def report(c: OutputChannel^) =
    c.println()

def test =

  val out = OutputChannel()

  val a = A(out)
  val _: A^{out} = a
  val _: A^{} = a  // error
  val b = B(out)
  val _: B^{out} = b
  val _: B^{} = b  // error

class A2(chan: OutputChannel2^):
  def report() =
    chan.println()

class B2(chan: OutputChannel2^):
  chan.println()

  def report(c: OutputChannel2^) =
    c.println()

def test2 =
  val out2 = OutputChannel2()
  val a2 = A2(out2)
  val _: A2^{out2} = a2
  val _: A2 = a2 // error
  val b2 = B2(out2)
  val _: B2^{out2} = b2
  val _: B2 = b2 // error

