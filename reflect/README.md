# scala-reflect_3 compatibility module

This module is a source-compatibility foothold for Scala 2 `scala-reflect`
users migrating to Scala 3. It is intentionally separate from `scala3-library`
and publishes as `org.scala-lang::scala-reflect`.

Implemented in this first slice:

- `scala.reflect.runtime.universe`
- `runtimeMirror`, `currentMirror`, simple static class/package lookup
- `TypeTag`, `WeakTypeTag`, `typeOf`, and `weakTypeOf` materialized by Scala 3
  inline macros
- simple `Name`, `Type`, `Symbol`, `Tree`, `Constant`, `Position`, and mirror
  facades
- `scala.reflect.macros.{Context,blackbox,whitebox}` facades for helpers that
  are being rewritten to Scala 3 quoted macros

Explicitly not implemented:

- legacy Scala 2 `def macro` expansion
- whitebox result refinement
- exact Scala 2 mutable compiler trees and owner mutation APIs
- legacy ToolBox behavior
- exact Scala 2 tree shapes, diagnostics, or printer output

The compatibility model is source-oriented, like the Scala 2.13 library sources
ported into Scala 3. It is not a binary-compatibility promise for artifacts built
against Scala 2 `scala-reflect`.

See `FINISHING_PLAN.md` for the implementation order, test matrix, and review
slices needed to finish the port.
