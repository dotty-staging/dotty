# Scala 3 Compiler on JavaScript (compiler-js)

The `compiler-js/` subproject compiles the Scala 3 compiler itself to JavaScript
via Scala.js, producing a `main.js` that runs on Node.js.

The compiler frontend is fully functional: parsing, type-checking, error
reporting, and capture checking all work. The Scala.js backend (`-scalajs`) also
works, so `.sjsir` files can be emitted. The JVM backend (GenBCode) is stubbed
out — no `.class` file output.

## Quick start

```bash
# Build the JS compiler and bundle library class files (first time):
sbt 'project scala3-compiler-sjs' fastLinkJS bundleLibs

# Compile a Scala file:
./bin/scalac-js -d /tmp/out Hello.scala

# Compile to Scala.js IR:
./bin/scalac-js -scalajs -d /tmp/out Hello.scala
```

The `bin/scalac-js` script auto-builds if `main.js` is not found.

## How it works

### Source override mechanism

The compiler sources are shared with the main compiler. Files placed in
`compiler-js/src/` override files at the same relative path in `compiler/src/`.
The SBT build filter (in `project/Build.scala`) also excludes Java source files
and JVM-only packages (`backend/jvm/`, `scripting/`, `debug/`) from the shared
sources.

Out of ~1500 compiler source files, about 100 are overridden. Most overrides are
stubs for JVM APIs not available in Scala.js (java.nio.file, java.util.zip,
java.lang.reflect, xsbti, etc.). A handful adapt compiler code to avoid
reflection or JVM-specific features.

### Bundled libraries (`bundleLibs`)

The JS compiler needs class files on its classpath just like the JVM compiler.
The `bundleLibs` SBT task extracts them to a `lib/` directory:

```
compiler-js/target/.../lib/
  jdk/            ← java.base classes (from jmod)
  scala-lib/      ← scala2 + scala3 library classes and .tasty files
  scalajs-lib/    ← scalajs-library classes (needed for -scalajs)
```

Sources:
- **jdk/**: Extracted from `$JAVA_HOME/jmods/java.base.jmod` using `jmod extract`
  (jmod files have extra header bytes that make `IO.unzip` fail)
- **scala-lib/**: Copied from `scala-library-bootstrapped` class directory, which
  contains both the Scala 2 standard library and Scala 3 library extensions,
  compiled together with `.tasty` files
- **scalajs-lib/**: Extracted from the `scalajs-library` JAR resolved via the SBT
  update report

The `lib/` directory is placed as a sibling of the `scala3-compiler-fastopt/`
directory (not inside it, because the Scala.js linker cleans its output
directory on re-link). Extraction is cached — delete `lib/` to force
re-extraction.

### Auto-classpath detection

`Main.scala` (the JS entry point) automatically detects the bundled `lib/`
directory and injects `-classpath` arguments:

1. Derives the script directory from `process.argv[1]` (Node.js)
2. Looks for `../lib/jdk`, `../lib/scala-lib`, `../lib/scalajs-lib` relative
   to `main.js`
3. If no `-classpath`/`-cp` is provided: prepends the bundled classpath
4. If `-classpath` is provided: merges bundled paths before user paths

Note: `js.Dynamic.global.__dirname` cannot be used because `__dirname` is a
module-scoped variable in Node.js, not a property of `global`.

## Architecture

### SBT project

Defined in `project/Build.scala` (~line 1746) as `scala3-compiler-sjs`:

- Depends on `scala3-interfaces`, `tasty-core-bootstrapped`, `scala3-library-sjs`
- Compiled with the non-bootstrapped compiler
- `scalaJSUseMainModuleInitializer := true` with `Compile / mainClass := Some("dotty.tools.dotc.Main")`
- ES2018 target (needed for regex MULTILINE flag support)
- Fetches and repackages `scalajs-ir` sources (same as the bootstrapped compiler)

### Key overrides

**MegaPhase.scala** — The only change is the `defines` method, which on JVM uses
`Class.getDeclaredMethods` (Java reflection) to check which mini-phases handle
which tree types. On Scala.js, this is replaced with `true` (conservatively call
all mini-phases for all tree types). This is a performance trade-off, not a
correctness issue. The rest of MegaPhase is the original, unmodified.

**GenBCode.scala** — Stubbed: `isRunnable` returns `false`. The JVM backend
cannot run in JavaScript.

**IO layer** — `java.io.File`, `java.nio.file.Files`, `java.io.FileOutputStream`
are reimplemented using Node.js `fs` and `path` modules. Jar/zip reading is
stubbed (only unpacked class directories work as classpath entries).

**Macros / Quotes** — `MacroAnnotations`, `Splicer`, `Interpreter`,
`MacroClassLoader` are stubbed. Compile-time macro evaluation is not supported.

**Plugins / SemanticDB / Profiler** — Disabled via stubs.

**sbt integration** — `ExtractAPI`, `ShowAPI`, `ThunkHolder`, and callback
interfaces are stubbed to remove the dependency on zinc/sbt internals.

### Patterns and pitfalls

When adding new overrides:

- **Parameterless vs empty-paren**: Scala.js interface stubs define parameterless
  methods; if the compiler calls them with `()`, override the caller instead.
- **Constructor init order**: Use `def` (not `val`) for fields in classes like
  `PlainFile` where the superclass constructor accesses them before initialization.
- **Circular init**: Break cycles with `null.asInstanceOf` (see `NoSourcePosition`).
- **Scala.js built-in classes**: Cannot add methods to `Thread`, `System`, `Class`,
  `URI` — override the call sites instead.
- **Minimize overrides**: Always start from the original file. Only change what
  actually breaks. The MegaPhase incident (rewriting logic instead of fixing the
  one incompatible method) is the cautionary tale.

## JavaScript API

The generated `main.js` exposes two usage modes: a Node.js CLI entry point and a
browser-facing API.

### Node.js (CLI)

When loaded in Node.js, `main.js` runs immediately as a CLI tool. It reads
arguments from `process.argv`, auto-detects bundled libraries, and invokes the
compiler. There is no API to call — it behaves like a standard command-line
compiler.

### Browser (`DottyCompiler`)

When loaded in a browser (via a `<script>` tag), `main.js` exports a global
`DottyCompiler` object with two methods:

#### `DottyCompiler.loadClasspath(buffer: ArrayBuffer): void`

Loads the classpath archive into memory. Must be called once before `compile()`.
The `buffer` should contain the contents of `classpath.bin` (produced by the
`packClasspath` SBT task).

#### `DottyCompiler.compile(source: string, args: string[]): Diagnostic[]`

Compiles a Scala source string and returns an array of diagnostic objects.

**Parameters:**
- `source` — Scala source code as a string
- `args` — additional compiler flags (e.g., `["-Xprint:typer", "-Ycc"]`)

**Returns** an array of objects, each with:

| Field      | Type     | Description                                      |
|------------|----------|--------------------------------------------------|
| `severity` | `string` | `"info"`, `"warning"`, or `"error"`              |
| `line`     | `number` | 0-based line number (`-1` if no position)        |
| `column`   | `number` | 0-based column number (`-1` if no position)      |
| `message`  | `string` | Rendered message with source context (ANSI-colored) |

#### Example

```javascript
const resp = await fetch("classpath.bin");
DottyCompiler.loadClasspath(await resp.arrayBuffer());

const diagnostics = DottyCompiler.compile(
  "object Hello { val x: Int = true }",
  []
);
for (const d of diagnostics) {
  console.log(`[${d.severity}] line ${d.line}: ${d.message}`);
}
```

## Limitations

- **No `.class` output**: The JVM backend is stubbed.
- **No jar/zip classpath**: Only unpacked class directories work. Implementing
  in-browser zip reading (e.g., via JSZip) would also enable jar classpath support.
- **No macro evaluation**: Compile-time splice/macro execution is not supported.
- **No incremental compilation**: sbt integration is stubbed.
- **Node.js only**: The IO layer depends on Node.js `fs`/`path` modules.
  Browser support would require a virtual filesystem.

## File inventory

~107 override files in `compiler-js/src/`:

| Category | Count | Examples |
|----------|-------|---------|
| Java API stubs | ~30 | `java/nio/file/`, `java/io/`, `java/util/zip/` |
| xsbti stubs | 3 | `xsbti/api/api.scala`, `UseScope.scala` |
| Backend stubs | 3 | `GenBCode.scala`, `DottyBackendInterface.scala` |
| Compiler infrastructure | ~20 | `MegaPhase.scala`, `Splicer.scala`, `Plugin.scala` |
| IO layer | ~10 | `PlainFile.scala`, `Path.scala`, `ZipArchive.scala` |
| sbt integration | ~5 | `ExtractAPI.scala`, `ThunkHolder.scala` |
| Reporting / config | ~10 | `Reporter.scala`, `Settings.scala`, `Properties.scala` |
| Other | ~25 | `Positioned.scala`, `Names.scala`, `SourceFile.scala` |
