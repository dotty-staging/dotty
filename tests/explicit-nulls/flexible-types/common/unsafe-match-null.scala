def test1 =
  val s: String = ???
  s.trim() match
    case _: String =>
    case null => // error: Values of types Null and String cannot be compared
