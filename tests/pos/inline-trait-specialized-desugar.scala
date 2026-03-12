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
inline trait Iteratorsp$Int extends Iterator[Int]
inline trait ArrayIterator$sp$Int extends ArrayIterator[Int], Iterator[Int]
class ArrayIterator$impl$Int(elems: Array[Int]) extends ArrayIterator$sp$Int, ArrayIterator[Int](elems)

// Inline traits does the magic of actually inlining the code and specialising from T to Int in that step.


// They do this:
// def foo(x: ArrayIterator[Int]): Int = x.next()
// We convert this to:
def foo(x: ArrayIterator$sp$Int): Int = x.next()
// As long as we generate this (i.e. "do the special erasure") before we run inline traits we should be fine because then the reference will be replaced.


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

    // We convert this to:
    val ai = new ArrayIterator$impl$Int(xs) {}
    println(ai.next())
