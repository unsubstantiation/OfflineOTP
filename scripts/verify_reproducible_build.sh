#!/usr/bin/env bash
# Compare unsigned release APKs AND resolved dependency bytes from independent
# source directories / Gradle homes. No remote cache is trusted for this check.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
report="$root/reports/reproducibility"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export TZ=UTC LANG=C.UTF-8 LC_ALL=C.UTF-8
# Both independent builds must embed the same build timestamp.
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(date +%s)}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the pinned Android SDK}"
mkdir -p "$report"
rm -f "$report/result.txt"
: > "$report/sdk-packages.txt"
command -v java >/dev/null
command -v apkanalyzer >/dev/null
java -version 2> "$report/java-version.txt"
for package in platforms/android-36 build-tools/36.0.0; do
    sha256sum "$ANDROID_HOME/$package/package.xml" >> "$report/sdk-packages.txt"
done

for run in 1 2; do
    source_dir="$work/source-$run"
    mkdir -p "$source_dir" "$report/run-$run"
    tar -C "$root" --exclude=.git --exclude=.gradle --exclude=.cache --exclude=build \
        --exclude=.venv --exclude=reports --exclude=__pycache__ -cf - . | tar -C "$source_dir" -xf -
    (
        cd "$source_dir"
        export GRADLE_USER_HOME="$work/gradle-home-$run"
        ./gradlew --no-daemon --no-build-cache --no-configuration-cache --rerun-tasks \
            clean :app:assembleFdroidRelease :app:assemblePlayRelease writeDependencyInventory
    ) 2>&1 | tee "$report/run-$run/build.log"
    cp "$source_dir/build/reports/dependency-inventory.txt" "$report/run-$run/"
    for flavor in fdroid play; do
        apk="$source_dir/app/build/outputs/apk/$flavor/release/app-$flavor-release-unsigned.apk"
        test -f "$apk"
        cp "$apk" "$report/run-$run/$flavor-release-unsigned.apk"
        apkanalyzer manifest print "$apk" > "$report/run-$run/$flavor-manifest.xml"
        python3 "$root/scripts/check_manifest.py" "$report/run-$run/$flavor-manifest.xml"
    done
    (cd "$report/run-$run" && sha256sum *-release-unsigned.apk dependency-inventory.txt) \
        > "$report/run-$run/SHA256SUMS"
done

diff -u "$report/run-1/dependency-inventory.txt" "$report/run-2/dependency-inventory.txt"
for flavor in fdroid play; do
    cmp "$report/run-1/$flavor-release-unsigned.apk" "$report/run-2/$flavor-release-unsigned.apk"
done
diff -u "$report/run-1/SHA256SUMS" "$report/run-2/SHA256SUMS"
printf 'Independent unsigned APKs and dependency inventories are byte-identical.\n' | tee "$report/result.txt"
