object Test {
  def main(args: Array[String]): Unit = {
    import collection.immutable.*

    val intmap = IntMap(1 -> 1, 2 -> 2)
    val intres = intmap.map { case (a, b) => (a, b.toString) }
    assert(intres.isInstanceOf[IntMap[?]])

    val longmap = LongMap(1L -> 1, 2L -> 2)
    val longres = longmap.map { case (a, b) => (a, b.toString) }
    assert(longres.isInstanceOf[LongMap[?]])
  }
}
