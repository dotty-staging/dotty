// They do this: (with Specialized type class)
inline trait Iterator[T]:
  def hasNext: Boolean
  def next(): T

// They do this: (with Specialized type class)
inline trait ArrayIterator[T](elems: Array[T]) extends Iterator[T]:
  private var current = 0
  def hasNext: Boolean = current < elems.length
  def next(): T = try elems(current) finally current += 1


// We generate these:
trait Iteratorsp$Int extends Iterator[Int]
trait ArrayIterator$sp$Int extends ArrayIterator[Int], Iterator$sp$Int
class ArrayIterator$impl$Int(elems: Array[Int]) extends ArrayIterator$sp$Int, ArrayIterator[Int](elems)

// We could keep the signatures in the sp trait and then put the implementations in the impl class
// This would still require the modification to inline traits to inline all the way down, but also pruning at every step
//  


// Inline traits does the magic of actually inlining the code and specialising from T to Int in that step.


// They do this:
// def foo(x: ArrayIterator[Int]): Int = x.next()
// We convert this to:
def foo(x: ArrayIterator$sp$Int): Int = x.next()
// As long as we generate this (i.e. "do the special erasure") before we run inline traits we should be fine because then the reference will be replaced.


// trait Foo$sp$Int
// 

// what if they do already some kind of with clause

// They do this:
// class MyClassA
// class MyClassB extends MyClassA, ArrayIterator[Int]

// We convert this to:
class MyClassA
class MyClassB extends MyClassA, ArrayIterator$sp$Int

@main def main = 
    val xs: Array[Int] = Array(1, 2, 3)

    // They do this:
    // new ArrayIterator[Int](xs) {}

    // new ArrayIterator$sp$Int with ArrayIterator[Int] (xs) {}

    // We convert this to:
    val ai = new ArrayIterator$impl$Int(xs) {}
    


    
    println(ai.next())


// Concerns:
  // Specializing the "arr" field 
  // - Avoid boxing for internal values like `result` will that actually get done? - maybe through the last point "calls will be inlined"
//  - The superclass of `C` is a top class, or `C` itself is a top class.
// Drop all specialized trait parameters of A
// - adds `A[S]` as first parent trait,
//  - _also_ adds all parents of `A` in their specialized forms,
//  - contains all specialized declarations of `A`.

// and yet "second parent is not needed"
//  - repeats the value parameters of trait `A`,
//  - extends `A[S]`.

// If we can manage to get rid of the inheritance there that could be helpful in terms of avoiding multiple values
// BUT: generate a version which is with just inline traits that has this problem as well.
// Need to deal with the caching at some point
// These implementation classes are type correct as long as we inject the knowledge that a specialization trait
// like `Seq$sp$Int` is equal to its parameterized version `Seq[Int]`
