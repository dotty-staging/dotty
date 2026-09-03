//> using options -language:experimental.specializedTraits
@main def Test =
  val b = package2.B()
  assert(b.foo(10) == "Package 1!")
  assert(b.bar == "package2.B")
  assert(package2.Impls.d.bar == "package1.A$impl$scala$Int")
  val e = new package1.A[Int]() {}
  assert(e.bar == "package1.A$impl$scala$Int")
