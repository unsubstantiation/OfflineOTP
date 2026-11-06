#!/usr/bin/env bash
# Build the unified OpenPGP Rust backend for the Android ABIs OfflineOTP ships and
# stage the cdylibs into app/src/main/jniLibs. Requires cargo-ndk and an NDK.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point at the installed Android NDK}"
if ! command -v cargo-ndk >/dev/null 2>&1; then
    echo "cargo-ndk is required (cargo install cargo-ndk)" >&2
    exit 2
fi

cargo ndk \
    -t arm64-v8a \
    -t armeabi-v7a \
    -t x86_64 \
    -o app/src/main/jniLibs \
    build --manifest-path rust/Cargo.toml --release

for abi in arm64-v8a armeabi-v7a x86_64; do
    test -f "app/src/main/jniLibs/$abi/libofflineotp_openpgp.so"
done
echo "Staged Android OpenPGP jniLibs"
