#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-/home/ubuntu/Android/Sdk}"
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
APK_SIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
APK="$PROJECT_ROOT/app/build/outputs/apk/release/app-release.apk"
METADATA="$PROJECT_ROOT/app/build/outputs/apk/release/output-metadata.json"
RELEASE_ROOT="/home/ubuntu/codex/mobile-client/releases"
NOTES_INPUT="${1:-Server-built Andri Android update.}"
if [[ "$NOTES_INPUT" == @* ]]; then
  NOTES_FILE="${NOTES_INPUT#@}"
  test -f "$NOTES_FILE"
  NOTES="$(cat "$NOTES_FILE")"
else
  NOTES="$NOTES_INPUT"
fi

test -f "$APK"
SIGNER_OUTPUT="$("$APK_SIGNER" verify --print-certs "$APK")"
EXPECTED_CERT="ab9d091292bb569d7247f9d9e7bc3f3dec0b5269d68df44339f90334e430cb15"
if ! grep -Fq "Signer #1 certificate SHA-256 digest: $EXPECTED_CERT" <<<"$SIGNER_OUTPUT"; then
  echo "APK is not signed with this server's persistent release certificate" >&2
  exit 1
fi
mkdir -p "$RELEASE_ROOT"

python3 - "$APK" "$METADATA" "$RELEASE_ROOT" "$NOTES" <<'PY'
import hashlib, json, os, pathlib, shutil, sys, time
apk = pathlib.Path(sys.argv[1])
metadata_root = json.loads(pathlib.Path(sys.argv[2]).read_text())
metadata = metadata_root['elements'][0]
release_root = pathlib.Path(sys.argv[3])
notes = sys.argv[4]
if metadata_root.get('applicationId') != 'com.codexserver.mobile':
    raise SystemExit('Unexpected application ID')
version = metadata['versionName']
code = int(metadata['versionCode'])
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
manifest_path = release_root / 'latest.json'
if manifest_path.exists():
    current = json.loads(manifest_path.read_text())
    current_code = int(current.get('versionCode', 0))
    if code < current_code:
        raise SystemExit(f'versionCode {code} must be greater than published versionCode {current_code}')
    if code == current_code and (version != current.get('versionName') or digest != current.get('sha256')):
        raise SystemExit('Same-version metadata updates are allowed only for the identical published APK')
target = release_root / f"codex-server-{version}-{digest[:12]}.apk"
staged = release_root / f".{target.name}.{os.getpid()}.uploading"
shutil.copyfile(apk, staged)
if hashlib.sha256(staged.read_bytes()).hexdigest() != digest:
    raise SystemExit('Staged APK checksum mismatch')
os.chmod(staged, 0o644)
os.replace(staged, target)
manifest = {
    'versionCode': code,
    'versionName': version,
    'apkPath': str(target),
    'sha256': digest,
    'sizeBytes': target.stat().st_size,
    'notes': notes,
}
# Preserve immutable version records and maintain the catalog before exposing latest.
history_path = release_root / 'history.json'
rows = json.loads(history_path.read_text())['releases'] if history_path.exists() else []
if manifest_path.exists():
    rows.append(json.loads(manifest_path.read_text()))
rows.append(manifest)
by_code = {}
for row in rows:
    row_code = int(row['versionCode'])
    previous = by_code.get(row_code)
    if previous and previous['sha256'] != row['sha256']:
        raise SystemExit('Conflicting historical release metadata')
    by_code[row_code] = row
history = {'releases': sorted(by_code.values(), key=lambda row: int(row['versionCode']), reverse=True)}
history_stage = release_root / f".history.json.{os.getpid()}.uploading"
history_stage.write_text(json.dumps(history, ensure_ascii=False, indent=2) + '\n')
os.chmod(history_stage, 0o644)
os.replace(history_stage, history_path)
record_stage = release_root / f".release-{code}.json.{os.getpid()}.uploading"
record_stage.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
os.chmod(record_stage, 0o644)
os.replace(record_stage, release_root / f"release-{code}.json")
manifest_stage = release_root / f".latest.json.{os.getpid()}.uploading"
manifest_stage.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
os.chmod(manifest_stage, 0o644)
os.replace(manifest_stage, release_root / 'latest.json')
print(f"Published {version} ({digest}) to the local update directory")
PY
