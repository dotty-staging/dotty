package p
import language.experimental.captureChecking

class File

object O:
  def sink[T]: T -> Unit = _ => ()
  def mk1(io: File^) = Some((f: File^{io}) => ())
  def mk2(io: File^) = Some(sink[File^{io}])
  def mk3(io: File^) =
    val g = (f: File^{io}) => ()
    Some(g)
