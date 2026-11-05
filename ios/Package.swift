// swift-tools-version:5.9
import PackageDescription

// The portable half of the OfflineOTP iOS client: the vault, backup and token formats shared with
// the Android app and the desktop client. No SwiftUI and no app target lives here, so the format
// layer can be unit-tested on a plain macOS runner without a simulator, a signing identity or an
// Apple developer account. See docs/ios-client-plan.md.
let package = Package(
    name: "OfflineOTPKit",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "OfflineOTPKit", targets: ["OfflineOTPKit"]),
    ],
    targets: [
        .target(name: "OfflineOTPKit"),
        .testTarget(name: "OfflineOTPKitTests", dependencies: ["OfflineOTPKit"],
                // Read from the source tree through #filePath, not bundled.
                exclude: ["Fixtures"]),
    ]
)
