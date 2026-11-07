import Foundation

// MARK: - Unified OpenPGP backend — rpgp + UniFFI (5/5)
//
// The fifth distribution (iOS) previously had no OpenPGP implementation while
// Android talked to an external OpenKeychain provider and the desktop embedded
// PGPainless (JVM-only). The unified backend is a Rust crate
// (`rust/offlineotp-openpgp`) via rpgp, exposed through UniFFI as
// `offlineotp_openpgp` — a Kotlin/JNA library on Android+desktop and an
// XCFramework on Apple targets.
//
// This file is the Swift counterpart to `OpenPgpPort` in `:core` and
// `PgpainlessOpenPgpPort`/`RpgpOpenPgpPort` on the JVM. It owns the same
// document limits and empty-output rules as the password backup path and leaves
// the actual cryptography to the Rust core. The plaintext is the same compact
// vault/backup JSON document, so only the outer envelope differs from the
// password backup format.
//
// The Rust library is packaged as an XCFramework by `scripts/openpgp/build-ios-xcframework.sh`
// and built in `ios.yml`. When `ios/Frameworks/offlineotp_openpgp.xcframework` is present,
// Package.swift defines `OFFLINEOTP_OPENPGP_NATIVE` and this port calls the generated UniFFI
// bindings. Without that artifact the format layer still compiles and the port throws a
// descriptive `backendUnavailable` error rather than linking against a missing symbol.

/// Errors that the Rust backend surfaces. Mirrors `OpenPgpError` in `rust/offlineotp-openpgp/src/lib.rs`.
public enum OpenPgpError: Error, LocalizedError, Equatable {
    case invalidRecipientKey(reason: String)
    case invalidSecretKey(reason: String)
    case invalidMessage(reason: String)
    case decryptionFailed(reason: String)
    case emptyOutput
    case tooLarge
    case `internal`(reason: String)
    case backendUnavailable(reason: String)

    public var errorDescription: String? {
        switch self {
        case .invalidRecipientKey(let r): return "Invalid recipient key: \(r)"
        case .invalidSecretKey(let r): return "Invalid secret key: \(r)"
        case .invalidMessage(let r): return "Invalid message: \(r)"
        case .decryptionFailed(let r): return "Decryption failed: \(r)"
        case .emptyOutput: return "OpenPGP provider returned no output"
        case .tooLarge: return "OpenPGP message exceeds size limit"
        case .internal(let r): return "Internal: \(r)"
        case .backendUnavailable(let r): return r
        }
    }
}

/// Swift port of `OpenPgpPort` (Kotlin) — the platform seam for OpenPGP operations.
/// The JVM shells implement it with either PGPainless or the Rust backend; the Swift
/// shell will implement it with the Rust XCFramework once it is committed.
public protocol OpenPgpPort {
    /// Whether this port can actually perform cryptography in the current build. The UI asks before
    /// offering a backup or restore, so a build without the native library says so up front instead
    /// of failing after the user has picked files.
    var isAvailable: Bool { get }

    func encrypt(plaintext: Data, recipientKeyBlocks: [Data]) throws -> Data
    func decrypt(message: Data, secretKeyBlock: Data, passphrase: String?) throws -> Data
}

public extension OpenPgpPort {
    /// An in-process implementation is available unless it declares otherwise.
    var isAvailable: Bool { true }
}

/// Bounded, policy-enforcing OpenPGP backup codec shared by all platforms.
/// Mirrors `OpenPgpBackupCodec` in `:core`.
public enum OpenPgpBackupCodec {
    static let maxDocumentBytes = 16 * 1024 * 1024

    public static func seal(port: OpenPgpPort, plain: String, recipientKeyBlocks: [Data]) throws -> Data {
        guard !recipientKeyBlocks.isEmpty else {
            throw OpenPgpError.invalidRecipientKey(reason: "At least one OpenPGP recipient is required")
        }
        guard !recipientKeyBlocks.contains(where: { $0.isEmpty }) else {
            throw OpenPgpError.invalidRecipientKey(reason: "An OpenPGP recipient key block is empty")
        }
        let plainBytes = Data(plain.utf8)
        guard plainBytes.count <= maxDocumentBytes - 64 else { throw OpenPgpError.tooLarge }

        let message = try port.encrypt(plaintext: plainBytes, recipientKeyBlocks: recipientKeyBlocks)
        guard !message.isEmpty else { throw OpenPgpError.emptyOutput }
        guard message.count <= maxDocumentBytes else { throw OpenPgpError.tooLarge }
        return message
    }

    public static func open(port: OpenPgpPort, message: Data, secretKeyBlock: Data, passphrase: String?) throws -> String {
        guard !message.isEmpty else { throw OpenPgpError.invalidMessage(reason: "Empty OpenPGP message") }
        guard message.count <= maxDocumentBytes else { throw OpenPgpError.tooLarge }
        guard !secretKeyBlock.isEmpty else { throw OpenPgpError.invalidSecretKey(reason: "Missing OpenPGP secret key") }

        let plainBytes = try port.decrypt(message: message, secretKeyBlock: secretKeyBlock, passphrase: passphrase)
        guard let document = String(data: plainBytes, encoding: .utf8) else {
            throw OpenPgpError.invalidMessage(reason: "Not UTF-8")
        }
        // Mirror OpenPgpOutputPolicy.hasContent: reject blank provider output.
        let trimmed = document.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw OpenPgpError.emptyOutput }
        return document
    }
}

/// Unified Rust backend. Calls the UniFFI-generated `offlineotp_openpgp` Swift
/// bindings when the XCFramework has been built, otherwise throws `backendUnavailable`.
///
/// `OFFLINEOTP_OPENPGP_NATIVE` is defined by Package.swift only when
/// `ios/Frameworks/offlineotp_openpgp.xcframework` exists. That keeps the portable
/// format tests buildable on a checkout without the Rust toolchain while making the
/// packaged iOS app use real rpgp cryptography.
#if OFFLINEOTP_OPENPGP_NATIVE
import offlineotp_openpgp
#endif

public struct RustOpenPgpPort: OpenPgpPort {

    public init() {}

    /// True when this build links the Rust XCFramework.
    public static var isBackendAvailable: Bool {
        #if OFFLINEOTP_OPENPGP_NATIVE
        return true
        #else
        return false
        #endif
    }

    public var isAvailable: Bool { Self.isBackendAvailable }

    public func encrypt(plaintext: Data, recipientKeyBlocks: [Data]) throws -> Data {
        #if OFFLINEOTP_OPENPGP_NATIVE
        do {
            let output = try offlineotp_openpgp.encrypt(
                plaintext: [UInt8](plaintext),
                recipientKeyBlocks: recipientKeyBlocks.map { [UInt8]($0) }
            )
            return Data(output)
        } catch let error as offlineotp_openpgp.OpenPgpError {
            throw Self.map(error)
        }
        #else
        throw Self.unavailable()
        #endif
    }

    public func decrypt(message: Data, secretKeyBlock: Data, passphrase: String?) throws -> Data {
        #if OFFLINEOTP_OPENPGP_NATIVE
        do {
            let output = try offlineotp_openpgp.decrypt(
                message: [UInt8](message),
                secretKeyBlock: [UInt8](secretKeyBlock),
                passphrase: passphrase
            )
            return Data(output)
        } catch let error as offlineotp_openpgp.OpenPgpError {
            throw Self.map(error)
        }
        #else
        throw Self.unavailable()
        #endif
    }

    private static func unavailable() -> OpenPgpError {
        .backendUnavailable(reason:
            "Unified OpenPGP backend not available: Rust XCFramework not built. " +
            "Run scripts/openpgp/build-ios-xcframework.sh (or use the ios.yml artifact), " +
            "or restore via password backup. See docs/ios-client-plan.md."
        )
    }

    #if OFFLINEOTP_OPENPGP_NATIVE
    private static func map(_ error: offlineotp_openpgp.OpenPgpError) -> OpenPgpError {
        switch error {
        case .InvalidRecipientKey(let reason): return .invalidRecipientKey(reason: reason)
        case .InvalidSecretKey(let reason): return .invalidSecretKey(reason: reason)
        case .InvalidMessage(let reason): return .invalidMessage(reason: reason)
        case .DecryptionFailed(let reason): return .decryptionFailed(reason: reason)
        case .EmptyOutput: return .emptyOutput
        case .TooLarge: return .tooLarge
        case .Internal(let reason): return .internal(reason: reason)
        }
    }
    #endif
}

// MARK: - Availability helpers (mirror OpenPgpAvailability in :core)

/// Readiness policy for the OpenPGP service; restore does not require recipients.
public struct OpenPgpAvailability: Equatable {
    public let canRestore: Bool
    public let canEncrypt: Bool

    public static func evaluate(providerConfigured: Bool, serviceReady: Bool, recipientCount: Int) -> OpenPgpAvailability {
        let ready = providerConfigured && serviceReady
        return OpenPgpAvailability(canRestore: ready, canEncrypt: ready && recipientCount > 0)
    }

    /// The in-app engine has no provider to configure and no stored keys: the file importer collects
    /// the recipients or the secret key when the action runs. Only the backend gates the buttons, so
    /// an unbuilt XCFramework disables both rather than offering a picker that cannot succeed.
    public static func inApp(backendAvailable: Bool) -> OpenPgpAvailability {
        OpenPgpAvailability(canRestore: backendAvailable, canEncrypt: backendAvailable)
    }
}
