# Pre-SIP: Data literals — collection and record literals for configuration and DSLs

This is a follow-up to two earlier discussions: [Pre-SIP: A syntax for collection literals](https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990) and [Pre-SIP: A syntax for aggregate literals](https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697). I have tried to take both threads seriously — the objections as much as the proposals — and to combine what survived them into one design. It is not a new idea so much as a specific selection: Martin's typeclass elaboration and fixed default from the collection-literals thread, minus the map special case; the named-tuples-as-case-class-constructors interpretation that both threads independently arrived at; and a specification of the notation as a data format, based on my experience shipping exactly this syntax as a library ([scala-object-notation](https://github.com/bishabosha/scala-object-notation)).

I'll state up front what I expect to be the contested part — the untargeted default — and why I think the earlier discussion argued against a design that wasn't actually on the table. Details below.

## Motivation

The target use case is bulk data and configuration: build files, test fixtures, request/config schemas — places where a value's structure is dictated by a schema defined elsewhere, and the code at the use site adds no information beyond the data itself. A Mill build file today:

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

With this proposal:

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

Every removed token is information the position already declares: `def moduleDeps` inherits `Seq[JavaModule]`, `PomSettings`' fields fix the types of everything inside it. The person defining the schema chooses the collection types; the person writing the data should not have to repeat them.

The library route cannot get there. As ghik demonstrated in the previous thread ([CL #147](https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/147)), Scala's inference propagates expected types only one level down, so any library encoding fails on nesting — and nesting (records inside collections inside records) is what configuration consists of. Recursive expected-type propagation into literal elements is the part that needs the language.

There is a second motivation, which I'll expand on below: with a small, closed grammar, the same notation works as a *data format*. A configuration file can be a valid Scala source file — compiled into the project with full inferred typing, or decoded at runtime by a lightweight parser with no compiler involved, with error messages carrying source positions. scala-object-notation implements this today against the experimental syntax.

## Proposal

### Sequence literals

As in the previous thread:

```
SimpleExpr ::= … | '[' ExprInParens {',' ExprInParens} ']'
```

With expected type `E`, the compiler resolves `ExpressibleAsCollectionLiteral[E]`:

```scala
trait ExpressibleAsCollectionLiteral[+Coll]:
  type Elem
  inline def fromLiteral(inline xs: Elem*): Coll
```

and elaborates `[a, b, c]` to `instance.fromLiteral(a, b, c)`. Each element is type-checked with expected type `Elem`, recursively, so nested literals work. `fromLiteral` is inline with inline varargs, so instances may be macros and can construct the target collection without an intermediate varargs `Seq`.

With **no usable expected type** (no expected type, a wildcard, `Any`/`AnyRef`, or an underspecified type variable), the literal is `scala.collection.immutable.Seq[T]`, with `T` the lub of the elements. This default is a fixed rule of the language specification. No given search occurs in the untargeted case.

I want to be precise about this, because much of the earlier criticism — "types materializing depending on which givens are in scope", "`CanBuildFrom` in a sheep's skin" — describes a *given-driven* default, and that is not what was proposed then and not what is proposed now. The untargeted default is fixed the same way the type of `1` is fixed to `Int` and `1.0` to `Double`: by the spec, uniformly, everywhere. Givens are consulted only when an expected type is written at the position — and then the type the literal takes is the one you can read on that line.

### Maps: no special case

The `->` shape detection from the original proposal is dropped entirely — I think sjrd's objection ([CL #24](https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/24)) was correct and unanswerable. Instead, a `Map[K, V]` instance simply has `Elem = (K, V)`, so at a map-typed position `["a" -> 1]`, `[kv]` for a pair-typed value, and `[]` all work under the one elaboration rule. Untargeted, `["a" -> 1]` is a `Seq[(String, Int)]` — never silently a `Map`, so duplicate keys are never silently swallowed. An untargeted map is written `Map(...)`, as today.

### Standard library instances

Immutable collections (`Seq`, `List`, `Vector`, `Set`, `Map`; `IArray` given `ClassTag`) get instances in the type class companion. Instances for **mutable** collections — including `Array` — exist but live behind `import scala.compiletime.ExpressibleAsCollectionLiteral.mutableLiterals.given`, so a literal can never silently satisfy a mutable parameter without an explicit opt-in at the use site. The untargeted default is immutable `Seq` regardless of imports.

### Record literals

A named-tuple literal whose expected type is a case class `C` elaborates to a real named-argument constructor call:

```scala
val settings: PomSettings = (
  description = "Core module",
  organization = "com.example",
  licenses = [License.MIT],   // Seq[License] expected here
)
```

Because this is an ordinary constructor call, **default arguments apply** — fields with defaults can be omitted, which named tuples by themselves can never offer, and which is essential for config-style schemas with many optional fields. Overloads, implicits and using-clauses behave as at any call site, and error messages point at individual fields.

Restrictions, following the discussion at the end of the previous thread: this is target-type interpretation of *literals* only — no subtyping and no implicit conversion between named tuples and classes. Positional tuples do **not** convert to case classes; the field names are what make the form safe. Non-case-class types can opt in via a mirror typeclass `ExpressibleAsRecordLiteral[C]`.

This is the surviving core of the aggregate-literals thread. The general "brackets for any `apply`" was rightly rejected there — a form that erases names invites overuse. A record literal is different: it trades one class name for *n* field names, which in configuration code is usually a gain in information, not a loss.

### The notation as a data format

The subset of Scala used above — scalar literals, string concatenation, `[...]` sequences, `(name = value, ...)` records, tuples, `->` pairs, `null` — forms a closed grammar that a simple parser can handle with no symbol resolution. I propose specifying it as an appendix of the SIP, with two guarantees:

1. A document in the subset is a valid Scala expression: pasted at any target-typed position, it compiles, with per-element error positions.
2. The subset is parseable without the compiler.

[scala-object-notation](https://github.com/bishabosha/scala-object-notation) is a working implementation: documents are valid Scala source, decoded at runtime against a schema (named tuples, case classes, enums, collections) with error paths like `.items[1].region` mapped to source positions. It already supports `[...]` behind the same `language.experimental.collectionLiterals` import the compiler uses.

This is also where the untargeted default earns its place. Consider a configuration file that is itself a Scala source file in the project:

```scala
package example.config

val conf = (
  app = (host = "127.0.0.1", port = 8080,
         replicas = [(region = "eu-central", weight = 2), (region = "us-east", weight = 1)]),
  features = ["metrics", "tracing"],
)
```

With the default rule this compiles as-is — `features: Seq[String]`, `replicas: Seq[(region: String, weight: Int)]`, `conf` a fully inferred named tuple — so other modules can use `conf.app.host` directly, while external tools decode the same file without a compiler. A target-typed-only design (as C# has, and as several people proposed last time) rejects exactly this file: every data document would need an ascription or an import. That, more than saving keystrokes at call sites, is why I propose keeping the default.

(To align with this, scala-object-notation will migrate its explicit sequence keyword from `Vector(...)` to `Seq(...)`.)

## What this proposal deliberately does not include

- The `->` map-shape rule and the untargeted `Map` default (dropped; see above).
- `[...]` as sugar for arbitrary companion `apply` methods, and the `#` companion placeholder (rejected in the aggregate-literals thread; I agree with the rejection).
- Positional-tuple-to-case-class adaptation (the names are the point).
- Pattern-position literals and relative scoping (`licenses = [..MIT]`) — compatible later, not riders on this proposal.

## Severability

I expect the untargeted default to remain the contested point, so the proposal is factored so that it can be removed without affecting anything else — either fully (target-typed-only, as tarsa proposed in [CL #255](https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/255)) or partially (a default enabled only by an explicit import such as `import scala.data.literalDefaults.seq`). Both fallbacks are specified in the design notes; both reject the bare data-document example above, which is the cost I'd ask people to weigh. The decision should be made before stabilization in either case: a shipped default cannot be retracted, a withheld one can be added.

On tooling: I'd propose adopting the demands from last time as acceptance criteria — stabilization gated on merged support in scalameta, tree-sitter-scala, IntelliJ and Metals. The grammar is small; scala-object-notation's standalone tokenizer is evidence it is cheap to parse in isolation.

## Open questions

1. Typeclass naming (`ExpressibleAsCollectionLiteral` is long; Swift's `ExpressibleByArrayLiteral` is precedent for keeping a self-describing name).
2. Overload resolution rules for literal arguments (default type where parameter types disagree; error on residual ambiguity) — this needs to be fully specified, since it is where C#'s complexity lives.
3. Whether record literals extend beyond case classes only via the typeclass, or also to classes with a single accessible constructor.
4. Interaction with `into` varargs; restating the named-tuples disambiguation of single-field `(f = v)`.
5. The precise definition of "no usable expected type" (unions, `Matchable`, opaque aliases).

I have a longer design document with full citations into both previous threads (which objections are answered, which are argued against, and the complete alternatives list) that I can share; this post is the proposal itself. Feedback welcome — in particular from people who objected last time: does separating the fixed default from given-driven elaboration, and the data-document use case, change the picture for you?
