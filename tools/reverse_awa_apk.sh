#!/usr/bin/env bash
# Reproducible AWA APK reverse-engineering pipeline.
# Generated Java/Smali is not the original source project.
set -Eeuo pipefail

usage() {
  cat <<'HELP'
Usage: bash tools/reverse_awa_apk.sh <awa.apk|awa-source.zip> [output-directory]

Required: Java 17+, Python 3, JADX CLI, Apktool.
Optional environment:
  JADX_BIN       JADX executable (default: jadx)
  APKTOOL_BIN    Apktool executable (default: apktool)
  APKTOOL_JAR    Apktool JAR path; takes precedence over APKTOOL_BIN

A ZIP input must contain exactly one APK. Input/output under reverse/ are
git-ignored. Review the source license and redistribution rights before
committing generated third-party decompilation.
HELP
}

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ] || [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
  usage
  exit 0
fi

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
INPUT_PATH=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
JADX_BIN=$(printenv JADX_BIN || true)
APKTOOL_BIN=$(printenv APKTOOL_BIN || true)
APKTOOL_JAR=$(printenv APKTOOL_JAR || true)
[ -n "$JADX_BIN" ] || JADX_BIN=jadx
[ -n "$APKTOOL_BIN" ] || APKTOOL_BIN=apktool

[ -f "$INPUT_PATH" ] || { echo "[error] input file not found: $INPUT_PATH" >&2; exit 2; }
command -v java >/dev/null 2>&1 || { echo "[error] Java is required." >&2; exit 3; }
command -v python3 >/dev/null 2>&1 || { echo "[error] Python 3 is required." >&2; exit 3; }
command -v "$JADX_BIN" >/dev/null 2>&1 || {
  echo "[error] JADX not found: $JADX_BIN" >&2
  echo "Install from https://github.com/skylot/jadx/releases or set JADX_BIN." >&2
  exit 3
}
if [ -n "$APKTOOL_JAR" ]; then
  [ -f "$APKTOOL_JAR" ] || { echo "[error] APKTOOL_JAR not found: $APKTOOL_JAR" >&2; exit 3; }
  APKTOOL_MODE=jar
elif command -v "$APKTOOL_BIN" >/dev/null 2>&1; then
  APKTOOL_MODE=bin
else
  echo "[error] Apktool not found. See https://github.com/iBotPeaches/Apktool/releases." >&2
  echo "Or set APKTOOL_JAR=/path/to/apktool_3.0.3.jar." >&2
  exit 3
fi

TMP_DIR=$(mktemp -d)
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT
APK_PATH="$TMP_DIR/awa.apk"

case "$INPUT_PATH" in
  *.apk|*.APK)
    cp -- "$INPUT_PATH" "$APK_PATH"
    ;;
  *.zip|*.ZIP)
    python3 - "$INPUT_PATH" "$APK_PATH" <<'PY'
import sys
import zipfile
from pathlib import PurePosixPath

source, output = sys.argv[1:]
with zipfile.ZipFile(source) as archive:
    candidates = [i for i in archive.infolist()
                  if not i.is_dir() and PurePosixPath(i.filename).suffix.lower() == ".apk"]
    if len(candidates) != 1:
        raise SystemExit(f"Expected exactly one APK in ZIP; found {len(candidates)}")
    item = candidates[0]
    if item.file_size > 150 * 1024 * 1024:
        raise SystemExit("Embedded APK exceeds the 150 MiB safety limit")
    with archive.open(item) as src, open(output, "wb") as dst:
        while True:
            chunk = src.read(1024 * 1024)
            if not chunk:
                break
            dst.write(chunk)
PY
    ;;
  *)
    echo "[error] input must be an APK or a ZIP containing one APK." >&2
    exit 2
    ;;
esac

python3 - "$APK_PATH" <<'PY'
import sys, zipfile
path = sys.argv[1]
if not zipfile.is_zipfile(path):
    raise SystemExit("Input is not a valid APK/ZIP container")
with zipfile.ZipFile(path) as apk:
    names = set(apk.namelist())
    if "AndroidManifest.xml" not in names:
        raise SystemExit("AndroidManifest.xml is missing")
    if not any(n == "classes.dex" or (n.startswith("classes") and n.endswith(".dex")) for n in names):
        raise SystemExit("No DEX bytecode found")
PY

APK_SHA256=$(sha256sum "$APK_PATH" | awk '{print $1}')
if [ "$#" -eq 2 ]; then
  OUT_DIR=$2
else
  OUT_DIR="$ROOT_DIR/reverse/output/awa-${APK_SHA256:0:12}"
fi
mkdir -p "$(dirname "$OUT_DIR")"
OUT_DIR=$(cd "$(dirname "$OUT_DIR")" && pwd)/$(basename "$OUT_DIR")
[ ! -e "$OUT_DIR" ] || { echo "[error] output already exists: $OUT_DIR" >&2; exit 4; }
mkdir -p "$OUT_DIR"
cp -- "$APK_PATH" "$OUT_DIR/input.apk"

JADX_VERSION=$("$JADX_BIN" --version 2>&1 | head -n 1 || true)
if [ "$APKTOOL_MODE" = jar ]; then
  APKTOOL_VERSION=$(java -jar "$APKTOOL_JAR" --version 2>&1 | head -n 1 || true)
else
  APKTOOL_VERSION=$("$APKTOOL_BIN" --version 2>&1 | head -n 1 || true)
fi

echo "[1/2] JADX: DEX to Java-like source..."
"$JADX_BIN" --show-bad-code --deobf -d "$OUT_DIR/jadx" "$APK_PATH"

echo "[2/2] Apktool: resources, manifest and Smali..."
if [ "$APKTOOL_MODE" = jar ]; then
  java -jar "$APKTOOL_JAR" d -f -o "$OUT_DIR/apktool" "$APK_PATH"
else
  "$APKTOOL_BIN" d -f -o "$OUT_DIR/apktool" "$APK_PATH"
fi

cat > "$OUT_DIR/REPORT.md" <<EOF
# AWA APK reverse-engineering output

- Input: $(basename "$INPUT_PATH")
- APK SHA-256: $APK_SHA256
- JADX: $JADX_VERSION
- Apktool: $APKTOOL_VERSION
- Generated (UTC): $(date -u '+%Y-%m-%dT%H:%M:%SZ')

## Output

- jadx/: decompiled Java-like source and supported decoded resources.
- apktool/: Smali, decoded manifest and Android resources.
- SHA256SUMS.txt: hashes for generated output files.

## Limitations

This is machine-generated decompilation, not the original Kotlin/Java project.
Names, comments, control flow, generics, resources and build configuration may
be missing or changed. Successful compilation and runtime compatibility are
not guaranteed. Review license and redistribution permission before reuse.
EOF

(
  cd "$OUT_DIR"
  find jadx apktool -type f -print0 | sort -z | xargs -0 -r sha256sum > SHA256SUMS.txt
  echo "JADX files: $(find jadx -type f | wc -l | tr -d ' ')"
  echo "Apktool files: $(find apktool -type f | wc -l | tr -d ' ')"
) > "$OUT_DIR/FILE_COUNTS.txt"

echo "[done] output: $OUT_DIR"
echo "[note] decompiled output is not an original source project."
