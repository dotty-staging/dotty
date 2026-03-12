trait A[T, R, Q]

inline trait Vec[T: {Specialized, Numeric}, S <: Object, Q: Numeric, R: Specialized, D: {Numeric, Specialized}] extends A[S, Char, T]

def foo(v: Vec[Int, String, Int, Int, Int]) = v

def main() = 
    type x = Specialized[Array[Array[Int]]]
    println("Hello, World!")

    // val x = new Vec[Int, String, Int, Int, Int]() {}
    // foo(x)

// Need to ban all of these but we will do that earlier I guess?
// Vec[Vec[Int]] hehe <- fine
// Vec[S, S[T]: Specialized] <- banned
// Vec[S, T[T]: Specialized] <- banned
// Vec[Array[T]: Specialized] <- banned


// Map(TypeBounds(TypeRef(ThisType(TypeRef(NoPrefix,module class scala)),class Nothing),
//    TypeRef(ThisType(TypeRef(NoPrefix,module class scala)),class Any)) -> TypeRef(TermRef(ThisType(TypeRef(NoPrefix,module class <root>)),object scala),class Int))

// List(TypeRef(ThisType(TypeRef(ThisType(TypeRef(NoPrefix,module class <empty>)),trait Vec)),type S),
// TypeRef(TermRef(ThisType(TypeRef(NoPrefix,module class <root>)),object scala),class Char),
// TypeRef(ThisType(TypeRef(ThisType(TypeRef(NoPrefix,module class <empty>)),trait Vec)),type T)  )))


// Challenge: annotation is on the level of type params and not on the level of methods for example. Can have some without. 

// Got tp [T#825496893, S#825496893 <: Object#744, Q#825496893, R#825496893, D#825496893]
//   #825496893
//   (using 
//     evidence$1#681088021: <root>#2.this.scala#21.Specialized#338[T#825496893],
//     evidence$2#681088021: Numeric#6014[T#825496893],
//     evidence$3#681088021: Numeric#6014[Q#825496893],
//     evidence$4#681088021: <root>#2.this.scala#21.Specialized#338[R#825496893],
//     evidence$5#681088021: Numeric#6014[D#825496893], evidence$6#681088021:
//     <root>#2.this.scala#21.Specialized#338[D#825496893])
//     ():
//       <empty>#2299.this.Vec#4482[T#825496893, S#825496893, Q#825496893,
//         R#825496893, D#825496893]


// need to test with explicit evidence. 


// inline trait Vec[T: SomeTypeClass]
// 

// def foo = 
//       instance of typeclass SomeTypeClass[Int]
//       new Vec[Int] \/

//       new VecSp without the condition
//            -> creates Vec[Int]