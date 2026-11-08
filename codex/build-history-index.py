#!/usr/bin/env python3
"""Index existing APKs after checking their release certificate and APK metadata."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

EXPECTED_CERT = 'ab9d091292bb569d7247f9d9e7bc3f3dec0b5269d68df44339f90334e430cb15'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--release-root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = args.release_root.resolve()
    tools = Path(os.environ.get('ANDROID_HOME', '/home/ubuntu/Android/Sdk')) / 'build-tools/35.0.0'
    existing = {}
    if args.output.exists():
        existing = {row['sha256']: row for row in json.loads(args.output.read_text())['releases']}
    latest_path = root / 'latest.json'
    latest = json.loads(latest_path.read_text()) if latest_path.exists() else {}
    notes_root = Path(__file__).resolve().parent
    entries = {}
    rejected = 0
    for apk in sorted(root.glob('*.apk')):
        if apk.is_symlink() or not re.fullmatch(r'[A-Za-z0-9._-]+\.apk', apk.name):
            rejected += 1
            continue
        result = subprocess.run([str(tools / 'apksigner'), 'verify', '--print-certs', str(apk)], capture_output=True, text=True)
        if result.returncode or f'Signer #1 certificate SHA-256 digest: {EXPECTED_CERT}' not in result.stdout:
            rejected += 1
            continue
        badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
        match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
        if not match or match[1] != 'com.codexserver.mobile':
            raise ValueError(f'Unexpected APK metadata: {apk.name}')
        code, version = int(match[2]), match[3]
        if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9]+)?', version):
            raise ValueError(f'Invalid version: {apk.name}')
        digest = hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest()
        notes_file = notes_root / f'release-notes-{version}.txt'
        notes = existing.get(digest, {}).get('notes', '')
        if digest == latest.get('sha256'):
            notes = latest.get('notes', notes)
        elif not notes and notes_file.exists():
            notes = notes_file.read_text().strip()
        row = dict(versionCode=code, versionName=version, apkPath=str(apk), sha256=digest,
                   sizeBytes=apk.stat().st_size, notes=notes)
        if code in entries and entries[code]['sha256'] != digest:
            raise ValueError(f'Conflicting signed APKs for versionCode {code}')
        entries[code] = row
    if not entries:
        raise ValueError('No trusted release APKs found')
    data = {'releases': sorted(entries.values(), key=lambda row: row['versionCode'], reverse=True)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    staged = args.output.with_name(f'.{args.output.name}.{os.getpid()}.tmp')
    staged.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    staged.chmod(0o644)
    staged.replace(args.output)
    print(f'Indexed {len(entries)} trusted release APKs; excluded {rejected} incompatible or untrusted APKs')

if __name__ == '__main__':
    main()
