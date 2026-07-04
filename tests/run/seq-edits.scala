@main def Test: Unit =
  val xs = List(1, 2, 3, 4)

  // deleted removes the element at the index
  assert(xs.deleted(1) == List(1, 3, 4))
  assert(xs.deleted(0) == List(2, 3, 4))
  assert(xs.deleted(3) == List(1, 2, 3))
  try { xs.deleted(4); assert(false) } catch case _: IndexOutOfBoundsException => ()
  try { xs.deleted(-1); assert(false) } catch case _: IndexOutOfBoundsException => ()
  val v: Vector[Int] = Vector(1, 2, 3).deleted(2) // receiver's own type
  assert(v == Vector(1, 2))

  // updatedWith optionally replaces (Some) or removes (None) the element
  assert(xs.updatedWith(1, a => Some(a * 10)) == List(1, 20, 3, 4))
  assert(xs.updatedWith(1, _ => None) == List(1, 3, 4))
  try { xs.updatedWith(9, Some(_)); assert(false) } catch case _: IndexOutOfBoundsException => ()
  val w: List[AnyVal] = List(1, 2).updatedWith(0, _ => Some(2.5)) // widening like updated
  assert(w == List(2.5, 2))

  // splitAround splits at the FIRST separator, excluding it from both halves
  assert(List(1, 0, 2, 0, 3).splitAround(0) == (List(1), List(2, 0, 3)))
  assert("bucket/path/prefix".toList.splitAround('/') ==
    ("bucket".toList, "path/prefix".toList))
  // absent separator: whole input left, empty right (like span)
  assert(List(1, 2).splitAround(9) == (List(1, 2), List()))
  assert(List.empty[Int].splitAround(9) == (Nil, Nil))
  // separator as first/last element
  assert(List(0, 1, 2).splitAround(0) == (Nil, List(1, 2)))
  assert(List(1, 2, 0).splitAround(0) == (List(1, 2), Nil))
