# SLC Proposal: `groupFlatMap`

**Status:** draft implementation as a basis for discussion — marked 🤔 (needs
stronger motivation) in STA review (item 3.4)
**Source:** https://github.com/scala/scala-library-next/issues/135

## Motivation

2.13 added the grouping family `groupBy` / `groupMap` / `groupMapReduce`,
mirroring `identity` / `map` / `map`+`reduce` per group. The `flatMap`
counterpart is missing: grouping elements while expanding each into zero or
more outputs currently requires `groupMap(...)(...).view.mapValues(_.flatten).toMap`
— an extra full pass, intermediate nested collections, and the `.view ... .toMap`
dance for something the grouping pass could do directly.

Concrete shapes: grouping log lines by source while splitting each line into
tokens; grouping orders by customer while expanding each order into its items;
any "group by key, then concatenate the per-element expansions" aggregation.

This is a *family-completeness* argument (the same one that justified
`groupMap` itself): `map : flatMap :: groupMap : groupFlatMap`.

## Proposed API

Added to `scala.collection.IterableOps` (next to `groupMap`):

```scala
def groupFlatMap[K, B](key: A => K)(f: A => IterableOnce[B]): immutable.Map[K, CC[B]]
```

## Semantics

- groups by the derived key; each input element expands into zero or more
  outputs appended to its group, preserving encounter order inside each group
- the group value type `CC[B]` matches `groupMap`
- the result is a plain unordered `immutable.Map`, matching `groupMap`
- defined on `IterableOps` (not `IterableOnceOps`): the group value is the
  receiver's own collection type, which is only meaningful for a reusable
  collection — same placement rationale as `groupMap`
- single strict pass; an element whose expansion is empty still forces its
  key's group to exist (consistent with "appends zero elements")

Note: an element with an empty expansion *does* create its group (with no
elements added). This matches the behavior of the one-pass builder
implementation and mirrors `groupByOrderedOpt`'s treatment in item 4.1; if the
SLC discussion prefers key-suppression for empty expansions, that is a one-line
change.

## Compatibility

Pure addition to `IterableOps`; MiMa `ForwardsBreakingChanges` entry.

## Tests

`tests/run/group-flat-map.scala`: expansion + grouping, empty expansions,
in-group ordering, receiver-typed group values, empty input, and the
`groupMap`+`flatten` equivalence.
