# SLC Proposal: `mutable.Map.merge`

**Status:** draft implementation, approved HIGH priority in STA review (item 4.8)
**Source:** https://github.com/scala/scala-library-next/issues/164
**Motivating case:** https://github.com/zio/zio-kafka/pull/1079

## Motivation

"Insert this value, or combine it with what's already there" is the workhorse
of in-place counting, grouping and aggregation. Scala's mutable maps offer only
`updateWith`, which forces the caller through `Option` plumbing for a case that
can never remove anything:

```scala
map.updateWith(key) { case Some(v) => Some(v max offset); case None => Some(offset) }
```

Java has had `Map.merge` since Java 8 for exactly this. The concrete motivation
in the proposal: zio-kafka had to fall back to a `java.util.HashMap` in its
consumer runloop *purely* to get `merge` while accumulating maximum offsets per
partition (`acc.merge(tp, offset, _ max _)`), forcing Java-collection interop
into otherwise idiomatic Scala code.

## Proposed API

Added to `scala.collection.mutable.MapOps`:

```scala
def merge(key: K, value: V, remappingFunction: (V, V) => V): V
```

## Semantics

- if `key` is absent, inserts `value`
- if `key` is present with value `v`, replaces it with `remappingFunction(v, value)`
  (existing value first, new value second — matching `java.util.Map.merge`)
- **never removes**: unlike Java's `merge`, which deletes the entry when the
  function returns `null`, the function returns a plain `V` — "we don't do
  nulls in Scala". Removal remains the job of `updateWith`.
- if the remapping function throws, the exception propagates and the map is
  left unchanged
- returns the value associated with the key after the operation
- `V` is invariant on mutable maps, so no `[V1 >: V]` widening is needed

## Design notes / open question

- **Return type.** The PDF proposed `Option[V]`, mirroring Java's nullable
  return. Since this `merge` never removes, the result would always be `Some`,
  so this implementation returns `V` directly (the review response had flagged
  the `Option[V]` return as needing clarification). This is the one point that
  should be settled explicitly in the SLC discussion.
- Implemented once on the `mutable.MapOps` trait in terms of `get`/`update`, so
  every implementation (`HashMap`, `TreeMap`, `LinkedHashMap`, …) gets it.
  Hash-map-specific single-lookup overrides are a follow-up optimization,
  as was done for `updateWith`.
- `concurrent.Map` implementations inherit the default, which is *not* atomic;
  if this proposal advances, an atomic override for `TrieMap` (CAS loop, like
  its `updateWith` override) should be included.

## Compatibility

Pure addition to `mutable.MapOps`; MiMa `ForwardsBreakingChanges` filter entry.

## Tests

`tests/run/mutable-map-merge.scala`: counting, return values on both branches,
exception transparency, the zio-kafka max-offset pattern, and behavior across
`LinkedHashMap`/`TreeMap`.
