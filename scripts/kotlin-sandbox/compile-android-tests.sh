#!/usr/bin/env bash
#
# Compile-only check for the instrumentation sources (app/src/androidTest/kotlin).
#
# AGP compiles the flavor-specific androidTest APKs; tests run on an emulator or device.
# This no-Android-SDK stand-in type-checks the instrumentation sources against run.sh's app
# sources and stub set plus the
# androidx.test stand-ins in ./stubs-android-test and the JUnit shim in ../jvm-tests. It says
# nothing about Android runtime behaviour; a green run only means the suite compiles.
set -euo pipefail

SANDBOX="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SANDBOX/../.." && pwd)"
KOTLIN_APP="$ROOT/app/src/main/kotlin/org/shadowice/flocke/andotp"
ANDROID_TEST_ROOT="$ROOT/app/src/androidTest/kotlin"
OUT="${KOTLIN_ANDROID_TEST_OUT:-$ROOT/reports/kotlin-sandbox/android-test-compile}"

# Toolchain discovery identical to run-jvm-tests.sh; bootstrap-toolchain.sh provisions the path.
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

# The app sources and platform stubs are exactly what run.sh compiles, read out of that file so
# the checks can never drift (the same block scripts/tests/test_kotlin_sandbox_harness.py pins).
eval "$(sed -n '/^APP_SOURCES=(/,/^)$/p' "$SANDBOX/run.sh")"
eval "$(sed -n '/^STUBS=(/,/^)$/p' "$SANDBOX/run.sh")"

mapfile -d '' ANDROID_TEST_SOURCES < <(find "$ANDROID_TEST_ROOT" -name '*.kt' -print0 | sort -z)
if [[ ${#ANDROID_TEST_SOURCES[@]} -eq 0 ]]; then
    echo "no androidTest sources found under $ANDROID_TEST_ROOT" >&2
    exit 2
fi

TEST_STUBS=("$SANDBOX"/stubs-android-test/android/app/*.kt
    "$SANDBOX"/stubs-android-test/androidx/test/core/app/*.kt
    "$SANDBOX"/stubs-android-test/androidx/test/ext/junit/runners/*.kt
    "$SANDBOX"/stubs-android-test/androidx/test/platform/app/*.kt
    "$SANDBOX"/stubs-android-test/androidx/test/runner/lifecycle/*.kt)
mapfile -d '' JUNIT_SHIM < <(find "$ROOT/scripts/jvm-tests" -name '*.kt' -print0 | sort -z)

rm -rf "$OUT"
mkdir -p "$OUT"

echo "kotlinc:     $KOTLINC"
echo "androidTest: ${#ANDROID_TEST_SOURCES[@]} files"
echo "output:      $OUT"

"$KOTLINC" -jvm-target 1.8 -nowarn \
    "${APP_SOURCES[@]}" "${STUBS[@]}" "${ANDROID_TEST_SOURCES[@]}" \
    "${TEST_STUBS[@]}" "${JUNIT_SHIM[@]}" \
    -d "$OUT"

echo "COMPILE OK -> $OUT"
