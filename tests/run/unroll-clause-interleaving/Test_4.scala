//> using options -experimental

@main def Test: Unit =
  val u = Unrolled()
  TestV1().test(u)
  TestV2().test(u)
  TestV3().test(u)
