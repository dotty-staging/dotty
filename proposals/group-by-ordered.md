# SLC Proposal: `groupByOrdered` / `groupByOrderedOpt`

**Status:** draft implementation as a basis for discussion — marked 🤓 (needs
design discussion) in STA review (item 4.1)
**Source:** https://github.com/scala/scala-collection-contrib/issues/252
**Prior art:** https://github.com/sake92/squery (seqUtils.scala)

## Motivation

`groupBy` returns an unordered `Map`, destroying the input's encounter order.
The motivating workload is re-nesting the flat, denormalized rows a SQL query
returns into an ordered `parent -> Seq(children)` structure that mirrors the
queried relational data: queries use `ORDER BY`, and the grouped output should
preserve that order (e.g. because it becomes an ordered JSON array). The
methods do not perform a join — they collapse rows the database already joined.

Pick the overload matching the join that produced the rows:

- `groupByOrdered(key)` — keep whole rows as the group value
- `groupByOrdered(key, value)` — **inner join**: every row carries a real value
- `groupByOrderedOpt(key, value)` — **left/full outer join**: the extractor
  yields `None` for a `NULL` child; the parent key is still created (with an
  empty group), so childless parents appear

## Proposed API

Added to `scala.collection.IterableOps`:

```scala
def groupByOrdered[K](extractKey: A => K): immutable.SeqMap[K, C]
def groupByOrdered[K, V](extractKey: A => K, extractValue: A => V): immutable.SeqMap[K, CC[V]]
def groupByOrderedOpt[K, V](extractKey: A => K, extractValue: A => Option[V]): immutable.SeqMap[K, CC[V]]
```

## Semantics

- keys appear in encounter (first-seen) order; values inside each group
  preserve input order; repeated keys append to the existing group
- result is a `SeqMap`; group values mirror `groupBy` (`C`, whole elements) and
  `groupMap` (`CC[V]`, extracted values)
- strict, single pass; empty input returns an empty `SeqMap`
- defined on `IterableOps` (not `IterableOnceOps`): the group value is the
  receiver's own collection type

## The review's design alternative (open discussion)

The review suggested a more flexible shape: make materialization *lazy* and
have the caller supply a `Factory`/`BuildFrom` (inferred from the result type),
so the ordered behavior could be insertion order **or** sorted-by-key — a
generic `lazyGroup` primitive of which `groupByOrdered` is one instantiation.
That design also surfaced in the review of 4.9 (`invert`). This branch
implements the PDF's direct strict form as the discussion baseline; if the
lazy/`BuildFrom` direction wins, these signatures become thin sugar over it.

## Compatibility

Pure additions to `IterableOps`; MiMa `ForwardsBreakingChanges` entries
(`groupByOrdered`, `groupByOrderedOpt`).

## Tests

`tests/run/group-by-ordered.scala`: key encounter order, in-group input order,
all three overloads mapped to their join cases (including empty groups for
childless parents), receiver-typed group values, empty input, and agreement
with `groupBy` up to ordering.
