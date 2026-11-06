#!/usr/bin/env bash
# Regenerate (or check) the committed UniFFI bindings from the reviewed UDL contract.
#
# Requires uniffi-bindgen 0.28.3 on PATH (or UNIFFI_BINDGEN=/path/to/uniffi-bindgen).
# UDL mode does not need a compiled Rust library, so this runs on any host with the
# bindgen CLI. Native compilation/tests still require cargo and the platform toolchains.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
UDL="$ROOT/rust/offlineotp-openpgp/src/offlineotp_openpgp.udl"
BINDGEN="${UNIFFI_BINDGEN:-uniffi-bindgen}"
MODE="${1:-generate}"

if ! command -v "$BINDGEN" >/dev/null 2>&1; then
    echo "uniffi-bindgen 0.28.3 is required (cargo install uniffi-bindgen --version 0.28.3)" >&2
    exit 2
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

CONFIG="$ROOT/rust/offlineotp-openpgp/uniffi.toml"
"$BINDGEN" generate "$UDL" --language kotlin --out-dir "$TMP/kotlin" --no-format --crate offlineotp_openpgp --config "$CONFIG"
"$BINDGEN" generate "$UDL" --language swift --out-dir "$TMP/swift" --no-format --crate offlineotp_openpgp --config "$CONFIG"

commit_file() {
    local src="$1" dst="$2"
    mkdir -p "$(dirname "$dst")"
    if [[ "$MODE" == "--check" ]]; then
        if ! cmp -s "$src" "$dst"; then
            echo "binding drift: $dst" >&2
            diff -u "$dst" "$src" >&2 || true
            return 1
        fi
    else
        cp "$src" "$dst"
    fi
}

commit_file "$TMP/kotlin/uniffi/offlineotp_openpgp/offlineotp_openpgp.kt" \
    "$ROOT/rust/bindings/kotlin/uniffi/offlineotp_openpgp/offlineotp_openpgp.kt"
commit_file "$TMP/swift/offlineotp_openpgp.swift" \
    "$ROOT/ios/Sources/offlineotp_openpgp/offlineotp_openpgp.swift"
commit_file "$TMP/swift/offlineotp_openpgpFFI.h" \
    "$ROOT/rust/bindings/swift/offlineotp_openpgpFFI.h"
commit_file "$TMP/swift/offlineotp_openpgpFFI.modulemap" \
    "$ROOT/rust/bindings/swift/offlineotp_openpgpFFI.modulemap"

echo "UniFFI bindings ${MODE/--check/checked}"
