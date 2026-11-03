#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-/home/ubuntu/Android/Sdk}"
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
APK_SIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
APK="$PROJECT_ROOT/app/build/outputs/apk/release/app-release.apk"
METADATA="$PROJECT_ROOT/app/build/outputs/apk/release/output-metadata.json"
OUT_DIR="$PROJECT_ROOT/codex"
RELEASE_NOTES_FILE="${1:-}"

cd "$PROJECT_ROOT"
./gradlew assembleRelease --no-daemon --max-workers=1
test -x "$APK_SIGNER"
test -f "$APK"
"$APK_SIGNER" verify --print-certs "$APK"

VERSION="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["elements"][0]["versionName"])' "$METADATA")"
CODE="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["elements"][0]["versionCode"])' "$METADATA")"
PACKAGE="Andri-Android-${VERSION}-signed-release.zip"
NOTES="$OUT_DIR/Andri-Android-${VERSION}-install.txt"
CHECKSUMS="$OUT_DIR/Andri-Android-${VERSION}-SHA256SUMS.txt"

cat > "$NOTES" <<EOF
Andri Android $VERSION (versionCode $CODE)

This APK is signed with the persistent server release key.
Install it from Andri's in-app update prompt or open this APK on the phone.
It can update an earlier package signed with the persistent server release key without uninstalling or clearing app data.
EOF
if [[ -n "$RELEASE_NOTES_FILE" ]]; then
  test -f "$RELEASE_NOTES_FILE"
  {
    printf '\n'
    cat "$RELEASE_NOTES_FILE"
  } >> "$NOTES"
fi

sha256sum "$APK" > "$CHECKSUMS"
chmod 644 "$APK" "$NOTES" "$CHECKSUMS"
python3 - "$OUT_DIR/$PACKAGE" "$APK" "$NOTES" "$CHECKSUMS" <<'PY'
import pathlib, sys, zipfile
archive = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
    for path in map(pathlib.Path, sys.argv[2:]):
        zf.write(path, path.name)
PY
chmod 644 "$OUT_DIR/$PACKAGE"
sha256sum "$OUT_DIR/$PACKAGE"
printf 'Built and packaged %s\n' "$OUT_DIR/$PACKAGE"
