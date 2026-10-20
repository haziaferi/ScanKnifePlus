#!/usr/bin/env bash
# Usage: docs/ocr-bench/run.sh [--camera SET] [SET=image-folder ...]
# Fetches and verifies the language data, pushes page image sets to the phone, runs OcrBenchmarkTest through the shared device-test.sh,
# collects the pulled output into docs/ocr-bench/results/ and scores it into docs/ocr-bench/results.md.
#   --camera SET  copies the five newest photos from the phone's Camera folder, oldest first as pages a to e, into scans/SET/ as raw
#                 photos (<page>.photo.jpg), and runs them as set SET.
# The clean renders (set "clean") always run as the baseline.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
WT="$(cd "$HERE/../.." && pwd)"
BRIEFS=/c/Users/User/Downloads/Builds/wt/_briefs
export ANDROID_SERIAL="${ANDROID_SERIAL:-8ad59cbb}"
# Git Bash would rewrite the device paths into Windows paths; only adb gets the conversion switched off, with host paths converted by hand.
adb() { MSYS_NO_PATHCONV=1 command adb "$@"; }
host() { cygpath -w "$1" 2>/dev/null || echo "$1"; }
DEVICE_DIR=/sdcard/Android/data/com.haziaferi.scanknifeplus/files/ocr-bench
CAMERA_DIR=/sdcard/DCIM/Camera

python "$HERE/fetch_tessdata.py"

SETS=("clean=$HERE/corpus/clean")
while [ $# -gt 0 ]; do
  case "$1" in
    --camera)
      SET="$2"; shift 2
      DEST="$HERE/scans/$SET"
      mkdir -p "$DEST"
      mapfile -t PAGES < <(tail -n +2 "$HERE/corpus/pages.tsv" | cut -f1)
      mapfile -t PHOTOS < <(adb shell "ls -t $CAMERA_DIR/*.jpg" | tr -d '\r' | head -n "${#PAGES[@]}" | tac)
      [ "${#PHOTOS[@]}" -eq "${#PAGES[@]}" ] || { echo "need ${#PAGES[@]} photos in $CAMERA_DIR, found ${#PHOTOS[@]}"; exit 1; }
      for i in "${!PAGES[@]}"; do
        echo "${PAGES[$i]} <- ${PHOTOS[$i]}"
        adb pull "${PHOTOS[$i]}" "$(host "$DEST/${PAGES[$i]}.photo.jpg")" >/dev/null
      done
      SETS+=("$SET=$DEST")
      ;;
    *) SETS+=("$1"); shift ;;
  esac
done

for pair in "${SETS[@]}"; do
  python "$HERE/score.py" --check-images "${pair#*=}"
done
for pair in "${SETS[@]}"; do
  rm -rf "$HERE/results/${pair%%=*}"
done
mkdir -p "$HERE/results"

# One instrumentation per test method: a single long run got the process killed by the 6T's background-drain detector (BgDetect).
# The uninstall after each run removes the pushed images, so they are pushed again every time.
PULLED="$WT/app/build/outputs/connected_android_test_additional_output"
for method in fast300 fast400 best300 best400 layoutPsm; do
  adb shell rm -rf "$DEVICE_DIR"
  adb shell mkdir -p "$DEVICE_DIR/in"
  adb push "$(host "$HERE/corpus/pages.tsv")" "$DEVICE_DIR/pages.tsv" >/dev/null
  for pair in "${SETS[@]}"; do
    adb push "$(host "${pair#*=}")" "$DEVICE_DIR/in/${pair%%=*}" >/dev/null
  done
  rm -rf "$PULLED"
  bash "$BRIEFS/device-test.sh" "$WT" "com.haziaferi.scanknifeplus.ocr.OcrBenchmarkTest#$method"
  SRC="$(find "$PULLED" -type d -name ocr-bench | head -1)"
  [ -n "$SRC" ] || { echo "no ocr-bench output pulled from the device for $method"; exit 1; }
  cp -r "$SRC"/. "$HERE/results/"
done
python "$HERE/score.py"
