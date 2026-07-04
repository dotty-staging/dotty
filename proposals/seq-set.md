# SLC Proposal: `SeqSet` and `VectorSet`

**Status:** draft implementation, approved MEDIUM priority in STA review (item 3.1)
**Sources:** https://github.com/scala/scala-library-next/issues/22,
https://github.com/scala/scala-library-next/issues/168

## Motivation

The collections library is asymmetric: on the map side there is `SeqMap` (the
insertion-ordered abstraction) with `VectorMap` and `TreeSeqMap` as scalable
implementations, plus `ListMap` for tiny maps. On the set side there is *only*
`ListSet` — appropriate, performance-wise, solely for small collections (O(n)
insert/lookup, O(n²) building) and with no ordered-set abstraction above it.

`SeqSet` and `VectorSet` fill that gap:

- **`SeqSet`** — the insertion-order-preserving set abstraction, the exact
  counterpart of `SeqMap`
- **`VectorSet`** — its scalable default implementation, the counterpart of
  `VectorMap`: amortized effectively-constant `contains`/`incl`/`excl`, with
  iteration in insertion order

They are also a dependency of the ordered flavor of map inversion
(item 4.9: `SeqMap[K, V].invert: SeqMap[V1, SeqSet[K]]`).

## Proposed API

```scala
// scala.collection
trait SeqSet[A] extends Set[A] with SetOps[A, SeqSet, SeqSet[A]]
object SeqSet          // delegates to immutable.SeqSet

// scala.collection.immutable
trait SeqSet[A] extends Set[A] with collection.SeqSet[A] with SetOps[A, SeqSet, SeqSet[A]]
object SeqSet          // factory; default implementation is VectorSet

final class VectorSet[A] extends AbstractSet[A] with SeqSet[A] ...
object VectorSet extends IterableFactory[VectorSet]
```

## Semantics

- immutable; iteration and traversal follow insertion order
- adding an existing element keeps its original position; removing and
  re-adding moves it to the end
- `equals`/`hashCode` are the ordinary order-insensitive `Set` ones (matching
  `SeqMap`'s documented behavior)
- transformations (`filter`, `map`, …) preserve encounter order and return
  `VectorSet`/`SeqSet` as appropriate

## Design notes

- `VectorSet` is implemented as a thin wrapper around `VectorMap[A, Unit]`,
  reusing its vector-plus-tombstone machinery (and its performance profile)
  rather than duplicating ~200 lines of delicate slot bookkeeping. A dedicated
  representation dropping the unit values is a straightforward follow-up
  optimization if the proposal is accepted; the public API would not change.
- The builder wraps `VectorMapBuilder`, so building n elements is O(n)
  (vs `ListSet`'s O(n²)).
- **Follow-ups deliberately not in this branch:** making `ListSet` extend
  `SeqSet` (source-compatible but needs binary-compat verification);
  small-size specializations (`SeqSet1..4`) mirroring `SeqMap1..4`; a
  `TreeSeqSet` counterpart of `TreeSeqMap`; a `mutable.SeqSet` counterpart
  (`mutable.LinkedHashSet` would be the existing implementation).

## Compatibility

New public classes only; MiMa `ForwardsBreakingChanges` `MissingClassProblem`
wildcard entries for the three new types.

## Tests

`tests/run/seq-set.scala`: insertion-order iteration, first-position dedup,
position-keeping `incl` / re-add-appends behavior, order-preserving `excl`,
order-insensitive equality, `head`/`last`/`tail`/`init`, order-preserving
`filter`/`map` with type preservation, the `SeqSet` factory defaulting to
`VectorSet`, builder dedup, empties, and a 100-element workout of the
underlying tombstone machinery.
