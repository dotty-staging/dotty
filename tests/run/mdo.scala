import scala.language.experimental.monadicDo

def mdo1 = mdo {
  val x = 1
  x
}

def mdo2 = mdo {
  val x = ⋇Some(1)
  x
}

def mdo3 = mdo {
  val x = ⋇Some(1)
  val y = ⋇Some(2)
  x + y
}

object Test extends App {
  println(mdo1)
  println(mdo2)
  println(mdo3)
}
