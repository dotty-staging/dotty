object A {
  // The default getter must have an explicit return type (List[_] => Int)
  // This wasn't happening since e28c3edda4. That commit encoded upper/lower
  // bounds of Any/Nothing as EmptyTree, which were triggering an .isEmpty
  // check in Namers#TypeTreeSubstitutor
  def x(f: List[?] => Int = _ => 3) = 9
}
