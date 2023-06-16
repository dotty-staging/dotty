import scala.language.experimental.monadicDo

def mdo1 = mdo {
  val x = 1
  x
}

def mdo2 = mdo {
  val x = 🌯Some(1)
  x
}

def mdo3 = mdo {
  val x = 🌯Some(1)
  val y = 🌯Some(2)
  x + y
}

// def mdo4 = mdo {
//   val f = ((x: Int) => 🌯Some(x + 1)) // error
//   val y = 🌯Some(2)
//   f(y)
// }

def mdo5 = mdo {
  🌯Some(2) + 🌯Some(3)
}

object Test extends App {
  println(mdo1)
  println(mdo2)
  println(mdo3)
  println(mdo5)
}
