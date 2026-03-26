inline trait A:
    def foo = "Hello World"

inline trait B:
    def foo = "Bonjour"

class C extends A, B

@main def Test: Unit = 
    val c = C()
    assert(c.foo == "Bonjour") 
