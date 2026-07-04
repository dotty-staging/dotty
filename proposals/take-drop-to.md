# SLC Proposal: `takeTo` / `dropTo`

**Status:** draft implementation, approved HIGH priority in STA review (item 4.4;
reviewer: "many times this is what I wanted take/dropWhile to do")
**Source:** https://github.com/scala/scala-library-next/issues/29

## Motivation

`takeWhile` and `dropWhile` cut a collection at the first element that fails a
predicate — *excluding* that element from the taken prefix. Very often the
natural phrasing of a task is the inclusive one: "read up to **and including**
the terminator".

- take lines up to and including the `"END"` marker
- consume tokens through the closing brace
- drop log entries through the last restart marker

Today that needs an off-by-one workaround. `xs.takeWhile(_ != end)` loses the
terminator; `xs.span(_ != end)` plus re-attaching the head of the second half is
verbose, allocates both halves, and — crucially — cannot be expressed at all on
a single-pass source (`span` on an `Iterator` cannot be consumed one side after
the other without buffering).

## Proposed API

On `scala.collection.IterableOps` and `scala.collection.Iterator`:

```scala
def takeTo(p: A => Boolean): C
def dropTo(p: A => Boolean): C          // Iterator[A] on Iterator
```

## Semantics

- `takeTo(p)` returns everything up to and **including** the first element
  satisfying `p`; if no element matches, the whole input
- `dropTo(p)` returns everything **after** the first element satisfying `p`,
  excluding it; if no element matches, empty
- `takeTo(p) ++ dropTo(p)` reassembles the input (for reusable collections)
- results use the receiver's own collection type, like `takeWhile`/`dropWhile`
- encounter order is preserved; single-pass sources are supported via the
  `Iterator` versions, and `takeTo` never reads past the terminator, so it
  works on unbounded sources (`Iterator.from(1).takeTo(_ == 3)`)

## Design notes

- The collection versions are defined on `IterableOps` (not `IterableOnceOps`,
  where `takeWhile` is abstract and adding a member would burden every
  implementor); single-pass support comes from the `Iterator` versions,
  mirroring how the `zip` family is layered.
- Implemented lazily via `View.fromIteratorProvider` over the iterator
  versions, so views and `LazyList` stay lazy. Productionization follow-ups if
  accepted: dedicated `View.TakeTo`/`View.DropTo` classes and
  strict-optimized overrides, mirroring `takeWhile`'s.
- Naming: `-To` suffix reads as "through the terminator". `takeUntil`/
  `takeThrough` were alternative spellings discussed in the sources; `-To`
  matches the proposal.

## Compatibility

Pure additions; MiMa `ForwardsBreakingChanges` entries for
`IterableOps.takeTo/dropTo` and `Iterator.takeTo/dropTo`.

## Tests

`tests/run/take-drop-to.scala`: inclusion/exclusion at the terminator,
reassembly, no-match behavior, first-match-wins, empty input, own-type results,
iterator laziness and non-consumption past the terminator, and unbounded
sources (`Iterator.from`, `LazyList.from`).
