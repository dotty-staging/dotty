# SLC Proposal: `Map.invert`

**Status:** draft implementation as a basis for discussion — marked 🤔 (needs
stronger motivation) in STA review (item 4.9)
**Source:** https://contributors.scala-lang.org/t/standard-library-now-open-for-improvements-and-suggestions/7337/6

## Motivation

Swapping the direction of a map is a recurring need (index inversion,
reverse lookups, grouping by value), and doing it correctly means handling the
many-to-one case: several keys can share a value, so the inverted map's values
must be *sets* of original keys. The correct spelling today is the review's own
suggestion:

```scala
map.groupMap(_._2)(_._1).view.mapValues(_.toSet).toMap
```

which is three transformations, an intermediate per-value collection, and the
`.view ... .toMap` dance for what is conceptually one word. `invert` is the map
analogue of swapping tuple positions, but safe for many-to-one relationships.

## Proposed API

Added to `scala.collection.MapOps` (this branch implements the base flavor):

```scala
def invert[V1 >: V]: immutable.Map[V1, immutable.Set[K]]
```

## Semantics

- each value becomes a key of the result; original keys that share a value are
  collected into a `Set`
- single strict pass; the result is an unordered immutable `Map` regardless of
  receiver kind (defined on `collection.MapOps`, so mutable maps get it too,
  without mutation)
- `V1 >: V` widening is required because the value type moves into the
  invariant key position

## Flavor refinements (deferred, per the PDF)

The PDF also proposes per-flavor variants whose *grouped-set flavor mirrors the
receiver's*: `SeqMap → SeqMap[V1, SeqSet[K]]` and
`SortedMap → SeqMap[V1, SortedSet[K]]`. These cannot share a single `CC`-style
return type (inverting moves values into key position, and the receiver's
factory cannot order the new keys), hence explicit variants. They are **not**
in this branch:

- the `SeqMap` flavor is blocked on `SeqSet` (proposal 3.1, `stdlib/seq-set`)
- the `SortedMap` flavor is straightforward once the shape is agreed

The review also floated a more general idea — `iterableFactory`-style members
for the Map/Set/Seq flavors that preserve "sorted/ordered" properties — which
would subsume the per-flavor variants and belongs in the SLC design discussion.

## Compatibility

Pure addition to `MapOps`; MiMa `ForwardsBreakingChanges` entry.

## Tests

`tests/run/map-invert.scala`: many-to-one grouping, empties, injective maps,
explicit widening, mutable receivers, and a reverse-lookup use case.
