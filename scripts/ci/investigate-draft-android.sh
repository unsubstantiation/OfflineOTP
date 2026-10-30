#!/usr/bin/env bash
# One-time, read-only investigation of the draft ZIP. Never execute the APK.
set -euo pipefail
work="${RUNNER_TEMP:?}/offlineotp-draft-investigation"
zip="$work/OfflineOTP-android-debug.zip"
apk="$work/OfflineOTP-fdroid-debug.apk"
expected_zip=b3a675fb7197d059969acfd7d8d91cdc8185eea47916ddd2be4e119cbf554aa4
mkdir -p "$work"

case "${1:-}" in
  inspect)
    # This is the CI artifact whose GitHub-reported digest matched the draft release asset.
    # Never print download errors: a redirect can contain short-lived credentials.
    if ! gh api "repos/$GITHUB_REPOSITORY/actions/artifacts/12938992220/zip" > "$zip" 2> "$work/download.err"; then
      echo '::error title=Artifact retrieval::Unable to retrieve the original GitHub Actions artifact.'
      exit 1
    fi
    actual=$(sha256sum "$zip" | cut -d' ' -f1)
    if [[ "$actual" != "$expected_zip" ]]; then
      echo '::error title=Artifact integrity::The downloaded ZIP does not match the draft release digest.'
      exit 1
    fi
    echo "::notice title=Artifact integrity::The original CI ZIP matches the draft release SHA-256 $actual"

    # Read only the three expected entries, into fixed paths, after enforcing size limits.
    # Do not extract archive-supplied paths or follow symlinks.
    python3 - "$zip" "$work" <<'PY'
import hashlib
from pathlib import Path
import re
import sys
import zipfile

archive, directory = Path(sys.argv[1]), Path(sys.argv[2])
expected = {'OfflineOTP-fdroid-debug.apk', 'SHA256SUMS.txt', 'signing-certificate.txt'}
with zipfile.ZipFile(archive) as z:
    files = [item for item in z.infolist() if not item.is_dir()]
    names = [Path(item.filename).name for item in files]
    if len(names) != len(expected) or set(names) != expected:
        raise SystemExit('Unexpected or duplicate files in the artifact archive')
    for item in files:
        if item.file_size > 100_000_000 or (item.external_attr >> 16) & 0o170000 == 0o120000:
            raise SystemExit('Unreasonable artifact entry size or symbolic link')
        target = directory / Path(item.filename).name
        with z.open(item) as source, target.open('wb') as destination:
            while block := source.read(1024 * 1024):
                destination.write(block)

apk = directory / 'OfflineOTP-fdroid-debug.apk'
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
checksums = (directory / 'SHA256SUMS.txt').read_text(encoding='utf-8')
match = re.fullmatch(r'([0-9a-fA-F]{64})\s+\*?OfflineOTP-fdroid-debug\.apk\s*', checksums)
if not match or digest.lower() != match.group(1).lower():
    raise SystemExit('APK does not match the checksum bundled in the artifact')
print(f'::notice title=APK digest::SHA-256 {digest} (matches bundled checksum)')
PY
    "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs "$apk" > "$work/verified-certificate.txt"
    apkanalyzer manifest print "$apk" > "$work/manifest.xml"
    python3 - "$work" <<'PY'
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

work = Path(sys.argv[1])
cert = (work / 'verified-certificate.txt').read_text(encoding='utf-8')
old_cert = (work / 'signing-certificate.txt').read_text(encoding='utf-8')
pattern = r'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)'
a, b = re.search(pattern, cert), re.search(pattern, old_cert)
if not a or not b or a.group(1).lower() != b.group(1).lower():
    raise SystemExit('Verified signing certificate does not match the artifact metadata')
print(f'::notice title=Verified APK signature::Signer SHA-256 {a.group(1).lower()}')

root = ET.parse(work / 'manifest.xml').getroot()
ns = '{http://schemas.android.com/apk/res/android}'
app = root.find('application')
if app is None:
    raise SystemExit('APK has no application manifest')
perms = sorted(node.get(ns + 'name', '') for node in root if node.tag.startswith('uses-permission'))
def escape(value):
    return str(value).replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
print(f"::notice title=Packaged APK::Application ID {escape(root.get('package'))}; debuggable {escape(app.get(ns + 'debuggable'))}; allowBackup {escape(app.get(ns + 'allowBackup'))}")
print('::notice title=Packaged permissions::' + escape(', '.join(perms)))
PY
    if python3 scripts/check_debug_manifest.py "$work/manifest.xml" > "$work/policy.txt" 2>&1; then
      echo '::notice title=Manifest policy::The packaged debug manifest passed the limited permission and flag checks; this is not a malware scan.'
    else
      python3 - "$work/policy.txt" <<'PY'
from pathlib import Path
import sys
text = Path(sys.argv[1]).read_text(encoding='utf-8').strip()
text = text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
print('::warning title=Manifest policy::' + text[:3000])
PY
    fi
    ;;
  scan)
    db="$work/clamdb"
    mkdir -p "$db"
    cat > "$work/freshclam.conf" <<EOF
DatabaseDirectory $db
DatabaseOwner runner
DatabaseMirror database.clamav.net
Checks 1
MaxAttempts 1
EOF
    if ! freshclam --config-file="$work/freshclam.conf" --stdout > "$work/freshclam.log" 2>&1; then
      echo '::error title=ClamAV unavailable::Signature update failed; no antivirus verdict was obtained.'
      exit 1
    fi
    if ! compgen -G "$db/main.c[vl]d" > /dev/null || ! compgen -G "$db/daily.c[vl]d" > /dev/null; then
      echo '::error title=ClamAV unavailable::The main or daily signature database is missing; no antivirus verdict was obtained.'
      exit 1
    fi
    version=$(clamscan --database="$db" --version)
    echo "::notice title=ClamAV version::$version"
    set +e
    clamscan --database="$db" --scan-archive=yes --alert-exceeds-max=yes --alert-encrypted=yes \
      --max-filesize=100M --max-scansize=500M --max-files=20000 --max-recursion=20 \
      --max-scantime=300000 "$zip" "$apk" > "$work/clamscan.log" 2>&1
    result=$?
    set -e
    python3 - "$work/clamscan.log" "$result" <<'PY'
from pathlib import Path
import sys
text = Path(sys.argv[1]).read_text(encoding='utf-8', errors='replace')
status = int(sys.argv[2])
interesting = [line.strip() for line in text.splitlines() if (
    ' FOUND' in line or line.startswith(('Scanned files:', 'Infected files:', 'Data scanned:', 'Time:', 'ERROR:')))]
message = ('exit=' + str(status) + '; ' + '; '.join(interesting))
message = message.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
level = 'notice' if status == 0 else 'error'
print(f'::{level} title=ClamAV scan::{message[:3500]}')
PY
    if [[ "$result" != 0 ]]; then
      echo '::error title=ClamAV scan::The scan detected something or failed; consult its output. This is not a clean verdict.'
      exit 1
    fi
    echo '::notice title=ClamAV limitations::No ClamAV detection does not prove the ZIP or APK is safe and does not resolve the Firefox warning.'
    ;;
  *) echo 'Expected inspect or scan' >&2; exit 2 ;;
esac
