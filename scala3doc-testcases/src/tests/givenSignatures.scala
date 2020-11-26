package tests

package givenSignatures



class GivenClass {
    trait B
    trait C[T]
    val r: Int = 5
    type R = Int
    given R = r
    trait Ord[T] {
        def compare(x: T, y: T): Int
        extension (x: T) def < (y: T) = compare(x, y) < 0
        extension (x: T) def > (y: T) = compare(x, y) > 0
    }
    given Ord[Int] as intOrd {
        def compare(x: Int, y: Int) =
            if (x < y) -1 else if (x > y) +1 else 0
    }

    given (int: Int) => B as asd

    given [T] => C[T] as asd2

    given [T] => (ord: Ord[T]) => Ord[List[T]] as listOrd {

        def compare(xs: List[T], ys: List[T]): Int = (xs, ys) match
            case (Nil, Nil) => 0
            case (Nil, _) => -1
            case (_, Nil) => +1
            case (x :: xs1, y :: ys1) =>
                val fst = ord.compare(x, y)
                if (fst != 0) fst else compare(xs1, ys1)
    }

    given Int.type as IntOps = Int

    given GivenType = GivenType()

    class GivenType
}

