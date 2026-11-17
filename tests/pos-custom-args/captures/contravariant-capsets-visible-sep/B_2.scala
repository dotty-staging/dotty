package q
import language.experimental.captureChecking
import p.*

def use(io: File^) =
  O.mk(io)(io)
  O.mkSome(io).get(io)
  O.hs.head(io)
  O.mkAny().get(io)
  val f: File^ = O.mkCov()
  O.mk1(io).get(io)
  O.mk2(io).get(io)
  O.mk3(io).get(io)
