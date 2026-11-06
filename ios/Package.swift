// swift-tools-version:5.9
import PackageDescription
import Foundation

// The portable half of the OfflineOTP iOS client: the vault, backup and token formats shared with
// the Android app and the desktop client. No SwiftUI and no app target lives here, so the format
// layer can be unit-tested on a plain macOS runner without a simulator, a signing identity or an
// Apple developer account. See docs/ios-client-plan.md.
//
// OpenPGP is the unified Rust backend (`rust/offlineotp-openpgp`). When CI (or a local
// `scripts/openpgp/build-ios-xcframework.sh` run) has produced
// `ios/Frameworks/offlineotp_openpgp.xcframework`, the package compiles the generated UniFFI Swift
// bindings and `RustOpenPgpPort` performs real rpgp cryptography. Without that binary artifact the
// format layer still builds and tests, and the OpenPGP port fails explicitly with
// `backendUnavailable` instead of linking against a missing library.
let packageDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
let openPgpFrameworkPath = packageDirectory
    .appendingPathComponent("Frameworks/offlineotp_openpgp.xcframework").path
let hasOpenPgpFramework = FileManager.default.fileExists(atPath: openPgpFrameworkPath)

let openPgpDependencies: [Target.Dependency] = hasOpenPgpFramework
    ? [.target(name: "offlineotp_openpgp")]
    : []
let openPgpSettings: [SwiftSetting] = hasOpenPgpFramework
    ? [.define("OFFLINEOTP_OPENPGP_NATIVE")]
    : []

var targets: [Target] = [
    .target(
        name: "OfflineOTPKit",
        dependencies: openPgpDependencies,
        swiftSettings: openPgpSettings
    ),
    .testTarget(name: "OfflineOTPKitTests", dependencies: ["OfflineOTPKit"],
            // Read from the source tree through #filePath, not bundled.
            exclude: ["Fixtures"]),
]

if hasOpenPgpFramework {
    targets.append(.binaryTarget(
        name: "offlineotp_openpgpFFI",
        path: "Frameworks/offlineotp_openpgp.xcframework"
    ))
    targets.append(.target(
        name: "offlineotp_openpgp",
        dependencies: ["offlineotp_openpgpFFI"],
        path: "Sources/offlineotp_openpgp"
    ))
}

let package = Package(
    name: "OfflineOTPKit",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "OfflineOTPKit", targets: ["OfflineOTPKit"]),
    ],
    targets: targets
)
