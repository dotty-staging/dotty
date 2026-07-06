---
layout: doc-page
title: "Collection Literals"
nightlyOf: https://docs.scala-lang.org/scala3/reference/experimental/collection-literals.html
---

## Introduction

Collection literals are an experimental feature enabled with the language import

```scala
import language.experimental.collectionLiterals
```

A collection literal is a comma-separated list of expressions in square brackets:

```scala
val oneTwoThree: List[Int] = [1, 2, 3]
val diag: Vector[Vector[Int]] = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
val capitals: Map[String, String] = ["England" -> "London", "France" -> "Paris"]
val empty: Set[Int] = []
```

The grammar change is:

```
SimpleExpr        ::=  ...
                    |  CollectionLiteral
CollectionLiteral ::=  ‘[’ [ExprInParens {‘,’ ExprInParens}] ‘]’
```

Collection literals are expressions; they are not available in patterns. A `[...]`
directly following an expression is still a type argument list: a literal passed
as an argument is written `f([1, 2, 3])`, not `f[1, 2, 3]`. A `[...]` followed
by `=>` is still a polymorphic function literal.

## Elaboration with an expected type

If a collection literal `[x1, ..., xn]` appears with expected type `C`, the
compiler searches for a given instance of the type class
`scala.compiletime.ExpressibleAsCollectionLiteral[C]`:

```scala
trait ExpressibleAsCollectionLiteral[Coll]:
  type Elem
  inline def fromLiteral(inline xs: Elem*): Coll
```

If an instance `ecl` is found, the literal elaborates to
`ecl.fromLiteral(x1, ..., xn)`, and each element is type-checked with expected
type `ecl.Elem`. The expected type propagates recursively into elements, so
nested literals work: in `val m: Map[Int, List[Int]] = [1 -> [1, 2]]`, the
inner literal has expected type `List[Int]`.

An ambiguity between instances is a compile error, resolved by ascribing the
literal.

Instances for the immutable collections `Seq`, `List`, `Vector`, `Set`, and
`Map`, and for `IArray` (given a `ClassTag`), are provided in the companion
object of `ExpressibleAsCollectionLiteral`, so no import is needed for them.
A `Map[K, V]` instance has `Elem = (K, V)`: there is no special syntax for
maps, any pair-valued element works, and `[]` works at a map type.

Instances for mutable collections — including `Array` — exist but are not
part of the implicit scope; they require

```scala
import scala.compiletime.ExpressibleAsCollectionLiteral.mutableLiterals.given
```

so that a literal never satisfies a mutable expected type without an explicit
opt-in at the use site.

Library authors can make their own collection types expressible as literals by
defining an instance. Since `fromLiteral` is an inline method with inline
varargs, an instance can be implemented as a macro and can construct the
target collection directly, without an intermediate varargs `Seq`.

## The default type

With no usable expected type — no expected type at all, a wildcard, `Any`,
`AnyRef`, or an insufficiently constrained type variable — a collection
literal is a `scala.collection.immutable.Seq` of the least upper bound of its
element types:

```scala
val xs = [1, 2, 3]        // Seq[Int]
val ps = ["a" -> 1]       // Seq[(String, Int)], never a Map
val f  = [1, 2, 3].map(_ + 1)
```

This default is fixed by the language specification, in the same way that the
type of the literal `1` is `Int` and the type of `1.0` is `Double`. No given
search takes part in it; an untargeted literal is never a `Map`. An untargeted
map is written `Map(...)`, as always.

## Relationship to the Pre-SIP

This implementation follows the
[collection literals Pre-SIP](https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990)
with two deviations that came out of the discussion:

- The `->` shape detection for map literals is dropped. The untargeted default
  is always `Seq`; whether elements are pairs is irrelevant.
- `Coll` is invariant in `ExpressibleAsCollectionLiteral`. With a covariant
  parameter, the instance for `Vector[T]` would also be eligible at expected
  type `Seq[T]`, making every `Seq`-targeted literal ambiguous.

See also [record literals](./record-literals.md), which extend the same
target-type-directed interpretation to named tuple literals at case class
types.

## Composition with companion scope inference

Collection literals compose with
[companion scope inference](./companion-scope-inference.md) (SIP-80): a
literal's elements receive `Elem` as their expected type, which is exactly
what companion scope inference consumes, so enum cases and companion members
need no qualification inside a literal:

```scala
import language.experimental.{collectionLiterals, companionScopeInference}

enum Color:
  case Red, Green, Blue

val colors: Seq[Color] = [Red, Green, Blue]
val byColor: Map[Color, List[Color]] = [(Red, [Green, Blue])]
```

Two boundaries, both consistent with SIP-80's own rules:

- The key of a `->` pair is the *receiver* of `->`, a position that no
  expected-type mechanism reaches, so a bare case there does not resolve:
  write the pair in tuple form `(Red, ...)` — tuple literals propagate
  component expected types — or qualify the key (`Color.Red -> ...`).
- With no expected type the default rule applies and elements are typed
  without a target, so `val xs = [Red]` is an error, consistent with SIP-80's
  rule for `Seq(Red)`. Ascribe the literal.
