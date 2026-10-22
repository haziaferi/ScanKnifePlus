#!/usr/bin/env bash
# Usage: docs/ocr-bench/run.sh [--camera SET] [SET=image-folder ...]
#        docs/ocr-bench/run.sh --field SET=photo-folder [--field SET2=folder2 ...]
# Fetches and verifies the language data, pushes page image sets to the phone, runs OcrBenchmarkTest through the shared device-test.sh,
# collects the pulled output into docs/ocr-bench/results/ and scores it into docs/ocr-bench/results.md.
#   --camera SET  copies the five newest photos from the phone's Camera folder, oldest first as pages a to e, into scans/SET/ as raw
#                 photos (<page>.photo.jpg), and runs them as set SET.
# The clean renders (set "clean") always run as the baseline.
#   --field SET=DIR  field mode instead (no ground truth): every <page>.photo.jpg in DIR, with DIR/field.tsv if present (README, "Field mode").
#                 Runs fieldDetect and the four field configurations, FIELD_CHUNK photos per instrumentation (default 4), collects into the
#                 git-ignored docs/ocr-bench/field-out/SET/ and writes docs/ocr-bench/field-results.md. FIELD_METHODS overrides the method list.
#                 The clean baseline only runs when plain sets are given too.
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
FIELD_SETS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --field) FIELD_SETS+=("$2"); shift 2 ;;
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

# Field mode. Photos are pushed a chunk at a time: one instrumentation per method and chunk keeps each run short enough for BgDetect.
if [ "${#FIELD_SETS[@]}" -gt 0 ]; then
  # The OSD shim (native/tess_osd.c) goes into the git-ignored androidTest jniLibs, so only the test APK carries it.
  SDK="$(sed -n 's/^sdk\.dir=//p' "$WT/local.properties" 2>/dev/null | tr -d '\r' | sed 's/\\:/:/g; s/\\\\/\//g')"
  SDK="${SDK:-${ANDROID_HOME:-}}"
  NDK="$(ls -d "$SDK"/ndk/*/ 2>/dev/null | sort -V | tail -1)"
  CLANG="$(ls "$NDK"toolchains/llvm/prebuilt/*/bin/aarch64-linux-android24-clang{.cmd,} 2>/dev/null | head -1)"
  [ -n "$CLANG" ] || { echo "no NDK clang for arm64 under $SDK/ndk: the OSD shim cannot be built"; exit 1; }
  mkdir -p "$WT/app/src/androidTest/jniLibs/arm64-v8a"
  "$CLANG" -shared -fPIC -O2 -Wall -o "$WT/app/src/androidTest/jniLibs/arm64-v8a/libtessosd.so" "$HERE/native/tess_osd.c" -ldl
  OUT="$HERE/field-out"
  PULLED="$WT/app/build/outputs/connected_android_test_additional_output"
  CHUNK="${FIELD_CHUNK:-4}"
  for method in ${FIELD_METHODS:-fieldDetect fieldFast300 fieldFast400 fieldBest300 fieldBest400}; do
    case "$method" in
      fieldDetect) SUB=detect ;;
      *) SUB="$(echo "${method#field}" | sed -E 's/^([A-Za-z]+)([0-9]+)$/\1-\2/' | tr '[:upper:]' '[:lower:]')" ;;
    esac
    for pair in "${FIELD_SETS[@]}"; do
      SET="${pair%%=*}"; DIR="${pair#*=}"
      rm -rf "${OUT:?}/$SET/$SUB"
      mkdir -p "$OUT/$SET"
      [ -f "$DIR/field.tsv" ] && cp "$DIR/field.tsv" "$OUT/$SET/field.tsv"
      mapfile -t PHOTOS < <(ls "$DIR" | grep -i -E '^[^.]+\.photo\.(jpe?g|png)$' | sort)
      for ((i = 0; i < ${#PHOTOS[@]}; i += CHUNK)); do
        adb shell rm -rf "$DEVICE_DIR"
        adb shell mkdir -p "$DEVICE_DIR/field/$SET"
        [ -f "$DIR/field.tsv" ] && adb push "$(host "$DIR/field.tsv")" "$DEVICE_DIR/field/$SET/field.tsv" >/dev/null
        for photo in "${PHOTOS[@]:i:CHUNK}"; do
          adb push "$(host "$DIR/$photo")" "$DEVICE_DIR/field/$SET/$photo" >/dev/null
        done
        echo "[field] $method $SET: ${PHOTOS[*]:i:CHUNK}"
        rm -rf "$PULLED"
        bash "$BRIEFS/device-test.sh" "$WT" "com.haziaferi.scanknifeplus.ocr.OcrBenchmarkTest#$method"
        SRC="$(find "$PULLED" -type d -name ocr-field | head -1)"
        [ -n "$SRC" ] || { echo "no ocr-field output pulled from the device for $method"; exit 1; }
        cp -r "$SRC"/. "$OUT/"
      done
    done
  done
  adb shell rm -rf "$DEVICE_DIR"
  python "$HERE/score.py" --field "$OUT" --out "$HERE/field-results.md"
  [ "${#SETS[@]}" -gt 1 ] || exit 0
fi

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
