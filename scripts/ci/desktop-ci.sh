#!/usr/bin/env bash
#
# Continuous-integration entry point for the desktop Swing client and the shared :core module.
# Run this script on a full JDK 17+ host with network access. The separate GitHub workflows
# build platform-specific artifacts and do not replace this verification suite.
#
# It runs the same no-Android-SDK gates as local development plus a real Gradle distribution
# build that resolves the PGPainless, zxing and yubikit PC/SC dependencies from Maven Central.
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"

# No-op when a toolchain is already on PATH (real CI JDKs include the compiler via Gradle).
if ! command -v kotlinc-jvm >/dev/null 2>&1 && [[ ! -x /tmp/kotlin-sandbox-toolchain/kotlin/bin/kotlinc-jvm ]]; then
    scripts/kotlin-sandbox/bootstrap-toolchain.sh
fi
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"

scripts/kotlin-sandbox/compile-core.sh
scripts/kotlin-sandbox/compile-desktop.sh
scripts/run-jvm-tests.sh
python3 -m unittest discover -s scripts/tests -p "test_*.py"
python3 scripts/check_build_inputs.py

# Real dependency resolution and the application-plugin distribution.
./gradlew --no-daemon :desktop:distZip
