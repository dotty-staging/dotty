# SLC Proposal: `runsWith` / `runsBy` / `matchingRuns`

**Status:** draft implementation, approved MEDIUM priority in STA review (item 4.6;
review asked that the motivation include a typical data problem it solves — see below)
**Source:** https://github.com/scala/scala-library-next/issues/30

## Motivation

Grouping *adjacent* elements into maximal runs is the ordered cousin of
`groupBy`, and today it requires manual state tracking (a fold carrying "current
key + current buffer + completed groups") that is easy to get subtly wrong.

**Typical data problems this solves directly:**

- *Burst detection in logs*: `log.runsBy(_.severity)` turns an ordered log into
  consecutive `INFO`/`ERROR` bursts — e.g. "find error bursts longer than 10
  entries" becomes `log.runsBy(_.severity).filter { case ("ERROR", run) => run.sizeIs > 10; case _ => false }`.
- *Segmenting time series*: `readings.runsWith(_.value > threshold)` splits a
  sensor stream into alternating above/below-threshold stretches;
  `matchingRuns` keeps only the above-threshold episodes.
- *Run-length encoding / `uniq`*: `xs.runsBy(identity).map((k, r) => (k, r.size))`
  is RLE; `.map(_._1)` collapses consecutive duplicates.

`grouped` and `sliding` already partition by *count*; this family partitions by
*content*, which the review noted is the missing generalization.

## Proposed API

Added to `scala.collection.IterableOps`:

```scala
def runsBy[B](f: A => B): Iterator[(B, C)]
def runsWith(p: A => Boolean): Iterator[C]
def matchingRuns(p: A => Boolean): Iterator[C]
```

## Semantics

- a new run starts when the discriminator result (`runsBy`) or predicate result
  (`runsWith`) changes; runs are maximal and preserve element order
- each run is the receiver's own collection type `C`; runs are produced by a
  lazy outer `Iterator`, exactly like `grouped`/`sliding`, so unbounded sources
  work as long as each individual run is finite
- `runsBy` pairs each run with its discriminator value (so callers don't
  recompute it); `runsWith(p)` ≡ `runsBy(p).map(_._2)`;
  `matchingRuns(p)` keeps only the runs whose elements satisfy `p`
- concatenating the runs of `runsBy`/`runsWith` reassembles the input
- empty input yields an empty iterator
- **not offered on `Iterator`** (per the PDF): an iterator-native version would
  have to yield a concrete `Seq` instead of `C`, and the `IterableOps` version
  already provides the lazy outer behavior

## Design notes

- Implemented with one lookahead element and `newSpecificBuilder` per run; the
  discriminator is evaluated exactly once per element.
- `runsBy` returning the key alongside the run is a small extension over the
  PDF signature `runsBy[B](f: A => B): Iterator[(B, C)]` — actually the PDF
  already includes the key; kept as proposed.

## Compatibility

Pure additions to `IterableOps`; three MiMa `ForwardsBreakingChanges` entries.

## Tests

`tests/run/runs.scala`: all three methods, alternation of `runsWith`, run
reassembly, empty/singleton inputs, own-collection-type results, the log-burst
data problem, RLE/uniq, and lazy consumption from an unbounded `LazyList`.
