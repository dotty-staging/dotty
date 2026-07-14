import scala.language.experimental.captureChecking

object Aliased:
  type C^ = {caps.any}

  def pair(a: Any^{C}, b: Any^{C}): Unit = ()

object Inline:
  def pair(a: Any^{caps.any}, b: Any^{caps.any}): Unit = ()

def test(cap: caps.Capability): Unit =
  Aliased.pair(cap, cap)
  Inline.pair(cap, cap) // error
