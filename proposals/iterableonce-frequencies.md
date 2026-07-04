# SLC Proposal: `IterableOnce.frequencies`

**Status:** draft implementation, approved HIGH priority in STA review (item 4.2)
**Source:** https://contributors.scala-lang.org/t/standard-library-now-open-for-improvements-and-suggestions/7337/7

## Motivation

Counting how often each value appears is a constant of everyday programming —
word counts, histogram buckets, duplicate detection, test assertions. Today the
canonical spelling is

```scala
xs.groupMapReduce(identity)(_ => 1)(_ + _)
```

which is a three-argument incantation for a one-word idea (and the naive
`xs.groupBy(identity).view.mapValues(_.size).toMap` materializes every group).
The review considered this boilerplate saving alone enough to justify the
method.

## Proposed API

Added to `scala.collection.IterableOnceOps`:

```scala
def frequencies[A1 >: A]: Map[A1, Int]
```

## Semantics

- single strict pass over the source; works on any `IterableOnce`, including
  iterators (which are consumed)
- repeated elements increment the corresponding count
- empty input returns an empty map
- the result is an unordered `immutable.Map` — a fixed result type, like
  `toMap` / `count`, which is why the method sits on `IterableOnceOps` rather
  than `IterableOps` (no collection-type recovery needed)
- the widened type parameter `A1 >: A` exists because `Map` is invariant in its
  key type while `IterableOnce` is covariant in `A`; it also allows explicitly
  widening the key type (`xs.frequencies[AnyVal]`)

## Design notes

- Implemented by accumulating into a `mutable.HashMap` and converting once at
  the end — one hash table, no intermediate groups.
- `groupMapReduce` lives on `IterableOps`; `frequencies` deliberately does not,
  so even one-shot sources get it (grouping-family cohesion was considered and
  rejected in the proposal since the result type is fixed).

## Compatibility

Pure addition to `IterableOnceOps`; MiMa `ForwardsBreakingChanges` filter entry.

## Tests

`tests/run/iterableonce-frequencies.scala`: lists, empty input, strings, sets,
iterator single-pass consumption, explicit key widening, and equivalence with
the `groupMapReduce` spelling.
