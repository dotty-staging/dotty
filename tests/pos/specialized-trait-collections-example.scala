inline trait Iterator[T: Specialized]:
  def hasNext: Boolean
  def next(): T

// They do this: (with Specialized type class)
inline trait ArrayIterator[T: Specialized](elems: Array[T]) extends Iterator[T]:
  private var current = 0
  def hasNext: Boolean = current < elems.length
  def next(): T = try elems(current) finally current += 1

// We should generate these:
// trait Iteratorsp$Int extends Iterator[Int]
// trait ArrayIterator$sp$Int extends ArrayIterator[Int], Iterator[Int]
// class ArrayIterator$impl$Int(elems: Array[Int]) extends ArrayIterator$sp$Int, ArrayIterator[Int](elems)


// They do this:
def foo(x: ArrayIterator[Int]): Int = x.next()
// We should convert this to:
// def foo(x: ArrayIterator$sp$Int): Int = x.next()
// Check that the call to next() should be a specialized call and not have boxing - can compare to without specialized to see the impact.

// They do this:
// class MyClassA
// class MyClassB extends MyClassA, ArrayIterator[Int]

// // We convert this to:
// class MyClassA
// class MyClassB extends MyClassA, ArrayIterator$sp$Int

// @main def main = 
//     val xs: Array[Int] = Array(1, 2, 3)

//     // They do this:
//     // new ArrayIterator[Int](xs) {}

//     // We convert this to:
//     val ai = new ArrayIterator$impl$Int(xs) {}
//     println(ai.next())
100