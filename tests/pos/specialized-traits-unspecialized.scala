// (1)
inline trait A[T: Specialized, D: Specialized]:
    def foo: T
    def bar: D
inline trait B[S] extends A[S, Int]
trait C extends B[Char]

// expands to:
trait A$sp$S$Int$[S] extends A[S, Int]:
	def foo: S
    def bar: Int
inline trait B[S] extends A$sp$S$Int[S]
trait C extends B[Char]
// so the foo can never be specialized, even if we specialize on S:
trait A$sp$S$Int$[S] extends A[S, Int]:
	def foo: S
    def bar: Int
inline trait B[S: Specialized] extends A$sp$S$Int[S]
trait B$sp$Char extends B[Char]
trait C extends B[Char]


inline trait A$sp$S$Int$[S] extends A[S, Int]:
inline trait B[S: Specialized] extends A$sp$S$Int[S]
inline trait B$sp$Char extends B[Char]
trait C extends B[Char]
	def foo: S
    def bar: Int


def fun[S](x: A$sp$S$Int[S])
    x.bar


def fun(x: B[Char])
    x.foo

// (2) If we change the rules so that we can generate inline traits for $sp$:
inline trait A$sp$S$Int$[S] extends A[S, Int]:
	def foo: S
    def bar: Int
inline trait B[S] extends A$sp$S$Int[S]:
	def foo: S
    def bar: Int
trait C extends B[Char]
	def foo: Char
    def bar: Int

// (3) And furthermore if we have Specialized on the B[S]:
inline trait A$sp$S$Int$[S] extends A[S, Int]:
	def foo: S
    def bar: Int
inline trait B[S: Specialized] extends A$sp$S$Int[S]:
	def foo: S
    def bar: Int
inline trait B$sp$Char$ extends B[Char]:
    def foo: Char
    def bar: Int
trait C extends B$sp$Char$
	def foo: Char
    def bar: Int

// And then I would argue that there could be value in adding a warning for dropping the Specialized qualifier in case (2) 


// Should we be worried about code bloat due to inlining every time? I don't think so.
// We just need a rule to decide which method is selected.
inline trait A:
    def foo = "Hello, World"

inline trait B extends A:
    over
    def bar = "Boo"

inline trait C extends A:
    def baz = "baz"

inline trait D extends A, B, C

// Result:
inline trait A:
    def foo#1 = "Hello, World"

inline trait B extends A:
    def foo#2 = "Hello, World"
    def bar#1 = "Boo"

// inline trait C extends A:
//     def foo#3 = "Hello, World"
//     def baz#1 = "baz"

inline trait D extends A, B, C:
    def foo#1 = "Hello, World"
    def bar#1 = "Boo"
    def baz#1 = "baz"


// At the moment, the following is rejected
inline trait A:
    def foo = "Hello World"

inline trait B:
    def foo = "Bonjour"

class C extends A, B:
    def foo = "Bonjour2"

def main = 
    val x = C()
    println(x.foo)

// and this is also rejected without the override modifier, but allowed with:
trait A:
    def foo = "Hello World"

trait B extends A:
    def foo = "Bonjour"

class C extends A, B

def main = 
    val x = C()
    println(x.foo)

// while the following is allowed (and we take the value from the second trait i.e. B)
inline trait A:
    def foo = "Hello World"

inline trait B:
    def foo = "Bonjour"

class C extends A, B

def main = 
    val x = C()
    println(x.foo)
// I think this is fine and necessary if we want to make the resulting traits inline, because we need
// to extend from multiple inline traits sharing members. To be honest the behaviour will be more like the 
// override case because they come from the same inheritance hierarchy anyway.

// Alternative approach:
// (1) Erase S, concerned that this won't type correctly / will get a missing cast
// Also this is just not giving us the maximum amount of efficiency gain that we could get.
inline trait A[T: Specialized, D: Specialized]:
    def foo: T
    def bar: D
inline trait B[S] extends A[S, Int]
trait C extends B


trait A$sp$Any$Int extends A[Any, Int]:
    def foo: Any
    def bar: Int
inline trait B[S] extends A$sp$Any$Int
trait C extends B[Char]

// (2)
// Don't erase S but just don't care about the loss of specialization
trait A$sp$S$Int extends A[S, Int]:
    def foo: S
    def bar: Int
inline trait B[S] extends A$sp$S$Int[S]
trait C extends B[Char]



// This one is also kind of a massive problem....
inline trait Spec[S: Specialized]

inline trait A[T]
  def x(y: Spec[T])

class B extends A[Char]

// inline trait Spec2[W: Specialized]
// inline trait Spec[S: Specialized]
//   def z(y: Spec2[S])
// inline trait A[T]
//   def x(y: Spec[T])
// class B extends A[Char]

// 1. Specialization does nothing because no materially specialized instances
// 2. Inlining generates reference to Spec[Char] which is materially specialized
// 3. Specialization generates Spec$sp$Char class
// 4. Inlining fills this class up which generates reference to Spec2[Char]
// 5. Specialization generates Spec2$sp$Char class
// 6. Inlining again and done.