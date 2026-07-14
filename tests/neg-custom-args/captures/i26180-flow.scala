import scala.language.experimental.captureChecking

class IO

object ObjIO:
  type C^ = {caps.any}
  type D^ = {C}

  def parameter(a: IO^{C}): Unit = ()
  def chainedParameter(a: IO^{D}): Unit = ()

def termRefParameter(a: IO^{ObjIO.C}): Unit = ()

def rejectedFlows(io: IO^): Unit =
  ObjIO.parameter(io)                 // error
  ObjIO.chainedParameter(io)          // error
  termRefParameter(io)                // error
  val z: IO^{ObjIO.C} = io            // error
  val zd: IO^{ObjIO.D} = io           // error
