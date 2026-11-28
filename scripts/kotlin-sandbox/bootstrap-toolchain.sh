#!/usr/bin/env bash
#
# Fetches a JDK and the Kotlin compiler for the sandbox check, for machines that have neither and
# cannot reach Google Maven/the Android SDK repositories. Everything is unpacked into a temporary
# directory (outside the repository) and nothing is added to the build.
#
# Sources are package mirrors reachable without the Android SDK:
#   - jdk4py       (PyPI, Temurin JDK packaged as a Python wheel)
#   - kotlin-compiler (npm, the official JetBrains Kotlin compiler distribution)
#
# The digests below are the ones observed when this script was written: verification is
# trust-on-first-use against the mirrors over TLS, not an independent provenance check. Review the
# versions/digests before using this in any trusted environment, and prefer a package manager or a
# verified archive there.
set -euo pipefail

DEST="${1:-${KOTLIN_SANDBOX_TOOLCHAIN:-${TMPDIR:-/tmp}/kotlin-sandbox-toolchain}}"

JDK4PY_VERSION="25.0.5.1"
JDK4PY_WHEEL="jdk4py-${JDK4PY_VERSION}-py3-none-manylinux_2_17_x86_64.whl"
JDK4PY_SHA256="b989b35d24f704cb40b402a82003d131056bf2b9794c33acb84bf42b5ebffd34"

KOTLIN_VERSION="2.4.21"
KOTLIN_TGZ="kotlin-compiler-${KOTLIN_VERSION}.tgz"
KOTLIN_SHA256="5d0b53a7052a71e77773eea12af37bcc8db92049d05ced09ffb951c7294c2c87"

mkdir -p "$DEST"
cd "$DEST"

if [[ ! -f "$JDK4PY_WHEEL" ]]; then
    python3 -m pip download --no-deps -d . "jdk4py==${JDK4PY_VERSION}"
fi
echo "${JDK4PY_SHA256}  ${JDK4PY_WHEEL}" | sha256sum -c -

if [[ ! -f "$KOTLIN_TGZ" ]]; then
    curl -sS -o "$KOTLIN_TGZ" "https://registry.npmjs.org/kotlin-compiler/-/kotlin-compiler-${KOTLIN_VERSION}.tgz"
fi
echo "${KOTLIN_SHA256}  ${KOTLIN_TGZ}" | sha256sum -c -

rm -rf jdk kotlin
python3 - "$JDK4PY_WHEEL" <<'PY'
import sys, zipfile
zipfile.ZipFile(sys.argv[1]).extractall("jdk")
PY
mkdir -p kotlin
tar xzf "$KOTLIN_TGZ" -C kotlin --strip-components=1

JAVA_HOME="$DEST/jdk/jdk4py/java-runtime"
chmod -R u+x "$JAVA_HOME/bin"
chmod -R u+x "$DEST/kotlin/bin"

echo
echo "Add these to your environment (for example with eval):"
echo "export JAVA_HOME=$JAVA_HOME"
echo "export JAVA_BIN=$JAVA_HOME/bin/java"
echo "export KOTLINC=$DEST/kotlin/bin/kotlinc-jvm"
echo "export KOTLIN_STDLIB=$DEST/kotlin/lib/kotlin-stdlib.jar"
echo
echo "Then: bash scripts/kotlin-sandbox/run.sh"
