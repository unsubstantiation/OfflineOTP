#!/usr/bin/env bash
# Required before XcodeGen / xcodebuild. Never falls back to a different UI.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ "$(uname -s)" != Darwin ]]; then
    echo "Compose iOS frameworks require macOS and Xcode." >&2
    exit 1
fi
cd "$ROOT"
./gradlew :ui-foundation:assembleOfflineOTPPresentationReleaseXCFramework
SOURCE="$ROOT/ui-foundation/build/XCFrameworks/release/OfflineOTPPresentation.xcframework"
[[ -d "$SOURCE" ]] || { echo "Missing Compose framework: $SOURCE" >&2; exit 1; }
mkdir -p "$ROOT/ios/Frameworks"
# Replace, rather than merge stale framework slices from a previous build.
rm -rf "$ROOT/ios/Frameworks/OfflineOTPPresentation.xcframework"
ditto "$SOURCE" "$ROOT/ios/Frameworks/OfflineOTPPresentation.xcframework"
