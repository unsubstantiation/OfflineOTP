#!/usr/bin/env bash
#
# Runs the JVM unit-test suite (app/src/test/kotlin) on a desktop JVM.
#
# The test sources are plain JUnit 4: on a Gradle host they compile and run unchanged against
# the real junit:junit:4.13.2 (and org.json:json) declared in app/build.gradle. This script is
# the no-Gradle stand-in: it compiles the app sources plus the kotlin-sandbox stub set plus the
# minimal JUnit 4 shim in scripts/jvm-tests (the verification environment cannot fetch the junit
# jars), then drives them with the reflective JvmTestRunner.
#
# The compiled source set is exactly scripts/kotlin-sandbox/run.sh's APP_SOURCES/STUBS arrays:
# both arrays are read out of that file, so the sandbox check and this suite can never drift
# apart. Read scripts/kotlin-sandbox/README.md for what the stub set does and does not promise.
set -euo pipefail

SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPTS/.." && pwd)"
SANDBOX="$SCRIPTS/kotlin-sandbox"
KOTLIN_APP="$ROOT/app/src/main/kotlin/org/shadowice/flocke/andotp"
KOTLIN_CORE="$ROOT/core/src/main/kotlin/org/shadowice/flocke/andotp"
TEST_ROOT="$ROOT/app/src/test/kotlin"
CORE_TEST_ROOT="$ROOT/core/src/test/kotlin"
OUT="${KOTLIN_JVM_TESTS_OUT:-$ROOT/reports/jvm-tests/out}"

# Discover the compiler and JVM, falling back to the bootstrapped toolchain directory.
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

# kotlinc's launcher resolves its JVM via JAVA_HOME or PATH; provide both from JAVA_BIN.
if [[ -z "${JAVA_HOME:-}" ]]; then
    JAVA_HOME="$(cd "$(dirname "$(readlink -f "$JAVA_BIN")")/.." && pwd)"
    export JAVA_HOME
fi
export PATH="$(dirname "$(readlink -f "$JAVA_BIN")"):$PATH"

STDLIB="${KOTLIN_STDLIB:-}"
if [[ -z "$STDLIB" ]]; then
    KOTLINC_REAL="$(readlink -f "$KOTLINC")"
    for candidate in "$(dirname "$KOTLINC_REAL")/../lib/kotlin-stdlib.jar" "$(dirname "$KOTLINC")/../lib/kotlin-stdlib.jar"; do
        if [[ -f "$candidate" ]]; then
            STDLIB="$(cd "$(dirname "$candidate")" && pwd)/$(basename "$candidate")"
            break
        fi
    done
fi
if [[ -z "$STDLIB" || ! -f "$STDLIB" ]]; then
    echo "kotlin-stdlib.jar not found; set KOTLIN_STDLIB=/path/to/kotlin-stdlib.jar" >&2
    exit 2
fi

# The exact source and stub lists the sandbox check compiles, kept in lockstep by reading them
# from run.sh itself (the same block scripts/tests/test_kotlin_sandbox_harness.py validates).
eval "$(sed -n '/^CORE_SOURCES=(/,/^)$/p' "$SANDBOX/run.sh")"
eval "$(sed -n '/^APP_SOURCES=(/,/^)$/p' "$SANDBOX/run.sh")"
eval "$(sed -n '/^STUBS=(/,/^)$/p' "$SANDBOX/run.sh")"

mapfile -d '' TEST_SOURCES < <(find "$TEST_ROOT" "$CORE_TEST_ROOT" -name '*.kt' -print0 | sort -z)
if [[ ${#TEST_SOURCES[@]} -eq 0 ]]; then
    echo "no test sources found under $TEST_ROOT or $CORE_TEST_ROOT" >&2
    exit 2
fi

# The whole JUnit shim (annotations, Assert/Assume, RunWith) plus the reflective runner.
mapfile -d '' JUNIT_SHIM < <(find "$SCRIPTS/jvm-tests" -name '*.kt' -print0 | sort -z)

rm -rf "$OUT"
mkdir -p "$OUT"

echo "kotlinc: $KOTLINC"
echo "java:    $JAVA_BIN"
echo "stdlib:  $STDLIB"
echo "tests:   ${#TEST_SOURCES[@]} files"
echo "output:  $OUT"

"$KOTLINC" -jvm-target 1.8 -nowarn \
    "${CORE_SOURCES[@]}" "${APP_SOURCES[@]}" "${STUBS[@]}" "${TEST_SOURCES[@]}" \
    "$SCRIPTS/jvm-tests/org/junit/JUnit4.kt" \
    "$SCRIPTS/jvm-tests/org/junit/internal/AssumptionViolatedException.kt" \
    "$SCRIPTS/jvm-tests/JvmTestRunner.kt" \
    -d "$OUT"

"$JAVA_BIN" -cp "$OUT:$STDLIB" JvmTestRunnerKt "$OUT"
