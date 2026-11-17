package p
import language.experimental.captureChecking

class File

object O:
  // Declared parameter types are pickled
  def mk(io: File^) = (f: File^{io}) => ()
  def mkSome(io: File^): Some[File^{io} -> Unit] = Some((f: File^{io}) => ())
  // `^` is pickled as it is written
  val hs = List((f: File^) => ())
  def mkAny() = Some((f: File^) => ())
  def mkCov() = File(): File^
  // Inferred capture sets in contravariant positions are pickled with the inferred type
  def sink[T]: T -> Unit = _ => ()
  def mk1(io: File^) = Some((f: File^{io}) => ())
  def mk2(io: File^) = Some(sink[File^{io}])
  def mk3(io: File^) =
    val g = (f: File^{io}) => ()
    Some(g)
