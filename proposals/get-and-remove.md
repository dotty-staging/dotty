# SLC Proposal: `immutable.Map.getAndRemove`

**Status:** draft implementation as a basis for discussion — marked 🤔 (needs
stronger motivation) in STA review (item 4.7)
**Source:** https://github.com/scala/scala-library-next/issues/86

## Motivation

Taking an entry out of an immutable map — getting the value *and* the map
without it — is the natural primitive for work-queue, cache-eviction and
linear-consumption patterns over immutable state. Spelled with existing API it
is either two lookups:

```scala
(map.get(k), map.removed(k))
```

or the review's one-lookup workaround, which needs a mutable cell smuggled
through `updatedWith`:

```scala
var cell: Option[V] = None
val map1 = map.updatedWith(k) { case None => None; case some => cell = some; None }
(cell, map1)
```

**Addressing the review's challenge** (the `updatedWith` equivalence above):
the workaround exists but is exactly the kind of code a standard library
should absorb — the mutable-cell trick is non-obvious, easy to get wrong, and
unreadable at the call site. `getAndRemove` states the intent in one word and
leaves room for hash-map implementations to do a genuinely single-traversal
override later.

## Proposed API

Added to `scala.collection.immutable.MapOps`:

```scala
def getAndRemove(key: K): (Option[V], C)
```

## Semantics

- key present: returns `(Some(value), map without the key)`
- key absent: returns `(None, this map unchanged)` — no copy is made
- the returned map has the receiver's own type `C`, like `removed`

## Design notes

- Default implementation is `get` + `removed` (two lookups); per-implementation
  single-pass overrides (`HashMap`'s CHAMP nodes can return both results from
  one descent) are the follow-up optimization that makes this a true primitive,
  which the review noted would be needed for the performance motivation to hold.

## Compatibility

Pure addition to `immutable.MapOps`; MiMa `ForwardsBreakingChanges` entry.

## Tests

`tests/run/get-and-remove.scala`: present/absent keys, receiver immutability,
receiver-typed results (SortedMap), and the work-queue usage pattern.
