# SLC Proposal: `mapAccumulate`

**Status:** draft implementation as a basis for discussion — marked 🤔 (needs
stronger motivation) in STA review (item 4.3)
**Source:** https://contributors.scala-lang.org/t/standard-library-now-open-for-improvements-and-suggestions/7337/46

## Motivation

"Map, but with a bit of state along the way" — running totals, unique-label
assignment, positional encodings that depend on what came before. The pattern
sits awkwardly between `map` (no state) and `foldLeft` (state, but you must
rebuild the collection yourself).

**Addressing the review's challenge** (*"the only difference to a `foldLeft`
with a pair for state is that you don't need to pattern match, and the builder
is chosen automatically"*): that is exactly the offer, and it is worth more
than it sounds. The `foldLeft` spelling costs: a tuple allocation per element,
a `case ((acc, s), a) =>` pattern match, a manually prepended list that must be
reversed (or an explicit builder), and the reader must reverse-engineer that a
*map* is happening at all. `mapAccumulate` says it in the name, in one pass,
returning `(CC[B], S)` with the right collection built automatically. The same
operation exists as `mapAccumL` in Haskell, `mapAccumulate` in cats,
`mapAccum` in ZIO — the recurrence across ecosystems is the motivation.

## Proposed API

Added to `scala.collection.IterableOps`:

```scala
def mapAccumulate[B, S](z: S)(f: (A, S) => (B, S)): (CC[B], S)
```

## Semantics

- returns the mapped elements first and the final state second
- the mapped collection has the receiver's element-collection type `CC[B]`, like `map`
- preserves encounter order; runs strictly in a single pass
- empty input returns an empty collection and the initial state unchanged

## Compatibility

Pure addition to `IterableOps`; MiMa `ForwardsBreakingChanges` entry.

## Tests

`tests/run/map-accumulate.scala`: running totals, counter state, empty input,
receiver-typed results, ordering, and the explicit `foldLeft` equivalence for
comparison.
