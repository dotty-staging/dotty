// While we don't allow inner nested classes inside inline traits, we do allow creation of anonymous classes inside methods
// inside inline traits - after all these are just ordinary methods.  

inline trait C[S]:
   def v(x: S): S = x
   def w: Unit = 
      val x = new C[S] {}
      println("w")

class B extends C[Char]
