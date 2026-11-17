package q
import language.experimental.captureChecking
import p.*

// Checked before Defs.scala, so it sees the types that the typer inferred for
// the members of `O`. These have the capture sets in contravariant positions.
def use(io: File^) =
  O.mk1(io).get(io)
  O.mk2(io).get(io)
  O.mk3(io).get(io)
