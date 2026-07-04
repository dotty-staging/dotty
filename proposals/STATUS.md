# STA Standard Library Proposals — Status

Tracking document for the STA stdlib enhancement proposals
(PDF: *STA Proposal: Scala Standard Library Enhancements*; review notes:
[gist](https://gist.github.com/bishabosha/ad919c779b8f07feabf84d75f8c62f78)).

Maintained on the `collection-api-proposals` branch. One implementation branch
per proposal, each off `main`, each containing: the library change, a MiMa
`ForwardsBreakingChanges` filter entry, a `tests/run/*.scala` test, and a
`proposals/*.md` feature document written to seed the SLC forum post.

**Approval legend** (from review): ✅ approved (with priority) · 🤓 needs design
discussion · 🤔 needs stronger motivation. Items marked 🤓/🤔 are implemented as
a *basis for discussion*, incorporating the review feedback into the design.

| # | API | Approval | Implementation | Branch | Notes / deviations |
|---|-----|----------|----------------|--------|--------------------|
| 2 | `Vector` vs `List` performance report | ✅ HIGH | not started | — | Deliverable is a report, not an API. Review: Martin sceptical it helps the compiler; profile typical `List` sizes and `mapConserve` patterns. |
| 3.1 | `SeqSet` / `VectorSet` | ✅ MEDIUM | planned | `stdlib/seq-set` | New insertion-ordered immutable set, mirroring `SeqMap`/`VectorMap`. Largest item; also unblocks the `SeqMap` flavor of 4.9. |
| 3.2 | `zipStrict` | 🤓 discuss | planned | `stdlib/zip-strict` | Design revised per review: on `IterableOnceOps`, returns `Option[Iterator[(A, B)]]`; `knownSize` fast path (lazy result when sizes known equal, `None` when known unequal), buffering fallback otherwise. |
| 3.3 | `lazyZipAll` | ✅ LOW | planned | `stdlib/lazy-zip-all` | Lazy padding counterpart of `zipAll`, returns `LazyZip2`. |
| 3.4 | `groupFlatMap` | 🤔 motivation | planned | `stdlib/group-flat-map` | Grouping counterpart of `flatMap`, completes the `groupMap` family. |
| 4.1 | `groupByOrdered` (+ `Opt` overload) | 🤓 discuss | planned | `stdlib/group-by-ordered` | Strict `SeqMap`-returning version per PDF; review's lazy `Factory`/`BuildFrom` alternative documented as open design question. |
| 4.2 | `IterableOnce.frequencies` | ✅ HIGH | **done** | `stdlib/frequencies` | Single pass via one `mutable.HashMap`; on `IterableOnceOps` (fixed result type), `A1 >: A` for key invariance. |
| 4.3 | `mapAccumulate` | 🤔 motivation | planned | `stdlib/map-accumulate` | Review: near-equivalent to `foldLeft` with pair state; doc addresses what it adds (no pattern matching, builder chosen automatically). |
| 4.4 | `takeTo` / `dropTo` | ✅ HIGH | **done** | `stdlib/take-drop-to` | On `IterableOps` + `Iterator` (not `IterableOnceOps`, mirroring zip-family layering). `takeTo` never reads past the terminator; unbounded sources work. View classes/strict overrides = follow-up. |
| 4.5 | `mapWithIndex` | 🤔 motivation | planned | `stdlib/map-with-index` | Review: unclear benefit over `zipWithIndex` + `map` with view fusion; doc addresses this. `lazyIndices` idea noted for 3.3/lazyZip context. |
| 4.6 | `runsWith` / `runsBy` / `matchingRuns` | ✅ MEDIUM | planned | `stdlib/runs` | Review asks for a typical data problem in the motivation — doc includes one. Lazy outer `Iterator[C]` like `grouped`; intentionally not on `Iterator`. |
| 4.7 | `Map.getAndRemove` | 🤔 motivation | planned | `stdlib/get-and-remove` | Review: expressible via `updatedWith` + cell; doc argues readability/single-lookup. On `immutable.MapOps`. |
| 4.8 | `mutable.Map.merge` | ✅ HIGH | **done** | `stdlib/mutable-map-merge` | **Deviation:** returns `V`, not the PDF's `Option[V]` (merge never removes, so it would always be `Some`) — flagged as the open question. TrieMap atomic override listed as required follow-up. |
| 4.9 | `Map.invert` | 🤔 motivation | planned | `stdlib/map-invert` | Base `Map` flavor only (`immutable.Map[V1, Set[K]]`); `SeqMap`→`SeqSet` flavor blocked on 3.1; `SortedMap` refinement documented. Review's `groupMap` equivalence + factory-flavours idea in doc. |
| 4.10 | `Map.unionWith` (+ `unionWithOption`) | ✅ HIGH | **done** | `stdlib/union-with` | Uncurried `(that, f)` matching existing `IntMap`/`LongMap.unionWith` precedent (kept as faster overload). `unionWithOption` = reviewer-requested key-removing variant; its name is an open question. |
| 4.11 | `Set.fullIntersection` | 🤔 motivation | planned | `stdlib/full-intersection` | Review asked upstream for motivation; possibly scala-collection-contrib material. Implemented single-pass-per-side as discussion basis. |
| 4.12 | `Seq.deleted` / `updatedWith` / `splitAround` | 🤓 discuss | planned | `stdlib/seq-edits` | Review: `splitAround` ≈ `span` + drop; `MapView[Int, A]` projection idea noted in doc. |
| 4.13 | `String.splitToSeq` | ✅ HIGH | **done** | `stdlib/split-to-iarray` | **Deviation:** renamed `splitToIArray`, returns `IArray[String]` per review (immutability with zero copy). Exact `String.split` semantics. |
| 4.14 | `scala.math.clamp` | ✅ HIGH | **done** | `stdlib/clamp` | 5 overloads incl. `(Long, Int, Int): Int` and `Ordering`-based. Matches Java 21 `Math.clamp` semantics (NaN value passes through, NaN bound throws, IAE when `lower > upper`); hand-implemented (min JDK < 21). |
| — | Concurrent `HashSet` (Jamie) | unscoped | not started | — | Added during review session 1; needs a written proposal. |
| — | Low-allocation `HashMap`/`HashSet` for frequently-cleared caches (Jamie) | unscoped | not started | — | Added during review session 1; needs a written proposal. |

## Verification recipe (per branch)

```
./project/scripts/sbt "scala-library-bootstrapped/clean; scala-library-bootstrapped/compile; scala-library-bootstrapped/mimaReportBinaryIssues"
./project/scripts/sbt "scala3-bootstrapped/testCompilation <test-name>"
```

All **done** branches pass all three gates.

## Process reminder

Per `docs/_docs/contributing/procedures/contributing-to-stdlib.md`, each API
change needs an `SLC:`-prefixed thread on contributors.scala-lang.org and two
approvals from the stdlib working group before merging.
