#!/bin/sh
# Rebuilds app/src/main/assets/bml/bml.js for old Android System WebView (down to Chrome 55).
# Input: the original modern bundle (web-bml build) as $1. Needs node + npm (esbuild is fetched on demand).
# The only syntax esbuild cannot lower is a regex lookbehind (Chrome 62+), which is rewritten first
# (`(?<=\s*)` / `(?=\s*)` are always-true, so the pattern is equivalent without them).
set -e
IN="$1"; OUT="$(dirname "$0")/../app/src/main/assets/bml/bml.js"
[ -f "$IN" ] || { echo "usage: $0 bml.modern.js"; exit 1; }
sed 's|/(?<=\\s\*):(focus\|active)(?=\\s\*)/i|/:(focus\|active)/i|' "$IN" > "$OUT.tmp.js"
npx --yes esbuild@0.21.5 "$OUT.tmp.js" --target=chrome55 --outfile="$OUT"
rm -f "$OUT.tmp.js"
