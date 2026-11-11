trait T[@specialized(Int) X, A]

abstract class C[@specialized(Int) Y, A] extends T[Y, A]

trait T2[@specialized(Int) Z, A] extends T[Z, A]
