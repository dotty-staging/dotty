# scala-reflect_3 compatibility manifest

Source baseline: Scala 2.13 `scala-reflect`.

This manifest records the first compatibility slice. Status values:

- `adapted`: available with Scala 3 implementation semantics
- `stubbed`: source name exists, but unsupported behavior reports explicitly
- `pending`: not implemented in this slice

| Scala 2 surface | Status | Scala 3 compatibility behavior |
| --- | --- | --- |
| `scala.reflect.runtime.universe` | adapted | Stable object exposing the initial `Universe` API. |
| `scala.reflect.runtime.currentMirror` | adapted | Uses the thread context class loader, falling back to the module class loader. |
| `scala.reflect.api.Universe` | adapted | Provides names, flags, symbols, types, trees, constants, positions, tags, and simple printers. |
| `scala.reflect.api.JavaUniverse` | adapted | Provides JVM mirrors backed by Java reflection for basic lookup/invocation. |
| `TypeTag`, `WeakTypeTag`, `typeOf`, `weakTypeOf` | adapted | Materialized by Scala 3 inline macros using `scala.quoted.Type.show`. |
| `Name`, `TermName`, `TypeName` | adapted | String-backed compatibility values. |
| `Symbol`, `ClassSymbol`, `MethodSymbol`, `TermSymbol` | adapted | Minimal symbol facade with Java-reflection-backed class/member symbols. |
| `Type` | adapted | String/class-backed type facade with equality and basic member lookup. |
| `Mirror`, `InstanceMirror`, `ClassMirror`, `MethodMirror`, `FieldMirror` | adapted | Basic runtime mirror API over JVM reflection. |
| `Tree`, `Expr`, `Constant`, `Position` | stubbed | Minimal data model only; no Scala 2 tree-shape compatibility. |
| `Universe.reify` | stubbed | Throws an unsupported-operation error; use Scala 3 quotes/splices. |
| `scala.reflect.macros.Context` | adapted | Helper facade for rewritten Scala 3 macros with an existing `Quotes`. |
| `scala.reflect.macros.blackbox.Context` | adapted | Type alias-style facade over the common compatibility context. |
| `scala.reflect.macros.whitebox.Context` | stubbed | Exists for source migration; true whitebox typing is unsupported. |
| Quasiquotes | pending | Must be translated to Scala 3 quotes/patterns. |
| `scala.reflect.io` | pending | No compatibility facade yet. |
| `scala.reflect.internal` | pending | No compatibility facade yet. |
| legacy `def macro` expansion | unsupported | Not revived in Scala 3. |
| ToolBox | unsupported | Not part of this module; use Scala 3 staging/TASTy inspection where applicable. |
