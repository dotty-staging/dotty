def f() =
  type F = { val read: Any ->{this} Any } // error
  null.asInstanceOf[F^]

def g() =
  type F = { val read: Any ->{this} Any } // error
  null.asInstanceOf[F]

def h() =
  type F = { val io: Object^; val read: Any ->{io} Any } // error
  null.asInstanceOf[F]
