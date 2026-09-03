//> using options -language:experimental.specializedTraits
package package2

class B extends package1.A[Int]

object Impls:
  val d = new package1.A[Int]() {}
