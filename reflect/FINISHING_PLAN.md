# Finishing the Scala 3 `scala-reflect` compatibility port

This module is the starting point for a source-compatible `scala-reflect_3`
artifact. The first slice adds build wiring, a minimal `runtime.universe`,
materialized `TypeTag`/`WeakTypeTag`, basic JVM runtime mirrors, a macro-context
facade, tests, and a compatibility manifest.

The goal from here is not to make Scala 2 compiler internals reappear in Scala
3. The goal is to let common Scala 2 `scala-reflect` users migrate source code
as far as Scala 3 can reasonably support, while unsupported legacy behavior is
explicit and well documented. A concrete compatibility target is fixing
scala/scala3#25896, where initializing `scala.reflect.runtime.universe` under
Scala 3.8+ currently crashes Scala 2.13 `scala-reflect` users such as Spark
integration code because `scala.Array.apply` is not found.

## Current state

- Build entrypoints were added for `scala3-reflect` in `build.sbt` and
  `project/Build.scala`.
- Sources live under `reflect/src`.
- Tests live under `reflect/test`.
- `reflect/compatibility-manifest.md` classifies the first API slice.
- `reflect/README.md` describes the current compatibility boundary.

Known caveat: this first slice has not yet been fully compiler-validated in this
thread because Metals cancelled the compile request. Treat the current code as a
scaffold that needs normal Scala 3 compilation and review before expanding it.

## Immediate next steps

1. Import the build and compile only the new module.

   ```bash
   sbt --client reload
   sbt --client scala3-reflect/compile
   sbt --client scala3-reflect/test
   ```

   In agent sessions that require Metals-only validation, use the Metals MCP
   `import_build`, `compile_module` for `scala3-reflect`, and `test` for
   `scala.reflect.compat.RuntimeUniverseTest`.

2. Fix compile errors before adding more API. The most likely first-pass issues
   are path-dependent type refinements in `scala.reflect.api.Universe`,
   bootstrap-project classpath settings, and conflicts with the existing
   `scala.reflect` package in `scala-library`.

   Add a regression test for scala/scala3#25896 while doing this stabilization:
   simply initializing `scala.reflect.runtime.universe` from Scala 3 code must
   not fail. This keeps the Spark `ScalaReflection` initialization crash in
   scope for the port rather than leaving it as a downstream-only workaround.

3. Decide whether `TypeTag` materialization should remain string-backed for v0
   or immediately move to a compiler/TASTy-backed representation. String-backed
   tags are useful for migration smoke tests, but they cannot support exact
   subtype checks, abstract type capture, or Scala 2 tree reification.

4. Once the new module compiles, add it to any aggregate/publish lists that are
   intentionally missing after review, especially local publishing or release
   packaging lists if `scala-reflect_3` should ship in distributions.

## Implementation order

Follow this order; each step should compile and have focused tests before moving
on.

1. **Stabilize module wiring**
   - Confirm `scala3-reflect` publishes as `org.scala-lang::scala-reflect`.
   - Keep `scala3-compiler` as `provided` for downstream compile classpaths, but
     available at runtime where the compatibility layer needs TASTy/quoted
     internals.
   - Add MiMa or explicit “no binary compatibility promise” release notes only
     after maintainers decide the artifact policy.

2. **Make the v0 facade compile cleanly**
   - Keep API names Scala 2-shaped: `Universe`, `JavaUniverse`, `Mirror`,
     `TypeTag`, `WeakTypeTag`, `Type`, `Symbol`, `Tree`, `Name`, `FlagSet`,
     `Position`.
   - Prefer small, immutable wrappers. Do not copy Scala 2 mutable compiler
     internals into Scala 3.
   - Unsupported methods should fail with stable, migration-oriented messages.

3. **Replace string-backed types with compiler-backed descriptors**
   - Introduce an internal representation that can hold a Scala 3
     `quotes.reflect.TypeRepr`, a TASTy-loaded symbol/type, or a JVM reflection
     fallback.
   - Keep public `universe.Type` abstract enough that the backing store can
     evolve without source churn.
   - Add tests for aliases, singleton types, abstract types, applied types,
     intersection/union types, opaque types, enums, givens, and extension
     methods.

4. **Expand runtime mirrors**
   - Implement `staticClass`, `staticModule`, `staticPackage`, `classSymbol`,
     `moduleSymbol`, `runtimeClass`, `reflect`, `reflectClass`,
     `reflectModule`, `reflectMethod`, and `reflectField`.
   - Prefer TASTy/classpath metadata for Scala 3 artifacts; use Java reflection
     as a fallback for Java and erased runtime invocation.
   - Add tests using Scala 3 top-level definitions, objects, enums, nested
     classes, Java annotations, fields, methods, and constructors.

5. **Port tag behavior incrementally**
   - Materialize `TypeTag` and `WeakTypeTag` through Scala 3 inline macros.
   - Preserve common source shapes: `implicitly[TypeTag[T]]`, context bounds,
     `typeOf[T]`, `weakTypeOf[T]`, `tag.tpe`, `tag.in(otherMirror)`.
   - Do not promise Scala 2 exactness for path-dependent abstract captures until
     there is a compiler-backed representation and tests.

6. **Macro compatibility facade**
   - Keep `scala.reflect.macros.Context`, `blackbox.Context`, and
     `whitebox.Context` as migration helpers for code rewritten to Scala 3
     macro entrypoints.
   - Support helpers that map cleanly to `Quotes`: reporting, positions, simple
     tree/type aliases, `Expr`, `WeakTypeTag`, `TypeTag`, and simple typecheck or
     implicit-search workflows.
   - Do not revive legacy `def macro` expansion or true whitebox result
     refinement. Those should remain unsupported with clear diagnostics.

7. **Document and test unsupported behavior**
   - Add negative tests for legacy `def macro`, `reify`, old quasiquotes,
     ToolBox-style eval/typecheck, mutable owner mutation, and whitebox-only
     result typing.
   - Keep the diagnostics stable and actionable: “rewrite to Scala 3 quotes” is
     better than an implementation-detail exception.

## Test matrix

Use small test files first, then broaden to a corpus.

- `TypeTag`/`WeakTypeTag`: primitives, `List[String]`, nested applied types,
  singleton types, abstract type members, opaque types.
- Runtime mirror lookup: Java classes, Scala 3 classes, top-level definitions,
  objects/modules, enums, nested classes.
- Symbols/types: `fullName`, `name`, `owner`, `info`, `member`, `decl`,
  `typeSymbol`, `asClass`, `asMethod`, `asTerm`.
- Invocation: methods, constructors, fields, private-access behavior where JVM
  access rules allow it.
- TASTy surface: givens, extensions, exports, opaque types, enums, top-level
  definitions, annotations.
- Macro migration: helpers using a `Context` facade from Scala 3 quoted macros.
- Unsupported behavior: legacy macros, whitebox-only code, ToolBox, exact Scala
  2 tree-shape assumptions.

## Design guardrails

- Source compatibility is the promise; binary compatibility with Scala 2
  `scala-reflect` is not.
- Keep the module separate from `scala3-library`.
- Avoid adding public APIs to `scala3-library` unless there is no workable
  module-local hook.
- Prefer adapters over copied Scala 2 internals.
- Keep each PR/review slice small enough to explain and test locally.
- Update `compatibility-manifest.md` whenever a surface moves from `pending` to
  `adapted` or `stubbed`.

## Suggested review slices

1. Build-only PR: module wiring, empty package, README, manifest.
2. Tag PR: `TypeTag`/`WeakTypeTag`, `typeOf`, `weakTypeOf`, materialization
   tests.
3. Runtime mirror PR: class/module lookup and basic Java/Scala invocation.
4. Symbol/type PR: richer wrappers and TASTy-backed metadata.
5. Macro facade PR: `Context` migration helpers and negative legacy-macro tests.
6. Documentation PR: migration cookbook and unsupported-behavior guide.
