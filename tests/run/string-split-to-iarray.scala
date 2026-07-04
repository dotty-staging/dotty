@main def Test: Unit =
  // static result type is an immutable array
  val parts: IArray[String] = "a,b,c".splitToIArray(",")
  assert(parts.toSeq == Seq("a", "b", "c"))

  // mirrors java.lang.String.split: trailing empty strings are removed
  assert("a,b,,".splitToIArray(",").toSeq == Seq("a", "b"))
  // ... but interior empty strings are kept
  assert("a,,b".splitToIArray(",").toSeq == Seq("a", "", "b"))

  // limit overload, same semantics as String.split(regex, limit)
  assert("a,b,c".splitToIArray(",", 2).toSeq == Seq("a", "b,c"))
  assert("a,b,,".splitToIArray(",", -1).toSeq == Seq("a", "b", "", ""))

  // the separator is a regular expression
  assert("one1two22three".splitToIArray("\\d+").toSeq == Seq("one", "two", "three"))

  // no match returns the whole string as a single token
  assert("abc".splitToIArray(",").toSeq == Seq("abc"))

  // invalid patterns are reported like String.split
  try
    "abc".splitToIArray("(")
    assert(false)
  catch case _: java.util.regex.PatternSyntaxException => ()
