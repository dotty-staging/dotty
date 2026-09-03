package a {
  inline trait A[T: Specialized]
}
package b {
  class B extends a.A[Int]
}
class C extends a.A[String]
