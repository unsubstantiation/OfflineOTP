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
// During scaffolding the Rust library is optional: when it cannot be loaded
// (e.g. a checkout without `cargo build` or a simulator build before the
// XCFramework is committed) this type throws a descriptive error rather than
// linking against a missing symbol. Once the crate is wired into `ios.yml`
// and the XCFramework is committed, the fallback will be removed.

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
    func encrypt(plaintext: Data, recipientKeyBlocks: [Data]) throws -> Data
    func decrypt(message: Data, secretKeyBlock: Data, passphrase: String?) throws -> Data
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

/// Unified Rust backend (scaffolding). Calls the UniFFI-generated `offlineotp_openpgp`
/// Swift bindings when they exist, otherwise throws `backendUnavailable`.
///
/// After `rust/offlineotp-openpgp` is built and the XCFramework is committed, this
/// type will hold the generated `offlineotp_openpgp` module and forward directly.
/// The scaffolding keeps the package compiling in the offline sandbox and on checkouts
/// without the Rust toolchain.
public struct RustOpenPgpPort: OpenPgpPort {

    public init() {}

    public func encrypt(plaintext: Data, recipientKeyBlocks: [Data]) throws -> Data {
        // Attempt to call the UniFFI-generated Swift bindings. When the XCFramework
        // is not yet committed this symbol is absent; we throw a clear error rather
        // than linking against a missing library.
        // The generated module will be `import offlineotp_openpgp` and the function
        // `encrypt(plaintext:recipientKeyBlocks:)`.
        //
        // During scaffolding we do not have the XCFramework, so we report unavailability.
        // The compiler will not see `offlineotp_openpgp` as a target dependency yet.
        throw OpenPgpError.backendUnavailable(reason:
            "Unified OpenPGP backend not available: Rust XCFramework not built. " +
            "Run `cargo build --manifest-path rust/Cargo.toml` and generate the " +
            "UniFFI bindings, or restore via password backup. See docs/ios-client-plan.md."
        )
        // When the XCFramework is available, the body becomes:
        // return try offlineotp_openpgp.encrypt(plaintext: plaintext, recipientKeyBlocks: recipientKeyBlocks)
    }

    public func decrypt(message: Data, secretKeyBlock: Data, passphrase: String?) throws -> Data {
        throw OpenPgpError.backendUnavailable(reason:
            "Unified OpenPGP backend not available: Rust XCFramework not built. " +
            "Run `cargo build --manifest-path rust/Cargo.toml` and generate the " +
            "UniFFI bindings. See docs/ios-client-plan.md."
        )
        // When available:
        // return try offlineotp_openpgp.decrypt(message: message, secretKeyBlock: secretKeyBlock, passphrase: passphrase)
    }
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
}
