#!/usr/bin/env bash
#
# Full emulator lifecycle for the instrumentation matrix: create the AVD, boot it headless,
# wait for the system to come up, run the flavor's connected test task, then always tear the
# emulator and the AVD down again. The exit code is the Gradle exit code (or 2 for setup
# problems), so a CI step can use this as its only command.
#
# Usage: scripts/ci/start-emulator.sh <fdroid|play> <28|36>
#
# Requires ANDROID_HOME with the cmdline-tools, platform-tools and emulator packages installed
# (android-actions/setup-android provides exactly that) and Gradle via the repository wrapper.
# The runner needs KVM; GitHub's ubuntu-24.04 images expose it.
set -euo pipefail

flavor="${1:-}"
api="${2:-}"

usage() {
    echo "usage: $0 <fdroid|play> <28|36>" >&2
    exit 2
}

case "$flavor" in
    fdroid | play) ;;
    *) usage ;;
esac
case "$api" in
    28 | 36) ;;
    *) usage ;;
esac

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK (android-actions/setup-android does)}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
for tool in "$ADB" "$EMULATOR" "$SDKMANAGER" "$AVDMANAGER"; do
    if [[ ! -x "$tool" ]]; then
        echo "missing SDK tool: $tool" >&2
        exit 2
    fi
done

image="system-images;android-$api;default;x86_64"
avd="ci-andotp-$flavor-$api"
task="connected${flavor^}DebugAndroidTest"
report="$root/reports/emulator"
mkdir -p "$report"

cleanup() {
    "$ADB" emu kill >/dev/null 2>&1 || true
    # Give the emulator a moment to release the AVD directory before deleting it.
    for _ in $(seq 1 30); do
        "$ADB" get-state >/dev/null 2>&1 || break
        sleep 1
    done
    "$AVDMANAGER" delete avd -n "$avd" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "== installing $image"
yes 2>/dev/null | "$SDKMANAGER" --licenses >/dev/null
"$SDKMANAGER" --install "$image" >"$report/sdkmanager.log" 2>&1

echo "== creating AVD $avd"
echo no | "$AVDMANAGER" create avd -n "$avd" -k "$image" -d pixel --force >/dev/null

echo "== booting headless emulator"
"$EMULATOR" -avd "$avd" -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect -accel on -memory 2048 >"$report/emulator.log" 2>&1 &

"$ADB" wait-for-device
booted=""
for _ in $(seq 1 300); do
    if [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
        booted=yes
        break
    fi
    sleep 2
done
if [[ "$booted" != "yes" ]]; then
    echo "emulator did not finish booting within 10 minutes" >&2
    tail -50 "$report/emulator.log" >&2 || true
    exit 2
fi
# Let the package manager settle before instrumentation asks it to install APKs.
sleep 10

echo "== connected device:"
"$ADB" devices
echo "== connected task: :app:$task"

status=0
./gradlew --no-daemon ":app:$task" || status=$?
echo "== instrumentation exit code: $status"
exit "$status"
