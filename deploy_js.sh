#!/usr/bin/env bash
#
# Build and package the browser-ready Scala 3 compiler into scalac-web/.
#
# Usage:
#   ./deploy_js.sh            # full build (sbt compile + fastLinkJS + packClasspath)
#   ./deploy_js.sh --no-build # just copy artifacts (assumes prior sbt build)
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/scalac-web"

FASTOPT_DIR="$ROOT/compiler-js/target/scala3-compiler-sjs/scala-3.8.4-RC1-bin-SNAPSHOT-nonbootstrapped/scala3-compiler-fastopt"
CLASSPATH_BIN="$ROOT/compiler-js/target/scala3-compiler-sjs/scala-3.8.4-RC1-bin-SNAPSHOT-nonbootstrapped/classpath.bin"
LINKER_LIBS_BIN="$ROOT/compiler-js/target/scala3-compiler-sjs/scala-3.8.4-RC1-bin-SNAPSHOT-nonbootstrapped/linker-libs.bin"
INDEX_HTML="$ROOT/compiler-js/browser-test/index.html"

# --- Build step (unless --no-build) ---
if [[ "${1:-}" != "--no-build" ]]; then
  echo "==> Building compiler JS and packing classpath + linker libs..."
  sbt 'project scala3-compiler-sjs' 'compile; fullLinkJS; packClasspath; packLinkerLibs'
fi

# --- Verify artifacts exist ---
for f in "$FASTOPT_DIR/main.js" "$CLASSPATH_BIN" "$LINKER_LIBS_BIN" "$INDEX_HTML"; do
  if [[ ! -f "$f" ]]; then
    echo "ERROR: Missing $f"
    echo "Run without --no-build to do a full build first."
    exit 1
  fi
done

# --- Assemble output ---
echo "==> Assembling scalac-web/..."
rm -rf "$OUT"
mkdir -p "$OUT"

cp "$INDEX_HTML"          "$OUT/index.html"
cp "$FASTOPT_DIR/main.js" "$OUT/main.js"
cp "$CLASSPATH_BIN"       "$OUT/classpath.bin"
cp "$LINKER_LIBS_BIN"     "$OUT/linker-libs.bin"

# Copy source map if available
if [[ -f "$FASTOPT_DIR/main.js.map" ]]; then
  cp "$FASTOPT_DIR/main.js.map" "$OUT/main.js.map"
fi

# --- Summary ---
JS_SIZE=$(du -sh "$OUT/main.js" | cut -f1)
CP_SIZE=$(du -sh "$OUT/classpath.bin" | cut -f1)
LL_SIZE=$(du -sh "$OUT/linker-libs.bin" | cut -f1)
echo ""
echo "Done! scalac-web/ contents:"
echo "  index.html"
echo "  main.js           ($JS_SIZE)"
echo "  classpath.bin      ($CP_SIZE)"
echo "  linker-libs.bin    ($LL_SIZE)"
echo ""
echo "To serve:"
echo "  cd scalac-web && python3 -m http.server 8080"
