# SLC Proposal: `zipStrict`

**Status:** draft implementation as a basis for discussion — marked 🤓 (needs
design discussion) in STA review (item 3.2). This implementation follows the
**reviewer's counter-design**, not the PDF signature.
**Source:** https://contributors.scala-lang.org/t/standard-library-now-open-for-improvements-and-suggestions/7337/24

## Motivation

Zipping two collections that *must* have the same length is a common
correctness requirement (parallel columns, paired samples, argument lists).
`zip` silently truncates to the shorter side, hiding bugs; `zipAll` pads them,
which is the opposite of what's wanted. `zipStrict` zips only if the lengths
match, and reports mismatch as `None` instead of throwing or truncating.

## The design discussion

The PDF proposed two variants: `Iterable.zipStrict: Option[CC[(A, B)]]`
(receiver's collection type) and `Iterator.zipStrict: Option[Seq[(A, B)]]`
(strict, since confirming equal length consumes both sides).

The review suggested instead: *use `knownSize`; if either side does not have a
known size, don't specialize to a `Seq` result but return
`Some[Iterator[(A, B)]]` — then it can be defined once on `IterableOnceOps`.*

This implementation follows the review design, with one refinement for the
unknown-size case (returning `None` whenever sizes are unknown would make the
method useless for `List`, whose `knownSize` is -1 — instead the elements are
buffered while verifying alignment):

## Proposed API

Added to `scala.collection.IterableOnceOps`:

```scala
def zipStrict[B](that: IterableOnce[B]): Option[Iterator[(A, B)]]
```

## Semantics

- returns `Some` iff both inputs have exactly the same length, `None` otherwise
  (never throws; two empty inputs yield `Some` of an empty iterator)
- **fast path:** when both sides have non-negative `knownSize`, sizes are
  compared without consuming anything; on a match the result is the *lazy*
  `iterator.zip(that)`, on a mismatch `None` is returned in O(1)
- **fallback:** when either size is unknown, both inputs are consumed in
  lockstep into a buffer; if they end together the buffered pairs are returned,
  otherwise `None`
- left-to-right pairing order; single definition covers collections *and*
  iterators (`$consumesIterator` applies to iterator receivers/arguments)

## Open questions for SLC discussion

- Result type: `Option[Iterator[(A, B)]]` (this design) vs the PDF's
  per-receiver `Option[CC[(A, B)]]` on `Iterable`. The iterator result is
  uniform and cheap, but callers wanting a collection must `.to(...)` it.
- Whether the buffering fallback is acceptable, or whether unknown-size inputs
  should simply return `None` (the literal reading of the review note — cheap,
  but surprising for `List`).

## Compatibility

Pure addition to `IterableOnceOps`; MiMa `ForwardsBreakingChanges` entry.

## Tests

`tests/run/zip-strict.scala`: knownSize fast path (Vector), consumption
fallback (List), mixed sides, empties, iterators, and pairing order.
