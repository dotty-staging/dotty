#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"

if ! type "rg" > /dev/null; then
  echo "Needs `ripgrep` installed"
  exit 1
fi

if ! type "diff" > /dev/null; then
  echo "Needs `diffutils` or equivalent installed"
  exit 1
fi

count_diff () {
  echo $2: added $(cat $1 | rg "^[+]" | wc -l), removed $(cat $1 | rg "^[-]" | wc -l)
}

# Create the original diff
diff --unified --recursive --ignore-all-space ../scala2/src/library/scala/ ../scala2-library-cc/src/scala/ > ./base.diff && true
diff --unified --ignore-all-space ../scala2/src/library/scala/collection/immutable/LazyList.scala ../scala2-library-cc/src/scala/collection/immutable/LazyListIterable.scala >> ./base.diff && true

# Clean up
## Strip out the "Only in..."
rg -v "^Only in" ./base.diff > ./base-no-only-in.diff
## Strip empty line changes
rg -v "^[+-]\s*$" ./base.diff > ./base-no-spaces.diff
## Strip out capture checking imports
rg -v "^\+import (language\.experimental\.captureChecking|caps\..+|.*uncheckedCaptures)" ./base-no-spaces.diff > ./base-no-imports.diff
## Strip out all diffs involving comments
rg -v "^[+-]\s*(/\*|\*($|\s+)|\*/$|//)" ./base-no-imports.diff > ./base-no-comments.diff
## Take out changes only
rg "^[+-]\s" ./base-no-comments.diff > changes.diff
count_diff ./changes.diff "Total changes"

# Definitions
rg "^[+-].+\b(def|val|var|class|trait)\b" ./changes.diff > changes-defs.diff
count_diff ./changes-defs.diff "Total Definition Changes"
## With capture sets
rg "\^|->" ./changes-defs.diff > changes-defs-add-capture-sets.diff
count_diff ./changes-defs-add-capture-sets.diff "Definitions with capture sets"
## Methods, Variables and Classes
rg "^[+-].+\b(def)\b" ./changes-defs-add-capture-sets.diff > changes-methods.diff
count_diff ./changes-methods.diff "- Total Methods"
rg "^[+-].+\b(val|var)\b" ./changes-defs-add-capture-sets.diff > changes-variables.diff
count_diff ./changes-variables.diff "- Total Local Variables"
rg "^[+-].+\b(class|trait)\b" ./changes-defs-add-capture-sets.diff > changes-classes.diff
count_diff ./changes-classes.diff "- Total Classes"
## Only universal captures
rg -v "(\^\{|->)" ./changes-defs-add-capture-sets.diff > changes-defs-universal-capture-sets.diff
count_diff ./changes-defs-universal-capture-sets.diff "Definitions with universal capture sets only"
## Only capture sets on return value
rg ":.*?(\^|->\{)[^)]*?=" ./changes-defs-add-capture-sets.diff > changes-defs-return-capture-sets.diff
count_diff ./changes-defs-return-capture-sets.diff "Definitions with capture sets only in return"
## Restrict to pure
rg -- "->\s*($|[^{])" ./changes.diff > changes-defs-restrict-to-pure.diff
count_diff ./changes-defs-restrict-to-pure.diff "Definitions that are restricted to pure"
## Reach capabilities
rg -- "\^\{[^}]*\*[^}]*\}" ./changes.diff > changes-reachcaps.diff
count_diff ./changes-reachcaps.diff "Definitions with reach capabilities"

echo

# Unsafe casts
rg -- "asInstanceOf" ./changes.diff > changes-casts.diff
count_diff ./changes-casts.diff "Unsafe casts"
# Unsafe capture set removals
rg -- "\b(uncheckedCaptures|untrackedCaptures|unsafeAssumePure)\b" ./changes.diff > changes-unsafe.diff
count_diff ./changes-unsafe.diff "Unsafe capture set removals"
