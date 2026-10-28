#!/usr/bin/env bash
#
# Desktop Swing boundary gate.
#
# Mirrors the Gradle :desktop -> :core dependency with two kotlinc phases and no Android SDK:
#   phase 1 compiles :core (main+test) with only its pure-JVM third-party stubs, and
#   phase 2 compiles the desktop Swing sources and tests against core's compiled output and the
#   JDK's java.desktop module (Swing/AWT need no stub and no third-party UI library).
# A desktop source that imports android.* or reaches for an undeclared library fails to compile.
# Tests run headless (-Djava.awt.headless=true): the model layer is headless-safe, while the JFrame
# shell (MainWindow) is only compiled here because creating a heavyweight peer needs a display.
set -euo pipefail

SANDBOX="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SANDBOX/../.." && pwd)"
CORE_MAIN="$ROOT/core/src/main/kotlin"
CORE_TEST="$ROOT/core/src/test/kotlin"
DESKTOP_MAIN="$ROOT/desktop/src/main/kotlin"
DESKTOP_TEST="$ROOT/desktop/src/test/kotlin"
# Gradle puts src/test/resources on the test classpath automatically; the no-Gradle gate mirrors it.
DESKTOP_RESOURCES="$ROOT/desktop/src/test/resources"
OUT="${KOTLIN_DESKTOP_OUT:-$ROOT/reports/kotlin-sandbox/desktop}"

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

# Same portable third-party surface as compile-core.sh: never the yubikit android/nfc/usb stubs.
YUBIKIT_CORE_STUBS=(
    "$SANDBOX"/stubs/com/yubico/yubikit/core.kt
    "$SANDBOX"/stubs/com/yubico/yubikit/commandstate.kt
    "$SANDBOX"/stubs/com/yubico/yubikit/smartcard.kt
    "$SANDBOX"/stubs/com/yubico/yubikit/oath.kt
    "$SANDBOX"/stubs/com/yubico/yubikit/fidoclient.kt
    "$SANDBOX"/stubs/com/yubico/yubikit/clientdata.kt
    "$SANDBOX"/stubs/com/yubico/yubikit/webauthn.kt
)
CORE_STUBS=("$SANDBOX"/stubs/org/apache/commons/codec/binary/*.kt
    "$SANDBOX"/stubs/org/json/*.kt
    "${YUBIKIT_CORE_STUBS[@]}")
mapfile -d '' JUNIT_SHIM < <(find "$ROOT/scripts/jvm-tests" -name '*.kt' -print0 | sort -z)
mapfile -d '' CORE_SOURCES < <(find "$CORE_MAIN" "$CORE_TEST" -name '*.kt' -print0 | sort -z)
mapfile -d '' DESKTOP_SOURCES < <(find "$DESKTOP_MAIN" "$DESKTOP_TEST" -name '*.kt' -print0 | sort -z)
# Desktop-only third-party compile stubs (PGPainless/BouncyCastle and the yubikit PC/SC module).
# Phase 1 (core) never sees these: core speaks library-neutral ports and the portable yubikit
# core/oath/fido stubs it already owns; only desktop references the PGPainless and PC/SC adapters.
DESKTOP_STUBS=()
# zxing's integration/android stub pulls in android types, so only the pure-JVM core surface is
# included here; the desktop QR adapter uses ImageIO rather than any Android bitmap type.
ZXING_STUBS=()
mapfile -d '' ZXING_STUBS < <(find "$SANDBOX/stubs/com/google/zxing" \
    -name '*.kt' -not -path "*/integration/*" -print0 2>/dev/null | sort -z)
mapfile -d '' DESKTOP_STUBS < <(find "$SANDBOX/stubs/org/pgpainless" "$SANDBOX/stubs/org/bouncycastle" \
    "$SANDBOX/stubs/com/yubico/yubikit/desktop" "$SANDBOX/stubs/javax/smartcardio" \
    -name '*.kt' -print0 2>/dev/null | sort -z)
DESKTOP_STUBS+=("${ZXING_STUBS[@]}")
[[ ${#CORE_SOURCES[@]} -gt 0 ]] || { echo "no core sources under $CORE_MAIN" >&2; exit 2; }
[[ ${#DESKTOP_SOURCES[@]} -gt 0 ]] || { echo "no desktop sources under $DESKTOP_MAIN" >&2; exit 2; }

rm -rf "$OUT"
mkdir -p "$OUT/core" "$OUT/desktop"

echo "kotlinc: $KOTLINC"
echo "phase 1: core (${#CORE_SOURCES[@]} files, no android/androidx stubs)"
"$KOTLINC" -jvm-target 1.8 -nowarn \
    "${CORE_SOURCES[@]}" "${CORE_STUBS[@]}" "${JUNIT_SHIM[@]}" \
    -d "$OUT/core"

echo "phase 2: desktop (${#DESKTOP_SOURCES[@]} files, Swing/AWT from the JDK)"
"$KOTLINC" -jvm-target 1.8 -nowarn -cp "$OUT/core:$STDLIB" \
    "${DESKTOP_SOURCES[@]}" "${DESKTOP_STUBS[@]}" "${JUNIT_SHIM[@]}" \
    -d "$OUT/desktop"

echo "headless desktop tests:"
"$JAVA_BIN" -Djava.awt.headless=true -cp "$OUT/desktop:$OUT/core:$DESKTOP_RESOURCES:$STDLIB" JvmTestRunnerKt "$OUT/desktop"
