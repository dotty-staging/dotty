# SLC Proposal: `Iterable.lazyZipAll`

**Status:** draft implementation, approved LOW priority in STA review (item 3.3)
**Source:** https://github.com/scala/scala-library-next/issues/62

## Motivation

The zip family is asymmetrically complete: `zip` has the lazy counterpart
`lazyZip`, but `zipAll` — zip with padding of the shorter side — has none.
Code that wants to combine two sequences of potentially different lengths
without materializing an intermediate collection of tuples currently has to
fall back to `zipAll` (strict, allocates the tuple collection) or hand-written
iterator plumbing. `lazyZipAll` is the missing corner of the square.

## Proposed API

Added to `scala.collection.Iterable` (next to `lazyZip`):

```scala
def lazyZipAll[A1 >: A, B](that: Iterable[B])(thisDefault: A1, thatDefault: B): LazyZip2[A1, B, this.type]
```

## Semantics

- the shorter side is padded with its default value, exactly as `zipAll`
- pairing order matches `lazyZip`
- fully lazy: nothing is consumed or materialized until a strict operation
  (`map`, `foreach`, conversion) is invoked on the returned `LazyZip2`
- strict operations build the receiver's collection type, as with `lazyZip`

## Design notes

- Returns the existing `LazyZip2` decorator rather than a new type, so all of
  its strict operations (including chained `.lazyZip`) come for free.
- Implemented by wrapping both inputs in lazy padded views built from
  `Iterator.zipAll` (`View.fromIteratorProvider`); each traversal of the
  decorator re-derives the padding, keeping the decorator itself stateless.
  This iterates both sources once per traversal side; a dedicated
  `LazyZipAll2` class with a single lockstep iterator would be the optimized
  follow-up if accepted.
- `A1 >: A` widening lets the this-side default be a supertype of the element
  type (same shape as `zipAll`).

## Compatibility

Pure addition to `Iterable`; MiMa `ForwardsBreakingChanges` entry.

## Tests

`tests/run/lazy-zip-all.scala`: padding in both directions, equal lengths,
empty sides, receiver-typed strict results, laziness of the decorator, and
pair-building.
