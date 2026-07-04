# SLC Proposal: `Seq.deleted` / `Seq.updatedWith` / `Seq.splitAround`

**Status:** draft implementation as a basis for discussion — marked 🤓 (needs
design discussion) in STA review (item 4.12)
**Sources:** https://github.com/scala/scala-library-next/issues/186,
https://github.com/scala/scala-library-next/issues/187

## Motivation

Three small index/element edits that today require `patch` arithmetic or
splitting gymnastics:

- **`deleted(i)`** — remove the element at an index. Today:
  `xs.patch(i, Nil, 1)`, which silently ignores out-of-range indices instead of
  failing like `updated`.
- **`updatedWith(i, f)`** — replace *or remove* the element at an index based
  on its current value, mirroring `Map.updatedWith`'s shape at an index.
  Today: `xs(i)` + `updated`/`patch` (two traversals, manual branching).
- **`splitAround(sep)`** — split at the first occurrence of a separator,
  excluding it: `"bucket/path/prefix".splitAround('/') == ("bucket", "path/prefix")`.
  Today: `span(_ != sep)` and then dropping the separator from the second half.

## Proposed API

Added to `scala.collection.SeqOps`:

```scala
def deleted(i: Int): C
def updatedWith[B >: A](i: Int, f: A => Option[B]): CC[B]
def splitAround[A1 >: A](separator: A1): (C, C)
```

## Semantics

- `deleted` and `updatedWith` throw `IndexOutOfBoundsException` for an
  out-of-range index, matching `updated` (and unlike `patch`)
- `updatedWith`: `Some(b)` replaces the element, `None` removes it
- `splitAround` splits around the *first* element equal to the separator and
  excludes it from both halves; later occurrences stay in the second half; if
  the separator is absent, the result is `(whole, empty)` (the `span` limit
  case)
- result types follow the existing family: `deleted` and the `splitAround`
  halves return `C`, `updatedWith` returns `CC[B]` (like `patch`/`updated`/`splitAt`)
- surrounding order is preserved

## Points from the review, for the SLC discussion

- *"Does `splitAround` add much over `span` + dropping an element from the
  second half?"* — functionally no; the offer is intent and the easy-to-botch
  boundary handling (the drop must not apply when the separator is absent —
  note `span`+`tail` is a runtime error in that case, `span`+`drop(1)` silently
  works but few write it confidently).
- The review's more general idea — projecting a `Seq` to a `MapView[Int, A]`
  and back — would subsume `deleted`/`updatedWith` and deserves its own design
  thread; these methods are the minimal direct form.

## Compatibility

Pure additions to `SeqOps`; three MiMa `ForwardsBreakingChanges` entries.

## Tests

`tests/run/seq-edits.scala`: deletion at all positions and out-of-range
failures, replace/remove via `updatedWith` with widening, and `splitAround`
first-occurrence/absent/boundary cases including the bucket-path example.
