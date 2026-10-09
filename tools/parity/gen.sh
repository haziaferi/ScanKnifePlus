#!/usr/bin/env bash
# Regenerates the parity fixtures in scanner-core/src/test/resources/parity/ by running OpenScan's original Dart code.
# Usage: tools/parity/gen.sh /path/to/OpenScan [dart executable]
set -euo pipefail

OPENSCAN="${1:?usage: gen.sh /path/to/OpenScan [dart]}"
DART="${2:-dart}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../../scanner-core/src/test/resources/parity"

# Copy only the pure-Dart sources (no Flutter imports) into this throwaway package.
rm -rf "$HERE/lib"
mkdir -p "$HERE/lib/core/cv/models" "$HERE/lib/core/image_filter/utils"
cp "$OPENSCAN"/lib/core/cv/models/*.dart "$HERE/lib/core/cv/models/"
cp "$OPENSCAN"/lib/core/cv/edge_detection.dart "$HERE/lib/core/cv/"
cp "$OPENSCAN"/lib/core/image_filter/utils/image_filter_utils.dart "$HERE/lib/core/image_filter/utils/"

echo "OpenScan commit: $(git -C "$OPENSCAN" rev-parse HEAD)"
cd "$HERE"
"$DART" pub get >/dev/null
mkdir -p "$OUT"
for gen in bin/gen_*.dart; do
  "$DART" run "$gen" "$OUT"
done
