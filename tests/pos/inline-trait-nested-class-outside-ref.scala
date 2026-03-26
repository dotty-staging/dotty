inline trait A:
    class InnerA:
        val x = 10

class B extends A:
   def foo = 10

def x = 
    val b = B()
    val c = b.InnerA()
    c
