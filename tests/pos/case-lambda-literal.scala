object Test {
  val _ = List["a"]("a").foldLeft(Set.empty["a"]) { case (acc, a) => acc + a }
}
