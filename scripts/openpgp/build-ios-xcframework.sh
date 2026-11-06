#!/usr/bin/env bash
# Build the unified OpenPGP Rust backend as an Apple XCFramework and place it where
# ios/Package.swift expects it. Run on macOS with Xcode and rustup installed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "build-ios-xcframework.sh must run on macOS (xcodebuild is required)" >&2
    exit 2
fi

TARGETS=(
    aarch64-apple-ios
    aarch64-apple-ios-sim
    x86_64-apple-ios
    aarch64-apple-darwin
    x86_64-apple-darwin
)
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

lipo -create \
    rust/target/aarch64-apple-ios-sim/release/libofflineotp_openpgp.a \
    rust/target/x86_64-apple-ios/release/libofflineotp_openpgp.a \
    -output "$TMP/libofflineotp_openpgp-ios-sim.a"
lipo -create \
    rust/target/aarch64-apple-darwin/release/libofflineotp_openpgp.a \
    rust/target/x86_64-apple-darwin/release/libofflineotp_openpgp.a \
    -output "$TMP/libofflineotp_openpgp-macos.a"

mkdir -p ios/Frameworks
rm -rf ios/Frameworks/offlineotp_openpgp.xcframework
xcodebuild -create-xcframework \
    -library rust/target/aarch64-apple-ios/release/libofflineotp_openpgp.a -headers "$HEADERS" \
    -library "$TMP/libofflineotp_openpgp-ios-sim.a" -headers "$HEADERS" \
    -library "$TMP/libofflineotp_openpgp-macos.a" -headers "$HEADERS" \
    -output ios/Frameworks/offlineotp_openpgp.xcframework

echo "Wrote ios/Frameworks/offlineotp_openpgp.xcframework"
