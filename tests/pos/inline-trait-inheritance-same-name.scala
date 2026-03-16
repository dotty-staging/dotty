inline trait A:
    def foo = "Hello World"

inline trait B:
    def foo = "Bonjour"

class C extends A, B

def main = 
    val x = C()
    println(x.foo)
