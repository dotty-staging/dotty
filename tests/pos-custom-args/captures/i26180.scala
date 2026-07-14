import scala.language.experimental.captureChecking

object Obj:
  type C^ = {caps.any}
  type D^ = {C}
  type P = Any^{C}

  val field: Any^{C} = ???
  def parameter(a: Any^{C}): Unit = ()
  def result: Any^{C} = ???

  def chainedParameter(a: Any^{D}): Unit = ()
  def chainedResult: Any^{D} = ???
  def aliasedParameter(a: P): Unit = ()

  val lambda: Any^{C} => Unit =
    (a: Any^{C}) => ()

trait Abstract:
  type C^
  val field: Any^{C}
  def parameter(a: Any^{C}): Unit
  def result: Any^{C}

class IO

def termRefParameter(a: Any^{Obj.C}): Unit = ()

def flowUses(io: IO^): Unit =
  Obj.parameter(io)
  Obj.chainedParameter(io)
  termRefParameter(io)
  val z: Any^{Obj.C} = io
  val zd: Any^{Obj.D} = io

val inferredLambdaParameter: Any^{Obj.C} -> Unit =
  x => ()

object Inference:
  type C^ = {caps.any}
  trait R
  def acceptR(a: R^{C}): Unit = ()

def id[A](a: A): A = a

val solveSideUse =
  id[Inference.R^{Inference.C} -> Unit](x => Inference.acceptR(x))
