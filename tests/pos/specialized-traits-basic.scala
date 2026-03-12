trait A[T, R, Q]

inline trait Vec[T: {Specialized, Numeric}, S <: Object, Q: Numeric, R: Specialized, D: {Numeric, Specialized}] extends A[S, Char, T]

def foo(v: Vec[Int, String, Int, Int, Int]) = v

def main() = 
    println("Hello, World!")
