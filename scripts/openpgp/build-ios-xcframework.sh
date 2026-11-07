#!/usr/bin/env bash
# Build the unified OpenPGP Rust backend as an Apple XCFramework and place it where
# ios/Package.swift expects it. Run on macOS with Xcode and rustup installed.
#
# Default: universal Apple slices (arm64 + x86_64 where applicable).
# --arm64-only: the Apple-silicon CI path; it skips the Intel slices because the
# workflow's simulator/device artifacts and macOS host tests are arm64.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "build-ios-xcframework.sh must run on macOS (xcodebuild is required)" >&2
    exit 2
fi

ARM64_ONLY=false
if [[ "${1:-}" == "--arm64-only" ]]; then
    ARM64_ONLY=true
    shift
fi
if [[ $# -ne 0 ]]; then
    echo "usage: $0 [--arm64-only]" >&2
    exit 2
fi

if [[ "$ARM64_ONLY" == true ]]; then
    TARGETS=(
        aarch64-apple-ios
        aarch64-apple-ios-sim
        aarch64-apple-darwin
    )
else
    TARGETS=(
        aarch64-apple-ios
        aarch64-apple-ios-sim
        x86_64-apple-ios
        aarch64-apple-darwin
        x86_64-apple-darwin
    )
fi
rustup target add "${TARGETS[@]}"

CARGO_ARGS=(build --manifest-path rust/Cargo.toml --release)
for target in "${TARGETS[@]}"; do
    CARGO_ARGS+=(--target "$target")
done
cargo "${CARGO_ARGS[@]}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
HEADERS="$TMP/headers"
mkdir -p "$HEADERS"
cp rust/bindings/swift/offlineotp_openpgpFFI.h "$HEADERS/"
# xcodebuild expects the module map to be named module.modulemap inside the headers dir.
cp rust/bindings/swift/offlineotp_openpgpFFI.modulemap "$HEADERS/module.modulemap"

if [[ "$ARM64_ONLY" == true ]]; then
    SIM_LIBRARY=rust/target/aarch64-apple-ios-sim/release/libofflineotp_openpgp.a
    MACOS_LIBRARY=rust/target/aarch64-apple-darwin/release/libofflineotp_openpgp.a
else
    lipo -create \
        rust/target/aarch64-apple-ios-sim/release/libofflineotp_openpgp.a \
        rust/target/x86_64-apple-ios/release/libofflineotp_openpgp.a \
        -output "$TMP/libofflineotp_openpgp-ios-sim.a"
    lipo -create \
        rust/target/aarch64-apple-darwin/release/libofflineotp_openpgp.a \
        rust/target/x86_64-apple-darwin/release/libofflineotp_openpgp.a \
        -output "$TMP/libofflineotp_openpgp-macos.a"
    SIM_LIBRARY="$TMP/libofflineotp_openpgp-ios-sim.a"
    MACOS_LIBRARY="$TMP/libofflineotp_openpgp-macos.a"
fi

mkdir -p ios/Frameworks
rm -rf ios/Frameworks/offlineotp_openpgp.xcframework
xcodebuild -create-xcframework \
    -library rust/target/aarch64-apple-ios/release/libofflineotp_openpgp.a -headers "$HEADERS" \
    -library "$SIM_LIBRARY" -headers "$HEADERS" \
    -library "$MACOS_LIBRARY" -headers "$HEADERS" \
    -output ios/Frameworks/offlineotp_openpgp.xcframework

echo "Wrote ios/Frameworks/offlineotp_openpgp.xcframework"
