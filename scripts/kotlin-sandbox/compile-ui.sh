#!/usr/bin/env bash
#
# Shared Compose Multiplatform :ui boundary gate.
#
# Mirrors the Gradle layering (:core <- :ui) with plain kotlinc phases and no Gradle, no Android SDK
# and no Compose artifacts:
#
#   phase 1  compiles :core with its portable third-party stubs, exactly like compile-desktop.sh;
#   phase 2  compiles ui/src/commonMain against phase 1 plus hand-written Compose Multiplatform
#            stubs (scripts/kotlin-sandbox/stubs-compose). A file that reaches for android.*, Swing,
#            AWT, storage or session types fails to compile here;
#   phase 3  compiles the pure projection tests in ui/src/desktopTest (JUnit 4 shim) and runs them;
#   phase 4  compiles the :desktop-compose host against the compiled :desktop gate output and the
#            Compose Desktop window stubs, so the host cannot drift out of the shared screen's API.
#
# What a green run means: the shared UI type-checks under `-jvm-target 1.8`, and the frame builder
# behaves as asserted on a host JVM. What it does NOT mean: no composable is executed, no layout is
# measured, no Compose runtime exists, no resource is resolved, and the Compose stub signatures are
# approximations of the published artifacts. Gradle builds and on-device checks remain the release
# gates; see README.md and docs/compose-unified-ui-plan.md.
set -euo pipefail

SANDBOX="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SANDBOX/../.." && pwd)"
CORE_MAIN="$ROOT/core/src/main/kotlin"
UI_MAIN="$ROOT/ui/src/commonMain/kotlin"
UI_TEST="$ROOT/ui/src/desktopTest/kotlin"
COMPOSE_STUBS="$SANDBOX/stubs-compose"
OUT="${KOTLIN_UI_OUT:-$ROOT/reports/kotlin-sandbox/ui}"

KOTLINC="${KOTLINC:-$(command -v kotlinc-jvm || command -v kotlinc || true)}"
JAVA_BIN="${JAVA_BIN:-$(command -v java || true)}"
if [[ -z "$KOTLINC" || -z "$JAVA_BIN" ]]; then
    TOOLCHAIN="${KOTLIN_SANDBOX_TOOLCHAIN:-${TMPDIR:-/tmp}/kotlin-sandbox-toolchain}"
    if [[ -x "$TOOLCHAIN/kotlin/bin/kotlinc-jvm" && -x "$TOOLCHAIN/jdk/jdk4py/java-runtime/bin/java" ]]; then
        KOTLINC="$TOOLCHAIN/kotlin/bin/kotlinc-jvm"
        JAVA_BIN="$TOOLCHAIN/jdk/jdk4py/java-runtime/bin/java"
    else
        echo "kotlinc and java are required; run scripts/kotlin-sandbox/bootstrap-toolchain.sh" >&2
        echo "or set KOTLINC=/path/to/kotlinc-jvm JAVA_BIN=/path/to/java" >&2
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
[[ -f "$STDLIB" ]] || { echo "kotlin-stdlib.jar not found; set KOTLIN_STDLIB=/path/to/kotlin-stdlib.jar" >&2; exit 2; }

# Same portable third-party surface as compile-core.sh / compile-desktop.sh: never the yubikit
# android/nfc/usb transport stubs.
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
mapfile -d '' CORE_SOURCES < <(find "$CORE_MAIN" -name '*.kt' -print0 | sort -z)
mapfile -d '' UI_SOURCES < <(find "$UI_MAIN" -name '*.kt' -print0 | sort -z)
mapfile -d '' UI_TEST_SOURCES < <(find "$UI_TEST" -name '*.kt' -print0 | sort -z)
mapfile -d '' COMPOSE_STUB_SOURCES < <(find "$COMPOSE_STUBS" -name '*.kt' \
    -not -path "$COMPOSE_STUBS/desktop/*" -print0 | sort -z)
# Compose Desktop (window) stubs are only needed by the host phase.
mapfile -d '' COMPOSE_DESKTOP_STUB_SOURCES < <(find "$COMPOSE_STUBS/desktop" -name '*.kt' -print0 2>/dev/null | sort -z)
mapfile -d '' HOST_SOURCES < <(find "$ROOT/desktop-compose/src/main/kotlin" -name '*.kt' -print0 | sort -z)

[[ ${#CORE_SOURCES[@]} -gt 0 ]] || { echo "no core sources under $CORE_MAIN" >&2; exit 2; }
[[ ${#UI_SOURCES[@]} -gt 0 ]] || { echo "no ui sources under $UI_MAIN" >&2; exit 2; }
[[ ${#COMPOSE_STUB_SOURCES[@]} -gt 0 ]] || { echo "no Compose stubs under $COMPOSE_STUBS" >&2; exit 2; }

rm -rf "$OUT"
mkdir -p "$OUT/core" "$OUT/ui" "$OUT/tests" "$OUT/host"

echo "kotlinc: $KOTLINC"
echo "phase 1: core (${#CORE_SOURCES[@]} files, no android/androidx stubs)"
"$KOTLINC" -jvm-target 1.8 -nowarn \
    "${CORE_SOURCES[@]}" "${CORE_STUBS[@]}" \
    -d "$OUT/core"

echo "phase 2: shared ui (${#UI_SOURCES[@]} files, Compose Multiplatform stubs)"
"$KOTLINC" -jvm-target 1.8 -nowarn -cp "$OUT/core:$STDLIB" \
    "${UI_SOURCES[@]}" "${COMPOSE_STUB_SOURCES[@]}" \
    -d "$OUT/ui"

echo "phase 3: projection tests (${#UI_TEST_SOURCES[@]} files, JUnit 4 shim)"
"$KOTLINC" -jvm-target 1.8 -nowarn -cp "$OUT/core:$OUT/ui:$STDLIB" \
    "${UI_TEST_SOURCES[@]}" "${JUNIT_SHIM[@]}" \
    -d "$OUT/tests"

echo "headless projection tests:"
"$JAVA_BIN" -Djava.awt.headless=true -cp "$OUT/tests:$OUT/ui:$OUT/core:$STDLIB" \
    JvmTestRunnerKt "$OUT/tests"

# Phase 4 needs the desktop gate's compiled output because the host drives the real
# VaultController/DesktopVaultState. Run that gate first when its output is missing, so this script
# stays self-contained instead of silently skipping the host.
DESKTOP_OUT="${KOTLIN_DESKTOP_OUT:-$ROOT/reports/kotlin-sandbox/desktop}"
if [[ ! -d "$DESKTOP_OUT/desktop" || ! -d "$DESKTOP_OUT/core" ]]; then
    echo "phase 4: desktop gate output missing, running compile-desktop.sh first"
    bash "$SANDBOX/compile-desktop.sh" > /dev/null
fi

echo "phase 4: desktop-compose host (${#HOST_SOURCES[@]} files, Compose Desktop window stubs)"
[[ ${#HOST_SOURCES[@]} -gt 0 ]] || { echo "no host sources under desktop-compose/src/main/kotlin" >&2; exit 2; }
[[ ${#COMPOSE_DESKTOP_STUB_SOURCES[@]} -gt 0 ]] || { echo "no Compose Desktop stubs under $COMPOSE_STUBS/desktop" >&2; exit 2; }
"$KOTLINC" -jvm-target 1.8 -nowarn \
    -cp "$DESKTOP_OUT/core:$DESKTOP_OUT/desktop:$OUT/ui:$STDLIB" \
    "${HOST_SOURCES[@]}" "${COMPOSE_DESKTOP_STUB_SOURCES[@]}" \
    -d "$OUT/host"
echo "host type-checked (never run: the window stubs are inert)"

