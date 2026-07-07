# Data Literals for Scala — evaluation and synthesized proposal

A synthesis of the collection-literals Pre-SIP, the aggregate-literals Pre-SIP, and scala-object-notation, aimed at DSLs where bulk data and configuration definition currently require a lot of boilerplate — Mill `build.mill` files being the running example.

Sources:

- [Pre-SIP: A syntax for collection literals][cl] (thread 6990, 262 posts, Jan 2025 – Jul 2026), opened by Martin Odersky
- [Pre-SIP: A syntax for aggregate literals][al] (thread 6697, 285 posts, Jul 2024 – Jan 2025), opened by Matthias Berndt (mberndt)
- [scala-object-notation][scon] (SCON) by Jamie Thompson (bishabosha)

Citation convention: `[username, CL #n]` is post *n* in the collection-literals thread, `[username, AL #n]` post *n* in the aggregate-literals thread. Post links resolve as `<thread-url>/<n>`.

---

## Part 1 — Evaluation of the three sources

### 1.1 Collection literals (thread 6990)

The original proposal ([odersky, CL #1][cl-1]): `[a, b, c]` as expression syntax. With an expected type `E`, elaboration goes through a typeclass in `scala.compiletime`:

```scala
trait ExpressibleAsCollectionLiteral[+Coll]:
  type Elem
  inline def fromLiteral(inline xs: Elem*): Coll
```

With no expected type, the literal defaults to `immutable.Seq` — except when every element has the literal tree shape `a -> b`, in which case it defaults to `immutable.Map`. Implemented behind `language.experimental.collectionLiterals`.

**The technical core holds up.** Recursive expected-type propagation into literal elements is the one part libraries cannot replicate. ghik showed ([CL #147][cl-147]) that a library `&(...)` fails on nesting — `val e: List[(Int, Vector[Int])] = &(1 -> &(1, 2, 3))` does not compile, because Scala's inference propagates expected types only one level down. This settled the "just do it as a library" argument; mberndt, its main advocate, conceded ([CL #148][cl-148]). The inline-varargs typeclass is also the right shape: instances may be macros and can construct target collections without an intermediate varargs `Seq`.

**Why the thread went badly.** Three separable objections, visible in the like-counts (the highest-liked posts are almost all critical):

1. **The untargeted default.** `val a = [1, 2, 3]` being `Seq` drew the strongest reactions: opacity at definition sites (`assertEquals(a, Seq(1,2,3))` — [tgodzik, CL #34][cl-34]); "types randomly materializing depending on which givens happen to be in scope is exactly why implicit conversions got a bad name" ([Ichoran, CL #16][cl-16]); "the return of the infamous `CanBuildFrom` in a sheep's skin" ([eed3si9n, CL #32][cl-32], 34 likes, the most-liked post in the thread); the mutable-target trap, where a literal silently satisfies a mutable parameter that `Seq(1,2,3)` would have rejected ([nikitaga, CL #104][cl-104]); and overload-resolution complexity — tarsa noted that C# ships no untargeted default and considers that the right design ([CL #119][cl-119]). Notably, several of these arguments describe a given-driven default; the proposed default was actually a fixed rule. Part 2 returns to this distinction.
2. **The `->` shape detection for maps.** Uniformly rejected: making semantics depend on subtree shape is unprecedented in Scala and doesn't generalize to zero elements (`["a" -> 1]` is a `Map` but `[]` is a `Seq`) ([sjrd, CL #24][cl-24], 18 likes). Odersky dropped it ([CL #36][cl-36]).
3. **Fragmentation.** sjrd's criterion ([CL #115][cl-115], 33 likes): a new feature must be "(almost) always the best choice when applicable" — `enum` and `extension` pass; optional-choice features split the language into styles. An untargeted `[1,2,3]` competing with `Seq(1,2,3)` is such a split ([SethTisue, CL #39][cl-39], 29 likes; [djspiewak, CL #55][cl-55], 27 likes). sjrd also observed ([CL #124][cl-124]) that people write `val x = Seq(1,2,3)` — there is no repetition to remove — while the ascribed literal form `val x: Set[Int] = [1,2,3]` is longer than what it replaces.

A further process point: tooling. djspiewak ([CL #33][cl-33], 33 likes) and eed3si9n ([CL #32][cl-32]) argued that syntax SIPs must ship tested editor-parser support (tree-sitter, IntelliJ, Metals); fanf proposed gating stabilization on it ([CL #156][cl-156]).

**The visible compromise.** tarsa's staged plan ([CL #255][cl-255]): ship target-typed-only collection literals first (no untargeted default, so no default-type controversy and little overloading ambiguity), and defer untargeted defaults, possibly indefinitely — "Stage 1 would bring the vast majority of benefits with minimal controversy." This matches the C# design and echoes Ichoran's earliest suggestion ([CL #16][cl-16]).

**The DSL evidence.** Odersky: "Collection literals would be a big help in approachable DSLs for tooling. For instance, I was told that they would be great for simplifying Mill build scripts" ([CL #36][cl-36]). lihaoyi's four use cases ([CL #67][cl-67]) — Mill `moduleDeps`/`ivyDeps`, ujson literals, os-lib subprocess argv, requests-scala query params — are all positions that already have a target type. His corpus grep ([CL #217][cl-217]) found that small (0–2 element) collection literals are pervasive, sit at target-typed positions, and reference enclosing-scope values, so they cannot move to data files. The opposing view — bulk data belongs in files ([satorg, CL #57][cl-57]; [fanf, CL #31][cl-31]; [channingwalton, CL #118][cl-118]) — against lihaoyi's "there is a real cost for introducing a separate-file and separate-language barrier: you lose type safety, editor support, performance" ([CL #67][cl-67]) was not resolved in the thread; §2.5 addresses it directly.

### 1.2 Aggregate literals (thread 6697)

The original proposal ([mberndt, AL #1][al-1]): `[…]` as sugar for the expected type's companion `apply`, positional and named, recursive — `val ints: List[Int] = [1,2,3]`, `List[Person](["Martin", [1958, 9, 5]], …)`, `callFoo([[42]])`. Later refined to a `#` companion-object placeholder (`#(1,2,3)`, `#.fromNanos(42)`).

**Why it was rejected.** Odersky's names argument ([AL #119][al-119], 10 likes): "Names are usually really valuable… Programmers will very often use the shortest form available," so a feature that erases names will be over-used. Ichoran's genetics example (`[["CF512", [[II, [["rrf-3", ["b", 26]]] …` — "number and string soup", [AL #53][al-53]) and lrytz's assessment ("beneficial only for the ~5% of time spent writing… the impact would be more severe than implicits", [AL #44][al-44]) made the concern concrete. Both the general `[…]`-for-any-`apply` and the `#` placeholder were rejected ([AL #194][al-194]; mberndt's own summary, [AL #234][al-234]). soronpo also demonstrated a technical failure: with overloads there is no single expected type, so the elaboration is ambiguous ([AL #256][al-256]; conceded in [AL #257][al-257]).

**What survived.** The most-liked post in the thread was bishabosha's suggestion to use tuple syntax ([AL #4][al-4], 17 likes), and by the collection-literals thread both camps had converged on a version of it: named-tuple literals interpreted as case-class constructors when the target type is a case class. lihaoyi pointed out the reversal ([CL #79][cl-79]); Odersky adopted it ("The notation is too nice to just throw away", [CL #80][cl-80]), implemented it, and said he would file it as an amendment to the named-tuples SIP ([CL #94][cl-94]); Ichoran, previously the strongest critic of positional aggregates, called the named form "brilliant… strictly better" than positional construction ([CL #158][cl-158]). It is compatible with the names argument for a simple reason: a record literal carries its own names. It trades one class name for *n* field names, which in config-shaped code is usually an information gain.

Two rules Odersky fixed that should carry over ([CL #137][cl-137]): no subtyping and no implicit conversion between named tuples and classes — this is target-type interpretation of literals only; and the interpretation is recursive ("we interpret named tuple literals depending on the target type. That's a general principle").

One motivation from this thread that named tuples alone cannot satisfy: default arguments. Named tuples have no defaults and require all fields; desugaring a record literal to a real constructor call inherits defaults for free ([mberndt, AL #12][al-12]). For K8s/OpenAPI/build-config schemas with dozens of optional fields, this is the difference between usable and unusable.

### 1.3 scala-object-notation (SCON)

[SCON][scon] demonstrates that the combined notation — named-tuple record literals, sequence literals, Scala scalar literals — is a complete typed data language. A hand-rolled tokenizer and schema-directed streaming decoder (`Reader[T]`/`Writer[T]` over a first-class `RawSchema`, Mirror-derived for case classes and enums, no macros in core) decode documents that are themselves valid Scala source, with error paths indexed to source positions (`.database.host`, `.items[1]`). It ships `[1, 2, 3]` sequence syntax behind the same opt-in the compiler uses (`import language.experimental.collectionLiterals` at the top of the document) and tracks [SIP-72 dedented string literals][sip72] the same way, keeping the data dialect aligned with experimental Scala. It was presented in the CL thread ([bishabosha, CL #246][cl-246]); lihaoyi's response: "it would be nice if we could get this notation into Scala itself in a typesafe manner rather than as an external DSL" ([CL #247][cl-247]).

Three design points from SCON that the Pre-SIPs lacked:

1. **A closed grammar.** No constructors, no references, `(value)` grouping rejected: "the document is clearly raw data, not executable code", generic tooling needs no symbol resolution, and schema derivation stays structural and predictable (README, "Why The Syntax Is Narrow").
2. **Opt-in per document** via the standard language-import mechanism.
3. **The same notation works in-code and out-of-code** — compiled and target-typed on one side, decoded at runtime with source positions on the other. This addresses the "bulk data belongs in files" vs "separate-language barrier" disagreement from §1.1: if the file format is the literal notation, data moves across the code/file boundary without translation in either direction.

---

## Part 2 — The synthesized proposal: Data Literals

The proposal is one feature: collection literals plus record literals, both elaborated from the expected type, recursively, with a fixed spec-level default when no expected type exists — together with a normative data-notation subset for external tooling.

### 2.1 Design principles

- **P1 — A literal's type is never chosen by scope.** With an expected type, elaboration is typeclass-directed (§2.2.1) and the type is written at the position. With no usable expected type (none at all, `Any`/`AnyRef`, or an underspecified type variable), the literal is `scala.collection.immutable.Seq`. This default is fixed by the language specification, in the same way that the untargeted type of `1` is `Int` and of `1.0` is `Double`; no given search is involved. The distinction matters for the objections in §1.1: what made implicit conversions and `CanBuildFrom` unpopular is types arriving from ambient scope ([Ichoran CL #16][cl-16]; [eed3si9n CL #32][cl-32]), and a specification-level default is not that. Scala's numeric and character literals have always worked this way; Odersky raised the analogy in [CL #122][cl-122] but the thread did not pursue it. The `->`-shape `Map` default stays dropped (P2): one default, for one syntactic form.

  Stated once for both regimes this proposal covers: a collection literal is data; it becomes a typed value against a schema — the expected type in compiled Scala, a `Reader[T]` at decode time — and where no schema exists, its type is fixed by specification.

  **Severability.** The untargeted default is the contested part of this design (§2.4), so the proposal is factored so that the default can be removed without touching anything else. The target-typed-only subset ([tarsa CL #255][cl-255]) and import-declared defaults (§2.5.1) are documented fallback positions, not alternative architectures. The trade-off should be weighed before stabilization: a shipped default cannot be retracted without breakage, a withheld one can be added later.
- **P2 — No shape-based semantics.** Element trees are never inspected. Maps get no special syntax (§2.2.2).
- **P3 — Library-author opt-in.** Which types can be literal targets is decided by typeclass instances (collections) or by being a case class (records). This keeps Odersky's position from both threads ([CL #1][cl-1]; [AL #119][al-119], [AL #194][al-194]) that construction shortcuts should be sanctioned by the library author, while record-literal field names answer the names argument directly.
- **P4 — The literal grammar is a closed data notation.** Specified as a standalone appendix so external tools can parse it without a compiler; SCON is the reference implementation (§2.5).

### 2.2 Specification

#### 2.2.1 Sequence literals

Grammar as in [CL #1][cl-1]:

```
SimpleExpr ::= … | '[' ExprInParens {',' ExprInParens} ']'
```

With expected type `E`, resolve `ExpressibleAsCollectionLiteral[E]` (typeclass unchanged from [CL #1][cl-1]; `fromLiteral` is `inline` with inline varargs, so instances may be macros and can construct without an intermediate `Seq`). Each element is type-checked with expected type `Elem`, recursively — the part libraries cannot provide ([ghik, CL #147][cl-147]). `[]` is legal wherever a target exists.

The default rule (P1):

- **No usable expected type** — defined as in [CL #1][cl-1], following implicit-search practice: no expected type at all, a wildcard, `Any`, `AnyRef`, or a type variable not usefully constrained from above. The literal is then `scala.collection.immutable.Seq[T]` with `T` the lub of the elements; `[]` is `Seq[Nothing]`. The default is fixed by the language specification; no given search occurs. An `AnyVal` expected type falls under this rule and then fails conformance, with a dedicated error message.
- **With expected type `E`**: resolve the typeclass as above. Ambiguous instances are a compile error, resolved by ascription. Overloads that do not agree on a parameter type give the literal argument its default type, and resolution proceeds normally — deterministic, and it avoids the ambiguity failure soronpo demonstrated for expected-type-only schemes ([AL #256][al-256]). Exact rules are open question §2.7(2).

#### 2.2.2 Maps

No `->` shape detection ([sjrd, CL #24][cl-24] stands; dropped in [CL #36][cl-36]). A `Map[K, V]` instance simply has `Elem = (K, V)`, so `["a" -> 1]`, `[kv]` for a pair-typed `val kv`, and `[]` all work at map-typed positions under the one elaboration rule, which also settles JD557's mixed-elements question ([CL #17][cl-17]). Untargeted, `["a" -> 1]` is a `Seq[(String, Int)]` by the default rule — never silently a `Map`, so Ichoran's swallowed-duplicate-key hazard ([CL #16][cl-16]) cannot occur. Untargeted maps are written `Map(…)`/`Map()`, matching the late consensus in the thread ([odersky, CL #243][cl-243]; [lihaoyi, CL #244][cl-244]; [bishabosha, CL #248][cl-248]: "the real dispute is just notation for guaranteed type inference of 'empty map', and to that perhaps settling for `Map()` is not so bad").

#### 2.2.3 Stdlib instances

Immutable collections (`Seq`, `List`, `Vector`, `Set`, `Map`; `IArray` given `ClassTag`) get instances in the type class companion, so no imports are needed. Instances for mutable collections — including `Array` — live behind `import scala.compiletime.ExpressibleAsCollectionLiteral.mutableLiterals.given`, so nikitaga's case ([CL #104][cl-104]) — a literal silently satisfying a mutable parameter — requires an explicit opt-in. The untargeted default is always immutable `Seq` regardless of imports. (The `ClassTag` problem from [CL #5][cl-5], "No ClassTag available for Any", is one reason the fixed default is `Seq` rather than `IArray`.)

#### 2.2.4 Record literals

A named-tuple literal `(f₁ = v₁, …, fₙ = vₙ)` whose expected type is a case class `C` elaborates to `C(f₁ = v₁, …)` — a real named-argument constructor call. Three consequences follow without further mechanism, and all three matter for config DSLs:

- Default arguments apply: fields with defaults can be omitted. This is what named tuples alone cannot provide ([mberndt, AL #12][al-12]).
- Overloads, implicits, and using-clauses behave as at any call site.
- Field-level errors point at the field; a missing-field error lists the missing names at the literal.

Rules carried over from [CL #137][cl-137]: target-type interpretation of literals only — no subtyping, no implicit conversion between named tuples and classes. Positional tuples do not convert ([Ichoran, CL #158][cl-158]: the names are what make the form safe; `((5,12),(25,192))` for a `Rect` stays illegal). Non-case types can opt in via a mirror typeclass `ExpressibleAsRecordLiteral[C]` with an inline `fromRecord`, symmetric to the collection typeclass.

There is no empty record literal. `()` is the Unit value and there is no named tuple literal of arity zero, so the rule has no empty case to interpret: a case class with no fields, or with every field defaulted, is constructed as `C()`. The rationale mirrors the names argument: a record literal trades the class name for *n* field names, and at *n* = 0 the class name is the only information left — `()` at type `Config` would tell the reader nothing. Odersky floated the opposite choice in [CL #85][cl-85] ("`()` could fill a case class whose fields all have defaults"); it is not adopted here, matching Swift, whose leading-dot syntax requires `.init()` rather than bare parentheses. Any nonempty subset of fields works, with defaults filling the rest, and a `()` written at a fully-defaulted case class type gets a dedicated error note pointing at the `C()` spelling.

#### 2.2.5 Composition

The two halves compose recursively: a record field typed `Seq[Developer]` gives an inner `[...]` its target; each element's target `Developer` makes `(id = …, name = …)` a record literal; and so on down. Records inside collections inside records is the case that build and config files consist of, and neither Pre-SIP alone covers it.

#### 2.2.6 Out of scope for v1 (compatible later)

- Pattern-position literals (`case [x, rest*] =>`).
- Resolution of enum cases and companion members in literal positions (`licenses = [MIT]`) — this is [SIP-80 companion scope inference][sip80] (soronpo; the sigil-free descendant of the relative-scoping ideas at [AL #13][al-13]), a separate proposal that composes with this one rather than a rider on it. Data literals manufacture expected types at exactly the positions SIP-80 consumes them: a collection literal gives its elements `Elem` as expected type, a record literal pins constructor parameter types, so under both features `val shapes: Vector[Shape] = [(geometry = Circle, color = Blue)]` resolves `Circle` and `Blue` through the respective companions with no imports. The two features also share their notion of a usable expected type (target-type reduction, including constrained type variables via upper bounds), and SIP-80 obeys the same principle as P1: names are resolved against the expected type by fixed rule, never against ambient scope, and only where normal resolution fails — so the composition adds no new choice points. One position needs a dedicated rule: the key of a `->` pair is the *receiver* of `->`, whose expected type is not directly given by the element type. A general receiver-position rule closes it — when the unresolved identifier is the qualifier of a selection `X.op(args)`, candidates for `op` that are applicable without knowing the receiver (extension methods in scope, members provided by conversion-shaped implicits) have their type parameters instantiated by unifying their result type with the selection's expected result, which determines the receiver's expected type. For `[Red -> [Circle]]` at `Map[Color, List[Geometry]]`, unifying `->`'s result `(A, B)` with the element type gives `A := Color`, so `Red` resolves in `Color`'s companion. The rule mentions no specific operator; `->` falls out, as does any pipeline- or builder-style operator. Tuple-form pairs `[(Red, [Circle])]` also work via plain component-type propagation.

### 2.3 The motivating example: Mill

Build files suit this proposal well because override positions always have expected types — `def moduleDeps` inherits `Seq[JavaModule]` from `JavaModule`; `Task { … }` propagates `T[Seq[Dep]]` inward. Today (patterns from [lihaoyi, CL #67][cl-67] and [AL #72][al-72], and from Mill's own `build.mill`, which enumerates ~20 compiler-bridge versions inside `Seq(...)` wrappers):

```scala
object core extends ScalaModule with PublishModule:
  def moduleDeps = Seq(util, define)
  def mvnDeps = Seq(mvn"com.lihaoyi::os-lib:0.11.3", mvn"com.lihaoyi::upickle:4.1.0")
  def javacOptions = Seq("-encoding", "UTF-8", "-deprecation")
  def pomSettings = PomSettings(
    description = "Core module",
    organization = "com.example",
    url = "https://github.com/example/project",
    licenses = Seq(License.MIT),
    versionControl = VersionControl(browsableRepository = Some("github.com/example/project")),
    developers = Seq(Developer("jamie", "Jamie Thompson", "https://github.com/bishabosha")))
```

With data literals:

```scala
object core extends ScalaModule with PublishModule:
  def moduleDeps = [util, define]
  def mvnDeps = [mvn"com.lihaoyi::os-lib:0.11.3", mvn"com.lihaoyi::upickle:4.1.0"]
  def javacOptions = ["-encoding", "UTF-8", "-deprecation"]
  def pomSettings = (
    description = "Core module",
    organization = "com.example",
    url = "https://github.com/example/project",
    licenses = [License.MIT],
    versionControl = (browsableRepository = Some("github.com/example/project")),
    developers = [(id = "jamie", name = "Jamie Thompson", url = "https://github.com/bishabosha")])
```

Every removed token was information the position already declared. Odersky's schema/value split ([CL #94][cl-94]) describes this domain accurately: "The person defining the schema… will take care choosing the right collection types. The person defining the data value should not care about this at all." It also answers sjrd's rebuttal ([CL #69][cl-69], that Mill's noise is `object … extends` and a `def s(...)` helper would do): a helper serves one library, cannot nest recursively ([ghik, CL #147][cl-147]), and does nothing for `PomSettings`.

### 2.4 Responses to the recorded objections

| Objection | Source | Response |
|---|---|---|
| Givens determine my types / `CanBuildFrom` redux | [Ichoran CL #16][cl-16], [eed3si9n CL #32][cl-32] | The untargeted default is fixed by the spec (`immutable.Seq`, as `1` is `Int`), not found by given search. Under an expected type, the type is written at the position and givens select only the construction, inline-resolved, with ambiguity a hard error. In neither case does a type arrive from scope. |
| Yet another way / fragmentation | [sjrd CL #115][cl-115], [SethTisue CL #39][cl-39], [djspiewak CL #55][cl-55] | At a target-typed position the literal repeats nothing the position doesn't declare. Untargeted, `val xs = [1,2,3]` vs `val xs = Seq(1,2,3)` ([sjrd CL #124][cl-124]) is a real style choice. The counter-arguments are the numeric-literal precedent (`1` vs `1: Int`, `2.3` vs `2.3f`) and notation uniformity: a data document compiles unmodified in any position or file (§2.5.1). This objection is argued against rather than removed; the target-typed-only subset ([tarsa CL #255][cl-255]) is the documented fallback (P1, severability). |
| Map shape magic | [sjrd CL #24][cl-24] | Removed (§2.2.2); untargeted `["a" -> 1]` is a `Seq` of pairs. |
| Mutable / performance traps | [nikitaga CL #104][cl-104], [Ichoran CL #144][cl-144] | Mutable instances require an import; the untargeted default is fixed and immutable; `fromLiteral` is construction at the declared or default type, not a runtime coercion in the style of `into`, so there is no silent representation change to regress. |
| Overload-resolution complexity | [tarsa CL #119][cl-119], [soronpo AL #256][al-256] | A fixed default avoids C#'s "better conversion" machinery: where overloads do not agree on a parameter type, the literal takes its default type and resolution proceeds normally — deterministic, occasionally requiring ascription. Exact rules are open question §2.7(2). |
| Tooling and process | [djspiewak CL #33][cl-33], [eed3si9n CL #32][cl-32], [fanf CL #156][cl-156] | Adopted as acceptance criteria: stabilization gated on merged support in scalameta, tree-sitter-scala, IntelliJ, and Metals. SCON's standalone tokenizer shows the grammar is cheap to parse in isolation. |
| Brackets mean types | [MarkCLewis CL #92][cl-92], [Sporarum CL #10][cl-10], [satorg CL #133][cl-133] | Mitigated, not resolved — this proposal has no answer that removes the objection. The alternatives were each examined and rejected in-thread (§2.6). The teaching rule becomes: brackets after a name are types; brackets as an expression are data. |
| Bulk data belongs in files | [satorg CL #57][cl-57], [fanf CL #31][cl-31], [channingwalton CL #118][cl-118] vs [lihaoyi CL #67][cl-67] | Addressed by the notation layer (§2.5): the same notation is the file format, typed and position-diagnosed, so data can live in code or in files and move between them without translation. |

### 2.5 The data-notation layer (from SCON)

The SIP specifies, as a normative appendix, the closed data subset: scalar literals, string concatenation, sequence literals, record (named-tuple) literals, tuples, `->` pairs, `null`. Two guarantees:

1. A document in the subset is a valid Scala expression: pasted into any target-typed position, it compiles, with per-element error positions.
2. The subset is parseable without the compiler. [SCON][scon] is the reference implementation: schema-directed decode into named tuples, case classes, enums, and collections; programmatic read-back and pretty-printing; decode errors carrying nested paths.

This layer is what connects the proposal to DSLs and bulk data rather than only to shorter call sites. Mill can accept a Scala-syntax data file for dependency manifests; a service can keep config in a typed format that is copy-pasteable into tests; typed config needs no codegen, only `derives ReadWriter`. The two positions from §1.1 — data belongs in files, and moving data to files costs type safety and tooling — both become supported deployment modes of one notation.

#### 2.5.1 Consumption modes and the default rule

A document is consumed in three ways, and the expected type lives in a different place in each. The default rule of P1 exists so that all three work:

**Mode 1 — external decode.** The document is read at runtime through `readDeclAs[Config](input, …)` or a `Reader[T]`; that `T` plays the role the expected type plays in the compiler. The decoder walks tokens against `RawSchema` top-down — the same outside-in propagation the language feature performs with expected types. The schema is the expected type, arriving from the API boundary instead of from an ascription inside the document.

**Mode 2 — untyped structural processing.** When SCON decodes without a schema, `[1, 2, 3]` becomes a `VectorExpr` AST node — the notation's analogue of `ujson.Value` — not a `scala.collection.immutable.Seq[Int]`. The objection from [CL #16][cl-16]/[CL #32][cl-32] concerns the compiler choosing one concrete collection among many candidates; a structural parser makes no such choice. The grammar has exactly one sequence constructor, so `[...]` denotes a sequence node whose representation is deferred until a schema interprets it. The default-type question only exists in compiled Scala, which is why the spec-fixed `Seq` default and the `VectorExpr` node do not conflict.

**Mode 3 — the document compiled as a project member.** This mode decides the untargeted-default question. The data file carries a `.scala` suffix, is included in the build, and its unascribed top-level `val conf = (…)` must compile with zero ascriptions and zero imports — so downstream code can `import example.config.conf` and use `conf.app.host` with full inferred typing, while external tools decode the same file without the compiler. Named-tuple record literals already infer their own types with no target (record reinterpretation in §2.2.4 only fires when a case-class target exists; untargeted, they are ordinary named tuples), and scalars are scalars. The default rule covers the remaining case, sequences:

```scala
package example.config

val conf = (
  app = (host = "127.0.0.1", port = 8080,
         replicas = [(region = "eu-central", weight = 2), (region = "us-east", weight = 1)]),
  features = ["metrics", "tracing"],
)
```

This compiles as-is: `features` is a `Seq[String]`, `replicas` a `Seq[(region: String, weight: Int)]`, `conf` a fully inferred named tuple. A target-typed-only design rejects exactly this file. That is the main positive argument for the fixed default, and the reason the fallback positions in P1 are fallbacks rather than the main line.

Two notes:

1. **The explicit sequence spelling aligns on `Seq(...)`.** SCON currently spells sequences `Vector(...)`, but the constructor name is effectively a node label — its builders already decode it into arbitrary `Seq`s, arrays, or pair-sequence maps. SCON will migrate the keyword to `Seq` to match the language default, with `Vector` accepted as a legacy alias during migration. `Seq` also fits the notation's design better: it names an interface rather than a representation, consistent with the schema choosing the representation. With that, `[...]` and `Seq(...)` denote the same node in every regime, `[...]` is the canonical form, and a compiled document (mode 3) and a decoded one (mode 1) agree on what an untargeted sequence means.
2. **Alternative untargeted designs, considered and not adopted.** (a) Declared defaults: untargeted `[...]` legal only under an explicit `import scala.data.literalDefaults.vector`, a given with no instances in implicit scope. The provenance is lexically visible, but bare data documents become illegal and the ceremony lands exactly where bulk data lives; retained as the intermediate fallback between the full default and target-typed-only. (b) A reified default: untargeted `[1, 2, 3]` typing as a data-AST value (an `Expr`-like `scala.data.Value`, as ujson literals work). This avoids choosing any collection, but it pushes a decode step into downstream types and makes mode 3 produce ASTs where users want collections.

### 2.6 Alternatives considered

- **Tuple syntax `(a, b, c)`** ([bishabosha AL #4][al-4]; [kavedaa AL #33][al-33]; [arturopala CL #116][cl-116]; [bjornregnell CL #196][cl-196]) — `(x)` must remain a no-op, and one-element sequences are common while one-element tuples are rare, so `(42,)`-style fixes address the wrong case ([lihaoyi CL #193][cl-193], [CL #204][cl-204]; [odersky CL #201][cl-201], reaffirmed as "brackets or nothing" in [CL #240][cl-240]).
- **Library `&(...)`/`c(...)` via typeclass** ([mberndt CL #44][cl-44]; JD557's Scastie strawman) — no recursive expected-type propagation ([ghik CL #147][cl-147]).
- **`#(...)` companion placeholder** ([mberndt, AL from ~#98][al-1]) — rejected on the names argument ([odersky AL #194][al-194]); ambiguous under overloading ([soronpo AL #256][al-256]).
- **`(a, b, c)*` postfix spread** ([Ichoran AL #6][al-6], revived [AL #270][al-270]; [Sporarum CL #125][cl-125]) — "intriguing but fails the familiarity test" ([odersky AL #272][al-272]); shares the parenthesis problems.
- **String interpolators** (`json"…"`, `seq"1 2 3"` — [Sporarum CL #73][cl-73]; [diesalbla CL #171][cl-171]) — a foreign syntax embedded in Scala; precise typing through interpolators is contested and the record use case is lost ([rjolly CL #232][cl-232]).
- **Editor-level display sugar** ([nightscape CL #188][cl-188]) — most code is read outside editors ([Sporarum CL #189][cl-189]).
- **Generalized inference improvements** (`Factory`-based `col(...)` plus `@inferFromExpectedReturnType`, [ghik CL #139][cl-139]) — worthwhile but a much larger change, and orthogonal to this proposal.
- **`fromtuple`-style implicit conversions** ([soronpo AL #237][al-237]) — useful userland prior art; subject to the same one-level inference limit.

Dropped from the predecessor proposals, each for a specific documented reason: the `->` map-shape detection and the `Map` default; `[…]` as sugar for arbitrary `apply`; the `#` placeholder; positional-tuple-to-case-class adaptation. What remains is the subset that survived both discussions, plus a shipped library ([SCON][scon]) exercising the notation end to end.

### 2.7 Compilation performance

Measured on the reference implementation (this branch), because compile-time cost was a recurring process concern in the CL thread. Method: synthetic single-file programs holding one bulk-data value with N entries, N doubling from 50 to 800; five variants — a `Map[Color, List[Geometry]]` in vanilla syntax, as collection literals with qualified names, and as collection literals with bare enum keys and values (exercising the receiver-position rule on every key); a `Vector[Shape]` in vanilla syntax and as record literals with bare enum fields. Compiled in-process with `dotty.tools.dotc.Bench` (15 runs per file, average of best 5, single machine). Corpus generator and the N=800 pair are committed under `tests/bench/dataLiterals*.scala`.

Results (ms, steady state):

| variant | 50 | 100 | 200 | 400 | 800 |
|---|---|---|---|---|---|
| map, vanilla | 56 | 59 | 73 | 105 | 153 |
| map, literals, qualified names | 60 | 75 | 97 | 143 | 220 |
| map, literals, bare names | 60 | 76 | 104 | 149 | 234 |
| records, vanilla | 39 | 39 | 42 | 45 | 44 |
| records, literals, bare names | 43 | 45 | 51 | 55 | 59 |

Findings:

1. **Scaling is preserved.** Growth per size-doubling is in the 1.2–1.6 range for every variant (2.0 = pure linear marginal cost) and the literal variants scale the same way as their vanilla counterparts; nothing super-linear appears in the elaboration or the resolution machinery.
2. **Companion scope inference is not the cost center.** Bare names vs qualified names at N=800: 234 vs 220 ms — the 800 receiver-position probes plus 1600 element companion lookups add about 6%, on the order of 6µs per resolution.
3. **The overhead that exists is literal elaboration itself**: collection literals with qualified names cost 1.44× vanilla at N=800 (about 1.8× marginal per-entry cost). This is the per-literal instance search plus the inline expansion of `fromLiteral` — each nested `[g1, g2]` passes through the inliner where `List(g1, g2)` does not. Record literals show the same shape at much smaller absolute cost (1.34× at N=800), since their elaboration is a plain constructor call. If this ever matters in practice, the optimization target is bounded and known: non-inline fast paths for the standard library instances, not the resolution machinery.

### 2.8 Staging and open questions

**Staging:**

- **Stage A** (experimental): sequence literals — typeclass-directed under an expected type, fixed `Seq` default otherwise. This is the existing `language.experimental.collectionLiterals` implementation minus the `->` map detection.
- **Stage B**: record literals for case classes — the amendment to the named-tuples SIP Odersky said he would file ([CL #94][cl-94]) — plus `ExpressibleAsRecordLiteral`.
- **Stage C** (optional; requires new evidence): patterns (`case [x, rest*] =>`); [SIP-80][sip80] proceeds on its own track and composes without coordination (§2.2.6).

**Severability** (restating P1): if the committee does not accept the untargeted default, it can be removed from Stage A without affecting the rest, in two grades — declared defaults (§2.5.1 note 2a) or full target-typed-only ([tarsa CL #255][cl-255]) — at the cost of rejecting bare compiled data documents (§2.5.1 mode 3). This should be decided before stabilization: a shipped default cannot be retracted, a withheld one can be added.

**Open questions:**

1. Typeclass naming — `ExpressibleAsCollectionLiteral` is long ([odersky CL #1][cl-1]; `CanBeCollectionLiteral` was floated, [ssdeep CL #111][cl-111]); Swift's `ExpressibleByArrayLiteral` is precedent for keeping the self-describing name.
2. Overload resolution: specify how a literal argument participates (lambda-like shape-based pre-selection, default type where parameter types disagree, error on residual ambiguity). This is where C#'s complexity lives ([tarsa CL #119][cl-119]); the rules need to be written out.
3. Whether record literals extend beyond case classes only via the typeclass, or also to any class with a single accessible constructor.
4. Interaction with `into` varargs, and restating the named-tuples SIP's disambiguation of single-field `(f = v)` from assignment.
5. Mutable-instances policy: behind an import (proposed here) or absent from the stdlib entirely.
6. The precise definition of "no usable expected type" for the default rule: the [CL #1][cl-1] implicit-search-style list is the starting point, but unions/intersections, `Matchable`, opaque aliases, and by-name/`into` positions need spelling out — the "manifest target type" idea from [AL #145][al-145] (follow aliases, not type-parameter instantiations) is the candidate framework. Also whether `AnyVal` gets a dedicated error or a plain conformance failure.
7. ~~Whether the fixed default should be `Seq` or `Vector`~~ — resolved: `Seq`, and SCON migrates its explicit sequence keyword from `Vector(...)` to `Seq(...)` to match (§2.5.1 note 1). `Seq` names an interface rather than a representation, matches the existing neutral idiom, and avoids the `ClassTag` constraint of [CL #5][cl-5]. Remaining detail: the migration/aliasing story for existing SCON documents.

---

## Summary

The proposal combines: Odersky's typeclass machinery and fixed `Seq` default from [CL #1][cl-1] (the default justified by the numeric-literal precedent — `1` is `Int` by specification, not by given search — and required for bare compiled data documents, but kept severable per [tarsa CL #255][cl-255]); the removal of the `->` map-shape rule; the point both threads converged on (named-tuple literals as case-class constructors with default arguments, [CL #94][cl-94]/[CL #158][cl-158]); and SCON's closed-subset guarantee that the notation doubles as an external data format. The target use case is the Mill-style DSL: schemas name the types once, values are pure data, and the same file works compiled in the project, pasted at a typed position, or decoded without the compiler.

[cl]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990
[al]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697
[scon]: https://github.com/bishabosha/scala-object-notation
[sip72]: https://github.com/scala/improvement-proposals/pull/112
[cl-1]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/1
[cl-5]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/5
[cl-10]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/10
[cl-16]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/16
[cl-17]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/17
[cl-24]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/24
[cl-31]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/31
[cl-32]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/32
[cl-33]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/33
[cl-34]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/34
[cl-36]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/36
[cl-39]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/39
[cl-44]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/44
[cl-55]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/55
[cl-57]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/57
[cl-67]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/67
[cl-69]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/69
[cl-73]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/73
[cl-79]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/79
[cl-80]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/80
[cl-85]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/85
[cl-92]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/92
[cl-94]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/94
[cl-104]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/104
[cl-111]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/111
[cl-115]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/115
[cl-116]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/116
[cl-118]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/118
[cl-119]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/119
[cl-122]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/122
[cl-124]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/124
[cl-125]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/125
[cl-133]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/133
[cl-137]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/137
[cl-139]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/139
[cl-144]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/144
[cl-147]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/147
[cl-148]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/148
[cl-156]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/156
[cl-158]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/158
[cl-171]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/171
[cl-188]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/188
[cl-189]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/189
[cl-193]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/193
[cl-196]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/196
[cl-201]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/201
[cl-204]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/204
[cl-217]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/217
[cl-232]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/232
[cl-240]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/240
[cl-243]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/243
[cl-244]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/244
[cl-246]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/246
[cl-247]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/247
[cl-248]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/248
[cl-255]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/255
[al-1]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697/1
[al-4]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697/4
[al-6]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697/6
[al-12]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697/12
[al-13]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697/13
[sip80]: https://github.com/soronpo/scala-sips/blob/master/content/080-companion-scope-inference.md
[al-33]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697/33
[al-44]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697/44
[al-53]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697/53
[al-72]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697/72
[al-119]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697/119
[al-145]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697/145
[al-194]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697/194
[al-234]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697/234
[al-237]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697/237
[al-256]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697/256
[al-257]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697/257
[al-270]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697/270
[al-272]: https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697/272
