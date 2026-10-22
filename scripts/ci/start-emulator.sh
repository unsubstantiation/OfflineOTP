#!/usr/bin/env bash
#
# Full emulator lifecycle for the instrumentation matrix: create the AVD, boot it headless,
# wait for the system to come up, run the flavor's connected test task, then always tear the
# emulator and the AVD down again. Every external wait is bounded so a broken SDK/emulator cannot
# consume the entire Actions job timeout; failure summaries go to both the step log and annotations.
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
report="$root/reports/emulator"
mkdir -p "$report"

progress() {
    local message="$1"
    echo "== $message"
    if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
        echo "::notice title=Android emulator progress::$message"
    fi
}

report_failure() {
    local message="$1"
    local status="${2:-2}"
    printf '%s\n' "$message" >> "$report/failure.txt"
    echo "$message" >&2
    if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
        local annotation="$message"
        annotation=${annotation//'%'/'%25'}
        annotation=${annotation//$'\r'/'%0D'}
        annotation=${annotation//$'\n'/'%0A'}
        echo "::error title=Android emulator failure::$annotation"
    fi
    exit "$status"
}

summarize_test_failures() {
    python3 "$root/scripts/ci/summarize-instrumentation-failures.py" "$root/app/build"
}

# One annotation per failing test: a single combined line is truncated by the Actions UI, which
# hides every failure after the first couple. Keep the plain text report for the summary line too.
annotate_test_failures() {
    python3 "$root/scripts/ci/summarize-instrumentation-failures.py" --github "$root/app/build"
}

if [[ -z "${ANDROID_HOME:-}" ]]; then
    report_failure "Set ANDROID_HOME to the Android SDK (android-actions/setup-android does)"
fi
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
for tool in "$ADB" "$EMULATOR" "$SDKMANAGER" "$AVDMANAGER"; do
    if [[ ! -x "$tool" ]]; then
        report_failure "missing SDK tool: $tool"
    fi
done
if ! command -v timeout >/dev/null 2>&1; then
    report_failure "GNU timeout is required to bound emulator setup and test waits"
fi

# Force avdmanager and emulator to use one explicit, canonical AVD directory. Runners may set
# SDK-related home variables differently; without this, avdmanager can report success while the
# emulator searches another directory and reports an unknown AVD.
export ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"

image="system-images;android-$api;default;x86_64"
avd="ci-andotp-$flavor-$api"
task="connected${flavor^}DebugAndroidTest"
emulator_pid=""

cleanup() {
    if [[ -x "$ADB" ]]; then
        timeout 5 "$ADB" emu kill >/dev/null 2>&1 || true
    fi
    if [[ -n "$emulator_pid" ]]; then
        kill "$emulator_pid" >/dev/null 2>&1 || true
        for _ in $(seq 1 5); do
            if ! kill -0 "$emulator_pid" >/dev/null 2>&1; then
                break
            fi
            sleep 1
        done
        kill -KILL "$emulator_pid" >/dev/null 2>&1 || true
        wait "$emulator_pid" >/dev/null 2>&1 || true
    fi
    # Give the emulator a moment to release the AVD directory before deleting it.
    for _ in $(seq 1 5); do
        if ! timeout 3 "$ADB" get-state >/dev/null 2>&1; then
            break
        fi
        sleep 1
    done
    if [[ -x "$AVDMANAGER" ]]; then
        timeout 15 "$AVDMANAGER" delete avd -n "$avd" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

progress "accepting SDK licenses"
printf 'y\n%.0s' {1..100} > "$report/sdkmanager-licenses-input.txt"
if timeout 60 "$SDKMANAGER" --licenses < "$report/sdkmanager-licenses-input.txt" \
    >"$report/sdkmanager-licenses.log" 2>&1; then
    rm -f "$report/sdkmanager-licenses-input.txt"
else
    status=$?
    details="$(tail -n 12 "$report/sdkmanager-licenses.log" 2>/dev/null | tr '\n' ' ')"
    report_failure "sdkmanager --licenses failed or timed out (exit $status): $details" "$status"
fi

progress "installing $image (12-minute limit)"
if timeout 720 "$SDKMANAGER" --install "$image" >"$report/sdkmanager.log" 2>&1; then
    :
else
    status=$?
    details="$(tail -n 16 "$report/sdkmanager.log" 2>/dev/null | tr '\n' ' ')"
    report_failure "sdkmanager --install $image failed or timed out (exit $status): $details" "$status"
fi

progress "creating AVD $avd"
printf 'no\n' > "$report/avdmanager-input.txt"
if timeout 60 "$AVDMANAGER" create avd -n "$avd" -k "$image" -d pixel --force \
    < "$report/avdmanager-input.txt" >"$report/avdmanager.log" 2>&1; then
    rm -f "$report/avdmanager-input.txt"
else
    status=$?
    details="$(tail -n 16 "$report/avdmanager.log" 2>/dev/null | tr '\n' ' ')"
    report_failure "avdmanager create $avd failed or timed out (exit $status): $details" "$status"
fi

progress "verifying emulator can discover AVD $avd"
if timeout 30 "$EMULATOR" -list-avds >"$report/emulator-avds.log" 2>&1; then
    if ! grep -Fxq "$avd" "$report/emulator-avds.log"; then
        avd_manager_details="$(tail -n 12 "$report/avdmanager.log" 2>/dev/null | tr '\n' ' ')"
        avd_list="$(cat "$report/emulator-avds.log" 2>/dev/null | tr '\n' ' ')"
        report_failure "avdmanager created $avd, but emulator -list-avds cannot find it; ANDROID_AVD_HOME=$ANDROID_AVD_HOME, emulator list: $avd_list, avdmanager log: $avd_manager_details"
    fi
else
    status=$?
    details="$(tail -n 16 "$report/emulator-avds.log" 2>/dev/null | tr '\n' ' ')"
    report_failure "emulator -list-avds failed or timed out (exit $status): $details" "$status"
fi

progress "starting headless emulator"
"$EMULATOR" -avd "$avd" -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect -accel on -memory 2048 >"$report/emulator.log" 2>&1 &
emulator_pid=$!
printf '%s\n' "$emulator_pid" > "$report/emulator.pid"

progress "waiting up to 60 seconds for adb device registration"
if ! timeout 60 "$ADB" wait-for-device; then
    details="$(tail -n 20 "$report/emulator.log" 2>/dev/null | tr '\n' ' ')"
    report_failure "adb did not see the emulator within 60 seconds; emulator log: $details"
fi

progress "waiting up to 8 minutes for Android boot"
booted=""
boot_deadline=$((SECONDS + 480))
while (( SECONDS < boot_deadline )); do
    if boot_state="$(timeout 8 "$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" &&
        [[ "$boot_state" == "1" ]]; then
        booted=yes
        break
    fi
    sleep 2
done
if [[ "$booted" != "yes" ]]; then
    details="$(tail -n 30 "$report/emulator.log" 2>/dev/null | tr '\n' ' ')"
    report_failure "emulator did not finish booting within 8 minutes; emulator log: $details"
fi

# Let the package manager settle before instrumentation asks it to install APKs.
sleep 10
progress "device is ready; running :app:$task (15-minute limit)"
if timeout 900 ./gradlew --no-daemon ":app:$task" >"$report/gradle-test.log" 2>&1; then
    tail -n 20 "$report/gradle-test.log"
else
    status=$?
    details="$(tail -n 24 "$report/gradle-test.log" 2>/dev/null | tr '\n' ' ')"
    summarize_test_failures > "$report/test-failures.txt" 2>&1 || true
    if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
        annotate_test_failures || true
    fi
    test_details="$(head -n 4 "$report/test-failures.txt" 2>/dev/null | tr '\n' ' ')"
    report_failure ":app:$task failed or timed out (exit $status): $details Test failures: $test_details" "$status"
fi
progress "instrumentation task completed successfully"
