# SLC Proposal: `Map.unionWith` (and `unionWithOption`)

**Status:** draft implementation, approved HIGH priority in STA review (item 4.10:
"absolutely, but would also like a variant that can choose to remove keys if needed")
**Source:** https://contributors.scala-lang.org/t/standard-library-now-open-for-improvements-and-suggestions/7337/24

## Motivation

Merging two maps while combining the values of overlapping keys is a
fundamental operation — summing counters, taking the max per key, merging
configurations. `++`/`concat` silently lets the right-hand side win, which is a
classic source of bugs, and the correct spelling today is verbose:

```scala
that.foldLeft(self) { case (acc, (k, v)) =>
  acc.updatedWith(k) { case Some(w) => Some(combine(w, v)); case None => Some(v) }
}
```

The operation is ubiquitous elsewhere: Haskell's `Data.Map.unionWith`, and —
notably — **Scala's own `IntMap` and `LongMap` already ship it** (`IntMap.unionWith(that, f)`),
so this proposal also closes an internal inconsistency where only two
specialized maps offer the operation.

## Proposed API

Added to `scala.collection.MapOps`:

```scala
def unionWith[V2 >: V](that: collection.Map[K, V2], f: (K, V2, V2) => V2): CC[K, V2]
def unionWithOption[V2 >: V](that: collection.Map[K, V2], f: (K, V2, V2) => Option[V2]): CC[K, V2]
```

## Semantics

- keys present in only one map are preserved with their value, untouched by `f`
- keys present in both are combined: `f(key, valueInThis, valueInThat)`
- `unionWithOption` is the reviewer-requested removing variant: returning
  `None` for an overlapping key drops it from the result (keys present in only
  one map are always kept)
- returns the receiver's own map type `CC[K, V2]`, like `concat`; the value
  type widens as needed
- for sorted maps the default implementation returns the base `Map` type (as
  `MapOps.concat` did before `SortedMapOps` refined it); a sorted refinement is
  a natural follow-up if accepted

## Design notes

- **Parameter shape:** a single parameter list `(that, f)` — matching the PDF
  proposal and the existing `IntMap.unionWith`/`LongMap.unionWith` precedent
  (Scala 3 infers the lambda's parameter types fine in the same list).
- `IntMap`/`LongMap`'s specialized `unionWith(that: IntMap[S], f)` remains as a
  more specific overload; calls with `IntMap` arguments keep hitting the fast
  tree-merge path.
- Implementation is two passes (receiver, then `that` filtered to fresh keys)
  into the receiver's builder — no intermediate collections.
- Naming of the removing variant (`unionWithOption` vs `unionCollect` vs a
  flag) is open for the SLC discussion.

## Compatibility

Pure additions to `MapOps`; MiMa `ForwardsBreakingChanges` filter entries.

## Tests

`tests/run/map-union-with.scala`: combine/preserve behavior, key argument,
argument order, disjoint and empty cases, value widening, own-type results and
receiver immutability for mutable maps, removal via `unionWithOption`, and
coexistence with `IntMap.unionWith`.
