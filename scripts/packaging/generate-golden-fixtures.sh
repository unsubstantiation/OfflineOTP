#!/usr/bin/env bash
#
# Regenerates the desktop golden interop fixtures with deterministic, fixed salts/nonces using
# GoldenFixturesGenerator.kt. The generator is plain Kotlin over the sandbox-built :core output
# (no Gradle, no network); run compile-core.sh/compile-desktop.sh first.
#
# Output lands in a temporary directory and is compared byte-for-byte against the checked-in
# fixtures under desktop/src/test/resources/golden, so a drift in any codec fails here before the
# fixtures are ever replaced. Pass --write to replace the checked-in fixtures instead.
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
golden="$root/desktop/src/test/resources/golden"
core_out="$root/reports/kotlin-sandbox/desktop/core"
generator="$root/scripts/packaging/GoldenFixturesGenerator.kt"

if [[ ! -d "$core_out" ]]; then
    echo "Build :core first: scripts/kotlin-sandbox/compile-core.sh (or compile-desktop.sh)" >&2
    exit 2
fi

toolchain="${KOTLIN_SANDBOX_TOOLCHAIN:-${TMPDIR:-/tmp}/kotlin-sandbox-toolchain}"
KOTLINC="${KOTLINC:-$toolchain/kotlin/bin/kotlinc-jvm}"
JAVA_BIN="${JAVA_BIN:-$toolchain/jdk/jdk4py/java-runtime/bin/java}"
STDLIB="${KOTLIN_STDLIB:-$toolchain/kotlin/lib/kotlin-stdlib.jar}"
[[ -x "$KOTLINC" && -x "$JAVA_BIN" && -f "$STDLIB" ]] || {
    echo "Toolchain missing; run scripts/kotlin-sandbox/bootstrap-toolchain.sh" >&2
    exit 2
}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
"$KOTLINC" -nowarn -cp "$core_out" "$generator" -d "$work/gen"
"$JAVA_BIN" -cp "$work/gen:$core_out:$STDLIB" \
    org.shadowice.flocke.andotp.packaging.GoldenFixturesGeneratorKt "$golden" "$work/out"

if [[ "${1:-}" == "--write" ]]; then
    cp "$work/out"/* "$golden"/
    echo "Fixtures replaced in $golden; update GoldenInteropTest.expectedHashes if any changed."
    exit 0
fi

status=0
for fixture in expected-vault.json secrets.dat vault.properties otp_accounts.json.aes; do
    if cmp -s "$work/out/$fixture" "$golden/$fixture"; then
        echo "MATCH  $fixture"
    else
        echo "DRIFT  $fixture" >&2
        status=1
    fi
done
if [[ "$status" -eq 0 ]]; then
    echo "Regenerated fixtures are byte-identical to the checked-in golden set."
fi
exit "$status"
