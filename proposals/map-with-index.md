# SLC Proposal: `mapWithIndex`

**Status:** draft implementation as a basis for discussion — marked 🤔 (needs
stronger motivation) in STA review (item 4.5)
**Source:** https://github.com/scala/scala-library-next/issues/78

## Motivation

Mapping each element together with its position is a constant of rendering and
formatting code (numbered lists, alternating styles, positional encodings).
The canonical spelling `xs.zipWithIndex.map { case (a, i) => ... }` allocates
an intermediate tuple per element and forces a two-step reading of a one-step
idea.

**Addressing the review's challenge** (*"don't see the benefit over
`zipWithIndex` + `map` when we already suggest `.view`/`.iterator` for loop
fusion"*): the claim here is not performance — the view spelling indeed fuses —
but ergonomics: `mapWithIndex((a, i) => ...)` takes a two-parameter function
directly, avoiding both the tuple pattern-match boilerplate and the
easy-to-forget `.view ... .to(...)` round-trip. It is among the most
downloaded one-liners in every collections-extension library
(cats' `mapWithIndex`, scala-collection-contrib, ZIO Prelude), which is
evidence of demand. The review's alternative idea — a `lazyIndices` companion
for `lazyZip` — is complementary rather than competing and is not implemented
here.

## Proposed API

Added to `scala.collection.IterableOps`:

```scala
def mapWithIndex[B](f: (A, Int) => B): CC[B]
```

## Semantics

- returns the receiver's collection type `CC[B]`, like `map`
- indexing starts at zero, order is preserved, traversal is single-pass
- lazy on views (implemented via the existing `View` machinery)

## Compatibility

Pure addition to `IterableOps`; MiMa `ForwardsBreakingChanges` entry.

## Tests

`tests/run/map-with-index.scala`: basics, empty input, receiver-typed results,
zero-based ordering, equivalence with the `zipWithIndex` spelling, and view
laziness.
