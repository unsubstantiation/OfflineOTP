#!/usr/bin/env bash
#
# Strict Android-boundary check for the shared :core module.
#
# Unlike run.sh (which compiles core + app together against android stubs), this compiles ONLY
# core/src/main and core/src/test and puts no android.* or androidx.* stub on the classpath. A core
# source that reaches for an Android API therefore fails to compile, which is the point: the core
# must stay reusable by the desktop JVM client. The third-party stubs are limited to the libraries
# the core actually declares (see core/build.gradle); today that is commons-codec only.
set -euo pipefail

SANDBOX="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SANDBOX/../.." && pwd)"
CORE_MAIN="$ROOT/core/src/main/kotlin"
CORE_TEST="$ROOT/core/src/test/kotlin"
OUT="${KOTLIN_CORE_OUT:-$ROOT/reports/kotlin-sandbox/core}"

KOTLINC="${KOTLINC:-$(command -v kotlinc-jvm || command -v kotlinc || true)}"
JAVA_BIN="${JAVA_BIN:-$(command -v java || true)}"
if [[ -z "$KOTLINC" || -z "$JAVA_BIN" ]]; then
    TOOLCHAIN="${KOTLIN_SANDBOX_TOOLCHAIN:-${TMPDIR:-/tmp}/kotlin-sandbox-toolchain}"
    if [[ -x "$TOOLCHAIN/kotlin/bin/kotlinc-jvm" && -x "$TOOLCHAIN/jdk/jdk4py/java-runtime/bin/java" ]]; then
        KOTLINC="$TOOLCHAIN/kotlin/bin/kotlinc-jvm"
        JAVA_BIN="$TOOLCHAIN/jdk/jdk4py/java-runtime/bin/java"
    else
        echo "kotlinc and java are required; run scripts/kotlin-sandbox/bootstrap-toolchain.sh" >&2
        exit 2
    fi
fi
if [[ -z "${JAVA_HOME:-}" ]]; then
    JAVA_HOME="$(cd "$(dirname "$(readlink -f "$JAVA_BIN")")/.." && pwd)"
    export JAVA_HOME
fi
export PATH="$(dirname "$(readlink -f "$JAVA_BIN")"):$PATH"

STDLIB="${KOTLIN_STDLIB:-}"
if [[ -z "$STDLIB" ]]; then
    KOTLINC_REAL="$(readlink -f "$KOTLINC")"
    for candidate in "$(dirname "$KOTLINC_REAL")/../lib/kotlin-stdlib.jar" "$(dirname "$KOTLINC")/../lib/kotlin-stdlib.jar"; do
        if [[ -f "$candidate" ]]; then STDLIB="$(cd "$(dirname "$candidate")" && pwd)/$(basename "$candidate")"; break; fi
    done
fi
[[ -f "$STDLIB" ]] || { echo "kotlin-stdlib.jar not found; set KOTLIN_STDLIB" >&2; exit 2; }

mapfile -d '' CORE_SOURCES < <(find "$CORE_MAIN" "$CORE_TEST" -name '*.kt' -print0 | sort -z)
[[ ${#CORE_SOURCES[@]} -gt 0 ]] || { echo "no core sources found under $CORE_MAIN" >&2; exit 2; }

# Deliberately NO stubs/android and NO stubs/androidx: this is the Android-free guarantee. Only the
# pure-JVM third-party stubs core declares (commons-codec, org.json, and the PC/SC-capable portable
# yubikit core/oath/fido surface — never the yubikit android/nfc/usb transport stubs).
YUBIKIT_CORE_STUBS=(
    "$SANDBOX"/stubs/com/yubico/yubikit/core.kt
    "$SANDBOX"/stubs/com/yubico/yubikit/commandstate.kt
    "$SANDBOX"/stubs/com/yubico/yubikit/smartcard.kt
    "$SANDBOX"/stubs/com/yubico/yubikit/oath.kt
    "$SANDBOX"/stubs/com/yubico/yubikit/fidoclient.kt
    "$SANDBOX"/stubs/com/yubico/yubikit/clientdata.kt
    "$SANDBOX"/stubs/com/yubico/yubikit/webauthn.kt
)
THIRD_PARTY_STUBS=("$SANDBOX"/stubs/org/apache/commons/codec/binary/*.kt
    "$SANDBOX"/stubs/org/json/*.kt
    "${YUBIKIT_CORE_STUBS[@]}")
mapfile -d '' JUNIT_SHIM < <(find "$ROOT/scripts/jvm-tests" -name '*.kt' -print0 | sort -z)

rm -rf "$OUT"
mkdir -p "$OUT"

echo "kotlinc: $KOTLINC"
echo "core:    ${#CORE_SOURCES[@]} files (no android/androidx stubs on classpath)"
echo "output:  $OUT"

"$KOTLINC" -jvm-target 1.8 -nowarn \
    "${CORE_SOURCES[@]}" "${THIRD_PARTY_STUBS[@]}" "${JUNIT_SHIM[@]}" \
    -d "$OUT"

"$JAVA_BIN" -cp "$OUT:$STDLIB" JvmTestRunnerKt "$OUT"
