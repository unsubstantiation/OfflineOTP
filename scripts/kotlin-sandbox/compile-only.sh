#!/usr/bin/env bash
# Fast compile-only check for the converted sources (no Checks.kt run).
#
# It reuses run.sh's source lists, so it can never drift from the real harness, and compiles
# them plus any extra sources given on the command line. It is a developer convenience for
# iterating on a conversion; the authoritative (still non-authoritative, but complete) check is
# scripts/kotlin-sandbox/run.sh.
set -euo pipefail

SANDBOX="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SANDBOX/../.."
KOTLIN_APP="$ROOT/app/src/main/kotlin/org/shadowice/flocke/andotp"
KOTLIN_CORE="$ROOT/core/src/main/kotlin/org/shadowice/flocke/andotp"
OUT="${KOTLIN_SANDBOX_OUT:-$ROOT/reports/kotlin-sandbox/compile-only}"

export JAVA_HOME="${JAVA_HOME:-/tmp/ks-toolchain/jdk/jdk4py/java-runtime}"
export PATH="$JAVA_HOME/bin:$PATH"
KOTLINC="${KOTLINC:-/tmp/ks-toolchain/kotlin/bin/kotlinc-jvm}"

# Pull the source lists out of run.sh so the two never drift.
eval "$(python3 - "$SANDBOX/run.sh" <<'PY'
import re, shlex, sys
text = open(sys.argv[1]).read()
for variable in ("CORE_SOURCES", "APP_SOURCES", "STUBS"):
    match = re.search(rf"^{variable}=\((.*?)^\)", text, re.MULTILINE | re.DOTALL)
    # Each entry is one shell word (quoted prefix plus an optional glob), so split with shlex
    # rather than taking only the quoted parts; comment lines inside the array are skipped.
    body = "\n".join(line for line in match.group(1).splitlines() if not line.lstrip().startswith("#"))
    entries = shlex.split(body)
    print(f"{variable}=(" + " ".join(shlex.quote(e) for e in entries) + ")")
PY
)"

# The glob entries above are quoted strings; expand the ones that are really globs.
expanded=()
for entry in "${STUBS[@]}"; do
    entry="${entry//\$KOTLIN_APP/$KOTLIN_APP}"
    entry="${entry//\$ROOT/$ROOT}"
    entry="${entry//\$SANDBOX/$SANDBOX}"
    if compgen -G "$entry" > /dev/null; then
        while IFS= read -r line; do expanded+=("$line"); done < <(compgen -G "$entry")
    else
        expanded+=("$entry")
    fi
done
STUBS=("${expanded[@]}")

APP_SOURCES=("${APP_SOURCES[@]//\$KOTLIN_APP/$KOTLIN_APP}")
APP_SOURCES=("${APP_SOURCES[@]//\$ROOT/$ROOT}")
CORE_SOURCES=("${CORE_SOURCES[@]//\$KOTLIN_CORE/$KOTLIN_CORE}")
CORE_SOURCES=("${CORE_SOURCES[@]//\$ROOT/$ROOT}")

rm -rf "$OUT"
mkdir -p "$OUT"

"$KOTLINC" -jvm-target 1.8 -nowarn "$@" "${CORE_SOURCES[@]}" "${APP_SOURCES[@]}" "${STUBS[@]}" -d "$OUT"
echo "COMPILE OK -> $OUT"
