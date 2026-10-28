#!/usr/bin/env bash
#
# Reproducibility check for the desktop distribution. Builds :desktop:distZip twice from
# independent source copies and Gradle homes (no shared caches) and requires byte-identical
# archives, mirroring scripts/verify_reproducible_build.sh for the Android APKs.
#
# Requires a full JDK 17+ and network access to the dependency repositories on a real CI host;
# the no-network sandbox cannot run Gradle and uses scripts/kotlin-sandbox/compile-desktop.sh
# instead.
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
report="$root/reports/desktop-reproducibility"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export TZ=UTC LANG=C.UTF-8 LC_ALL=C.UTF-8

command -v java >/dev/null
java -version 2> "$work/java-version.txt"
mkdir -p "$report"
rm -f "$report/result.txt"

for run in 1 2; do
    source_dir="$work/source-$run"
    mkdir -p "$source_dir" "$report/run-$run"
    tar -C "$root" --exclude=.git --exclude=.gradle --exclude=.cache --exclude=build \
        --exclude=.venv --exclude=reports --exclude=__pycache__ -cf - . \
        | tar -C "$source_dir" -xf -
    (
        cd "$source_dir"
        export GRADLE_USER_HOME="$work/gradle-home-$run"
        ./gradlew --no-daemon --no-build-cache --no-configuration-cache --rerun-tasks \
            clean :desktop:distZip writeDependencyInventory
    ) 2>&1 | tee "$report/run-$run/build.log"

    archive=$(find "$source_dir/desktop/build/distributions" -maxdepth 1 -name '*.zip' -print -quit)
    test -f "$archive"
    cp "$archive" "$report/run-$run/desktop-dist.zip"
    cp "$source_dir/build/reports/dependency-inventory.txt" "$report/run-$run/" 2>/dev/null || true
    (cd "$report/run-$run" && sha256sum desktop-dist.zip) > "$report/run-$run/SHA256SUMS"
done

cmp "$report/run-1/desktop-dist.zip" "$report/run-2/desktop-dist.zip"
diff -u "$report/run-1/SHA256SUMS" "$report/run-2/SHA256SUMS"
printf 'Independent desktop distributions are byte-identical.\n' | tee "$report/result.txt"

# Optional native packaging smoke test. jpackage ships only with full JDKs (never a JRE), so this
# stays skipped on stripped toolchains instead of failing the gate. On a release host it builds the
# app image from the first distribution and verifies the launcher and full dependency set exist.
jpackage_bin="$(dirname "$(readlink -f "$(command -v java)")")/jpackage"
if [[ ! -x "$jpackage_bin" ]]; then
    printf 'jpackage unavailable: skipping native app-image smoke test.\n' | tee -a "$report/result.txt"
    exit 0
fi

app_image="$report/app-image"
rm -rf "$app_image"
(
    cd "$work/source-1"
    ./gradlew --no-daemon packageDesktop
) 2>&1 | tee "$report/packageDesktop.log"
test -d "$work/source-1/desktop/build/release/OfflineOTP"
test -x "$work/source-1/desktop/build/release/OfflineOTP/OfflineOTP" \
    -o -f "$work/source-1/desktop/build/release/OfflineOTP/OfflineOTP.exe"
# The app image must bundle the application jar plus every declared runtime dependency.
app_lib="$work/source-1/desktop/build/release/OfflineOTP/lib"
for jar in desktop.jar pgpainless-core bcpg-jdk18on bcprov-jdk18on core.jar; do
    ls "$app_lib" | grep -q "$jar" || { echo "missing runtime jar: $jar" >&2; exit 1; }
done
printf 'jpackage app-image smoke test passed.\n' | tee -a "$report/result.txt"
