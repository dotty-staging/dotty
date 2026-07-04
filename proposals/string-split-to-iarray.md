# SLC Proposal: `String.splitToIArray`

**Status:** draft implementation, approved HIGH priority in STA review (item 4.13,
proposed there as `splitToSeq`)
**Source:** https://github.com/scala/scala-library-next/pull/113

## Motivation

`"a,b,c".split(",")` is one of the most common string operations, and it hands the
caller a mutable `Array[String]`. Arrays are the odd one out in idiomatic Scala
code: they compare by reference, print unhelpfully, and are mutable, so careful
code ends up copying the result into a `Seq` (`split(...).toSeq`), paying an
allocation and losing the directness of the operation.

The original proposal returned `Seq[String]`. During review it was reworked to
return `IArray[String]`: if the motivation is avoiding exposed mutability, an
immutable array delivers that with **zero copying** — the freshly created array
from `String.split` is wrapped as-is — while keeping array-level performance
(compact storage, O(1) indexed access) and still offering the whole collections
API through `IArray`'s extension methods.

## Proposed API

Added to `scala.collection.StringOps`:

```scala
def splitToIArray(regex: String): IArray[String]
def splitToIArray(regex: String, limit: Int): IArray[String]
```

## Semantics

- exactly `java.lang.String.split(regex)` / `split(regex, limit)` semantics,
  including removal of trailing empty strings (no-limit form), the `limit`
  threshold meaning, and `PatternSyntaxException` on invalid patterns
- token order is preserved
- the result is wrapped with `IArray.unsafeFromArray` — safe here because
  `String.split` always returns a freshly allocated array that never escapes
  mutably

## Design notes

- **Deviation from the PDF proposal:** the return type is `IArray[String]`
  rather than `Seq[String]`, per review feedback. A `Seq` (`Vector`) return
  would copy every token array; `IArray` is free.
- The name follows the return type (`splitToIArray` rather than `splitToSeq`).
  Callers who want a `Seq` can write `.splitToIArray(...).toSeq`, which is no
  worse than today's `.split(...).toSeq`.
- Scala 2 cannot cross-build this method easily (`IArray` is Scala 3 only) —
  acceptable now that the library is maintained in the Scala 3 repository.

## Compatibility

Pure addition to `StringOps` (a value class — both the method and its
`$extension` form get MiMa `ForwardsBreakingChanges` filter entries).

## Tests

`tests/run/string-split-to-iarray.scala`: basic splitting, trailing/interior
empty-token behavior, both `limit` forms, regex separators, no-match case, and
pattern-error propagation.
