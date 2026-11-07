#!/usr/bin/env bash
# Build the unified OpenPGP Rust backend for the Android ABIs OfflineOTP ships and
# stage the cdylibs into app/src/main/jniLibs. Requires cargo-ndk, rustup and an NDK.
#
# Self-contained on purpose: the iOS counterpart (`build-ios-xcframework.sh`) installs its own
# rustup targets, and this script must too. `cargo ndk` cross-compiles with whatever toolchain
# rustup already knows about; it does **not** install `std` for the Android ABIs, so on a clean
# runner the build fails with "can't find crate for `std`" / "the <target> target may not be
# installed" and — before this script checked its inputs — the only signal was a bare exit 1.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

ABIS=(arm64-v8a armeabi-v7a x86_64)
RUST_TARGETS=(aarch64-linux-android armv7-linux-androideabi x86_64-linux-android)

# Resolve the NDK: honour either variable, otherwise take the newest installed one. The workflow
# pins a version, but `sdkmanager` may lay it down under a different path, and a contributor's
# local setup is not guaranteed to export the same variable name.
if [[ -z "${ANDROID_NDK_HOME:-}" && -n "${ANDROID_NDK_ROOT:-}" ]]; then
    ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"
fi
if [[ -z "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_HOME:-/nonexistent}/ndk" ]]; then
    ANDROID_NDK_HOME="$(find "${ANDROID_HOME}/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
fi
if [[ -z "${ANDROID_NDK_HOME:-}" || ! -d "${ANDROID_NDK_HOME}" ]]; then
    # GitHub annotation as well as stderr: on a runner the log body is only reachable through the
    # log download, while an ::error line surfaces as the failing step's annotation.
    message="ANDROID_NDK_HOME must point at an installed Android NDK (tried: \${ANDROID_NDK_HOME:-<unset>} and \$ANDROID_HOME/ndk/*; install with sdkmanager 'ndk;27.2.12479018')"
    echo "$message" >&2
    echo "::error title=Android NDK not found::$message"
    exit 2
fi
export ANDROID_NDK_HOME
echo "NDK:   ${ANDROID_NDK_HOME}"

if ! command -v cargo-ndk >/dev/null 2>&1; then
    echo "cargo-ndk is required (cargo install cargo-ndk --locked)" >&2
    exit 2
fi
echo "cargo: $(cargo --version)"

# Required, not cosmetic: without these the cross-compile has no `std` for the target.
rustup target add "${RUST_TARGETS[@]}"

cargo ndk \
    $(for abi in "${ABIS[@]}"; do printf -- '-t %s ' "$abi"; done) \
    -o app/src/main/jniLibs \
    build --manifest-path rust/Cargo.toml --release

for abi in "${ABIS[@]}"; do
    if [[ ! -f "app/src/main/jniLibs/$abi/libofflineotp_openpgp.so" ]]; then
        echo "::error title=OpenPGP jniLibs::missing app/src/main/jniLibs/$abi/libofflineotp_openpgp.so after the build"
        exit 1
    fi
    echo "staged app/src/main/jniLibs/$abi/libofflineotp_openpgp.so"
done
echo "Staged Android OpenPGP jniLibs"
