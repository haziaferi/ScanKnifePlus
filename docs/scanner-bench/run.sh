#!/usr/bin/env bash
# Usage: docs/scanner-bench/run.sh PHOTOS LABELS WORK
#   PHOTOS  folder with books/, corpus/ and shots/{camera,glasscam}/ (README, "Photos")
#   LABELS  the hand-marked labels.json (may be missing: detection then runs, scoring of everything else waits for it)
#   WORK    folder for every derived image and per-photo output; keep it outside the repository or git-ignored
# Runs the PC side in order: upright PNGs and labels (bench.py prepare), their detection (bench.py oss-detect), the JVM half
# (ScannerBenchTest through the shared gradle-run.sh, SCANNER_BENCH_DIR=WORK), then scoring, their filters, the OCR images and results.md
# (bench.py score). PYTHON names an interpreter with OpenCV and numpy (default: python). The OCR device step is separate (README).
set -eu
[ $# -eq 3 ] || { sed -n 2,9p "$0"; exit 2; }
HERE="$(cd "$(dirname "$0")" && pwd)"
WT="$(cd "$HERE/../.." && pwd)"
PY="${PYTHON:-python}"
PHOTOS="$1"; LABELS="$2"; WORK="$3"
mkdir -p "$WORK"

"$PY" "$HERE/bench.py" prepare --photos "$PHOTOS" --labels "$LABELS" --work "$WORK"
"$PY" "$HERE/bench.py" oss-detect --work "$WORK"
export SCANNER_BENCH_DIR="$WORK"
# --rerun: the environment variable is not a task input, so Gradle would otherwise call the test up to date.
bash /c/Users/User/Downloads/Builds/wt/_briefs/gradle-run.sh "$WT" :scanner-core:test \
  --tests com.haziaferi.scanknifeplus.scanner.bench.ScannerBenchTest --rerun
"$PY" "$HERE/bench.py" score --work "$WORK"
