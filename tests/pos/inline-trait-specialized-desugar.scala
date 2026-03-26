// User code does this: (with Specialized type class)
inline trait Iterator[T]:
  def hasNext: Boolean
  def next(): T

// User code does this: (with Specialized type class)
inline trait ArrayIterator[T](elems: Array[T]) extends Iterator[T]:
  private var current: Int = 0
  def hasNext: Boolean = current < elems.length
  def next(): T = try elems(current) finally current += 1


// Specialized traits generates these signatures:
inline trait Iterator$sp$Int extends Iterator[Int]
inline trait ArrayIterator$sp$Int extends ArrayIterator[Int], Iterator$sp$Int
class ArrayIterator$impl$Int(elems: Array[Int]) extends ArrayIterator$sp$Int, ArrayIterator[Int](elems)

// User code does this:
def foo(x: ArrayIterator[Int]): Int = x.next()

// Specialized traits converts this to
def foo(x: ArrayIterator$sp$Int): Int = x.next()

// User code does this:
/* class MyClassA
   class MyClassB extends MyClassA, ArrayIterator[Int] */

// We convert this to:
class MyClassA
class MyClassB extends MyClassA, ArrayIterator$sp$Int

@main def main = 
    val xs: Array[Int] = Array(1, 2, 3)

    // User code does this:
    /* val ai = new ArrayIterator[Int](xs) {} */

    // We convert this to:
    val ai = ArrayIterator$impl$Int(xs)
    
    println(ai.next())
