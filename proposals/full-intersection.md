# SLC Proposal: `Set.fullIntersection`

**Status:** draft implementation as a basis for discussion — marked 🤔 (needs
stronger motivation) in STA review (item 4.11; the reviewer has asked the
upstream issue for more motivation and suggested scala-collection-contrib as a
possible home)
**Source:** https://github.com/scala/scala-library-next/issues/184

## Motivation

Comparing two sets fully — what's only here, what's shared, what's only there —
is the shape of every reconciliation task: diffing desired vs actual state,
comparing package sets, computing sync plans. Today it takes three traversals
(`a.diff(b)`, `a.intersect(b)`, `b.diff(a)`), re-testing membership of every
element twice.

The upstream motivation was explicitly about avoiding those repeated
traversals, which — as the review noted — means the method must be a real
primitive, not a convenience wrapper. This implementation does one pass over
each set (each element's membership is tested exactly once), so it does deliver
the traversal saving; per-implementation structural overrides (e.g. CHAMP node
walks) could sharpen the constant factor further.

## Proposed API

Added to `scala.collection.SetOps`:

```scala
def fullIntersection[B >: A](that: collection.Set[B]): (C, C, CC[B])
```

## Semantics

- returns `(elementsOnlyInThis, elementsInBoth, elementsOnlyInThat)`
- the first two components use the receiver's own set type `C` (element type
  `A`); the third is `CC[B]` (the that-only elements have type `B`), mirroring
  how `intersect`/`diff` type their results
- component-wise equal to `(this diff that, this intersect that, that diff this)`
- one membership test per element of each set

## Open questions for SLC discussion

- Whether this belongs in the core library or scala-collection-contrib (the
  reviewer's question to the upstream issue is still open)
- Element-membership of `B`-typed elements in `this: Set[A]` requires an
  erasure-safe cast internally (same technique the equality-based set
  operations already rely on); an alternative signature `that: Set[A]`
  (like `diff`) would avoid it at the cost of the widened third component

## Compatibility

Pure addition to `SetOps`; MiMa `ForwardsBreakingChanges` entry.

## Tests

`tests/run/full-intersection.scala`: the three components, agreement with
`diff`/`intersect`, disjoint/equal/empty cases, receiver-typed components
(SortedSet), and element widening.
