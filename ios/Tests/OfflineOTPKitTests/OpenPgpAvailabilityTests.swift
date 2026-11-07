import XCTest
@testable import OfflineOTPKit

/// The OpenPGP availability helpers are what the backup sheet consults before offering an action.
/// A build without the Rust XCFramework must disable both buttons rather than present a file
/// importer that can only fail — the rule Android already applies to its provider mode.
final class OpenPgpAvailabilityTests: XCTestCase {

    /// An in-process port stands in for a build that links the engine.
    private struct EchoPort: OpenPgpPort {
        func encrypt(plaintext: Data, recipientKeyBlocks: [Data]) throws -> Data {
            Data("PGP:".utf8) + plaintext
        }
        func decrypt(message: Data, secretKeyBlock: Data, passphrase: String?) throws -> Data {
            guard message.starts(with: Data("PGP:".utf8)) else {
                throw OpenPgpError.decryptionFailed(reason: "bad header")
            }
            return message.dropFirst(4)
        }
    }

    /// A port that declares itself unavailable, as the Rust port does without the framework.
    private struct UnavailablePort: OpenPgpPort {
        var isAvailable: Bool { false }
        func encrypt(plaintext: Data, recipientKeyBlocks: [Data]) throws -> Data {
            throw OpenPgpError.backendUnavailable(reason: "not built")
        }
        func decrypt(message: Data, secretKeyBlock: Data, passphrase: String?) throws -> Data {
            throw OpenPgpError.backendUnavailable(reason: "not built")
        }
    }

    func testInAppActionsFollowTheBackend() {
        let ready = OpenPgpAvailability.inApp(backendAvailable: true)
        XCTAssertTrue(ready.canEncrypt)
        XCTAssertTrue(ready.canRestore)

        let missing = OpenPgpAvailability.inApp(backendAvailable: false)
        XCTAssertFalse(missing.canEncrypt)
        XCTAssertFalse(missing.canRestore)
    }

    func testTheProviderRuleStillNeedsRecipientsOnlyForEncryption() {
        let noRecipients = OpenPgpAvailability.evaluate(
            providerConfigured: true, serviceReady: true, recipientCount: 0)
        XCTAssertTrue(noRecipients.canRestore)
        XCTAssertFalse(noRecipients.canEncrypt)

        let unbound = OpenPgpAvailability.evaluate(
            providerConfigured: true, serviceReady: false, recipientCount: 2)
        XCTAssertFalse(unbound.canRestore)
        XCTAssertFalse(unbound.canEncrypt)
    }

    func testAnInProcessPortIsAvailableByDefault() {
        // The default keeps injected test ports usable without declaring availability themselves.
        XCTAssertTrue(EchoPort().isAvailable)
        XCTAssertTrue(OpenPgpAvailability.inApp(backendAvailable: EchoPort().isAvailable).canEncrypt)
    }

    func testAPortCanDeclareItselfUnavailable() {
        let port = UnavailablePort()
        XCTAssertFalse(port.isAvailable)
        let availability = OpenPgpAvailability.inApp(backendAvailable: port.isAvailable)
        XCTAssertFalse(availability.canEncrypt)
        XCTAssertFalse(availability.canRestore)
    }

    func testTheRustPortReportsWhatThisBuildLinks() {
        // Whatever this checkout links, the port and the helper must agree: the UI must never offer
        // an action the port would refuse.
        let port = RustOpenPgpPort()
        XCTAssertEqual(port.isAvailable, RustOpenPgpPort.isBackendAvailable)
        let availability = OpenPgpAvailability.inApp(backendAvailable: port.isAvailable)
        XCTAssertEqual(availability.canEncrypt, RustOpenPgpPort.isBackendAvailable)
        XCTAssertEqual(availability.canRestore, RustOpenPgpPort.isBackendAvailable)

        if !port.isAvailable {
            XCTAssertThrowsError(try port.encrypt(plaintext: Data("x".utf8),
                                                  recipientKeyBlocks: [Data("key".utf8)])) { error in
                guard case OpenPgpError.backendUnavailable = error else {
                    return XCTFail("expected backendUnavailable, got \(error)")
                }
            }
        }
    }
}
