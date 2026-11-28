import Foundation

/// One replaceable, crash-safe byte file — the port of `AtomicByteStore` in `:core`.
public protocol AtomicByteStore {
    func exists() -> Bool
    func read() throws -> Data
    func writeAtomic(_ data: Data) throws
    func delete()
}

/// The small flat key/value surface a password vault needs — the port of `KeyValueSettings`.
public protocol KeyValueSettings: AnyObject {
    func string(_ key: String) -> String?
    func setString(_ key: String, _ value: String)
    func integer(_ key: String) -> Int?
    func setInteger(_ key: String, _ value: Int)

    /// Deletes a value.
    ///
    /// Settings import/reset needs to distinguish an absent value from a stored default.
    /// Password rekey publishes settings only after the self-contained vault is promoted.
    /// Defaulted to doing nothing so an adapter that cannot delete still compiles — a store that
    /// can, must.
    func remove(_ key: String)

    /// The keys this store holds. Defaulted so existing adapters keep compiling; a store that can
    /// enumerate itself should override it, because the preferences export uses it to tell an
    /// *absent* setting from one stored at its default.
    func keys() -> Set<String>
}

extension KeyValueSettings {
    public func remove(_ key: String) { }

    public func keys() -> Set<String> { [] }
}

/// Stable setting names, shared with `VaultSettingsKeys` in `:core`.
public enum VaultSettingsKeys {
    public static let authMethod = "auth_method"
    public static let encryption = "encryption"
    public static let authKdf = "auth_kdf"
    public static let authCredentials = "auth_credentials"
    public static let authSalt = "auth_salt"
    public static let authIterations = "auth_iterations"
    public static let authMemoryKiB = "auth_memory_kib"
    public static let authParallelism = "auth_parallelism"
}

/// A password-encrypted vault, byte-compatible with the Android app and the desktop client.
///
/// `secrets.dat` is a nonce-prefixed AES-256-GCM blob over the compact vault document, keyed by the
/// lower 32 bytes (256 bits) of the 64-byte Argon2id output (legacy PBKDF2 vaults use the lower 16
/// bytes of the 32-byte PBKDF2 output with AES-128-GCM); the settings hold the 16-byte salt, the KDF
/// parameters and the Base64 URL-safe verifier (the upper half). The verifier is only a fast
/// pre-check — the GCM tag is the real gate, which is why an unknown password is reported as an
/// authentication failure and nothing else is revealed.
///
/// Android KeyStore vaults are out of scope: they have no portable equivalent.
public final class PasswordVault {
    public let vaultFile: AtomicByteStore
    public let backupFile: AtomicByteStore
    public let settings: KeyValueSettings
    private let storageLock = NSRecursiveLock()
    private let documents: EntryDocumentAdapter
    private let protocolPolicy: VaultProtocolAdapter
    private let persistence: PersistenceAdapter

    public init(vaultFile: AtomicByteStore, backupFile: AtomicByteStore, settings: KeyValueSettings,
                documents: EntryDocumentAdapter = NativeEntryDocumentAdapter(),
                protocolPolicy: VaultProtocolAdapter = NativeVaultProtocolAdapter(),
                persistence: PersistenceAdapter = NativePersistenceAdapter()) {
        self.vaultFile = vaultFile
        self.backupFile = backupFile
        self.settings = settings
        self.documents = documents
        self.protocolPolicy = protocolPolicy
        self.persistence = persistence
    }

    /// What an unlock returns: the entries and the session key. The key is never persisted here.
    public struct Unlocked {
        public let entries: [Entry]
        public let key: [UInt8]
    }

    public var isInitialized: Bool { vaultFile.exists() }

    /// A stable name for *this* vault, derived from its stored salt.
    ///
    /// Nothing secret: the salt sits next to the verifier in the settings file and is useless without
    /// the password. It is here so a device-held unlock key — a Keychain item the system releases only
    /// after Face ID — can be filed under the vault it belongs to, instead of one slot per app that
    /// would be handed to whichever vault happened to be opened next. A copy of the same vault has the
    /// same identifier and the same key; a different vault never shares one.
    public var vaultIdentifier: String? {
        storageLock.lock(); defer { storageLock.unlock() }
        if let bytes = try? vaultFile.read(), VaultContainer.isContainer(bytes) {
            guard let header = try? VaultContainer.header(bytes, protocolPolicy: protocolPolicy) else { return nil }
            return VaultCrypto.base64URLEncode(header.salt)
        }
        guard let saltText = settings.string(VaultSettingsKeys.authSalt),
              let salt = VaultCrypto.base64URLDecode(saltText), !salt.isEmpty else {
            return nil
        }
        return VaultCrypto.base64URLEncode(salt)
    }

    /// Provisions fresh credentials and writes an empty encrypted vault.
    public func create(password: String) throws { try create(password: password, entries: []) }

    /// Creates a new vault containing a complete, already-decoded restore in one encrypted write.
    public func create(password: String, entries: [Entry]) throws {
        storageLock.lock(); defer { storageLock.unlock() }
        guard persistence.canInitialize(vaultExists: vaultFile.exists(), backupExists: backupFile.exists()) else {
            throw VaultWriteError.alreadyExists
        }
        guard !password.isEmpty, password.utf16.count <= InputPolicy.maxTextChars else {
            throw VaultWriteError.invalidInput
        }
        let candidateSettings = CredentialSettings()
        let key = try provisionCredentials(password: password, target: candidateSettings)
        try writeEncrypted(key: key, entries: entries, newHeader: VaultContainer.fromSettings(candidateSettings, protocolPolicy: protocolPolicy))
        candidateSettings.publish(to: settings)
    }

    /// Verifies the password against the stored verifier, then decrypts the vault.
    public func open(password: String) throws -> Unlocked {
        storageLock.lock(); defer { storageLock.unlock() }
        guard vaultFile.exists() else { throw VaultOpenError.missing }
        guard !password.isEmpty else { throw VaultOpenError.authenticationFailed }
        let bytes: Data
        do { bytes = try vaultFile.read() } catch { throw VaultOpenError.ioFailure }
        let key: [UInt8]
        if VaultContainer.isContainer(bytes) {
            key = try VaultContainer.deriveKey(password: password, header: VaultContainer.header(bytes, protocolPolicy: protocolPolicy))
        } else { key = try deriveSessionKey(password: password) }
        return try decrypt(key: key, bytes: bytes)
    }

    /// Unlocks with an already-derived key (the hardware-unlock counterpart): no password
    /// verification is possible, so the GCM tag alone decides.
    public func openWithKey(_ key: [UInt8]) throws -> Unlocked {
        storageLock.lock(); defer { storageLock.unlock() }
        guard vaultFile.exists() else { throw VaultOpenError.missing }
        return try decrypt(key: key)
    }

    /// Serializes, encrypts and promotes the whole vault atomically.
    public func save(key: [UInt8], entries: [Entry]) throws {
        storageLock.lock(); defer { storageLock.unlock() }
        try writeEncrypted(key: key, entries: entries)
    }

    /// Copies the current vault bytes over the `.bck` store.
    @discardableResult
    public func backup() -> Bool {
        storageLock.lock(); defer { storageLock.unlock() }
        do {
            try backupFile.writeAtomic(vaultFile.read())
            return true
        } catch {
            return false
        }
    }

    /// Promotes the `.bck` bytes back over the vault.
    @discardableResult
    public func restoreBackup() -> Bool {
        storageLock.lock(); defer { storageLock.unlock() }
        do {
            try vaultFile.writeAtomic(backupFile.read())
            return true
        } catch {
            return false
        }
    }

    /// Verifies `currentPassword`, provisions fresh Argon2id credentials for `newPassword`,
    /// and promotes a verified self-contained vault before publishing compatibility settings.
    /// Ambiguous promotion never triggers an automatic overwrite with the rollback copy.
    public func changePassword(currentPassword: String, newPassword: String) throws -> Unlocked {
        storageLock.lock(); defer { storageLock.unlock() }
        guard !newPassword.isEmpty, newPassword.utf16.count <= InputPolicy.maxTextChars else {
            throw VaultWriteError.invalidInput
        }
        let unlocked = try open(password: currentPassword)
        let workflow = persistence.transaction(requiresBackup: true)
        guard workflow.verified() else { throw VaultWriteError.ioFailure }
        guard backup() else {
            throw VaultWriteError.ioFailure
        }
        guard workflow.backedUp() else { throw VaultWriteError.ioFailure }
        do {
            let candidateSettings = CredentialSettings()
            let newKey = try provisionCredentials(password: newPassword, target: candidateSettings)
            do {
                guard workflow.beginPromotion() else { throw VaultWriteError.ioFailure }
                try writeEncrypted(key: newKey, entries: unlocked.entries, newHeader: VaultContainer.fromSettings(candidateSettings, protocolPolicy: protocolPolicy))
                guard workflow.promoted() else { throw VaultWriteError.ioFailure }
                candidateSettings.publish(to: settings)
                return Unlocked(entries: unlocked.entries, key: newKey)
            } catch {
                workflow.promotionFailed()
                throw error
            }
        } catch let err as VaultWriteError {
            throw err
        } catch {
            throw VaultWriteError.ioFailure
        }
    }

    /// A full backup of the exact authenticated file, not a re-encrypted entry export.
    public func snapshot(key: [UInt8]) throws -> Data {
        storageLock.lock(); defer { storageLock.unlock() }
        let bytes = try vaultFile.read()
        guard VaultContainer.isContainer(bytes) else { throw VaultWriteError.invalidInput }
        var plaintext = try VaultContainer.openWithKey(key, data: bytes, protocolPolicy: protocolPolicy)
        defer { plaintext.resetBytes(in: 0..<plaintext.count) }
        return bytes
    }

    /// Never performed by open/save. Keep original bytes in .bck before atomic promotion.
    public func migrateToContainer(password: String) throws {
        storageLock.lock(); defer { storageLock.unlock() }
        let opened = try open(password: password)
        let prior = try vaultFile.read()
        if VaultContainer.isContainer(prior) { return }
        let descriptor = try VaultContainer.fromSettings(settings, protocolPolicy: protocolPolicy)
        var plaintext = try VaultCrypto.open(prior, key: opened.key)
        defer { plaintext.resetBytes(in: 0..<plaintext.count) }
        let migrated = try VaultContainer.seal(key: opened.key, header: descriptor, plaintext: plaintext, protocolPolicy: protocolPolicy)
        var verified = try VaultContainer.open(password: password, data: migrated, protocolPolicy: protocolPolicy)
        verified.resetBytes(in: 0..<verified.count)
        let workflow = persistence.transaction(requiresBackup: true)
        guard workflow.verified() else { throw VaultWriteError.ioFailure }
        try backupFile.writeAtomic(prior)
        guard workflow.backedUp(), workflow.beginPromotion() else { throw VaultWriteError.ioFailure }
        do {
            try vaultFile.writeAtomic(migrated)
            guard workflow.promoted() else { throw VaultWriteError.ioFailure }
        } catch { workflow.promotionFailed(); throw error }
    }

    // MARK: - Internals

    private func provisionCredentials(password: String, target: KeyValueSettings) throws -> [UInt8] {
        let salt = VaultCrypto.randomBytes(VaultCrypto.argon2SaltBytes)
        let memoryKiB = VaultCrypto.argon2MemoryKiB
        let iterations = VaultCrypto.argon2Iterations
        let parallelism = VaultCrypto.argon2Parallelism
        let credentials = try VaultCrypto.argon2idCredentials(
            password: password,
            salt: salt,
            memoryKiB: memoryKiB,
            iterations: iterations,
            parallelism: parallelism
        )
        target.setString(VaultSettingsKeys.authMethod, "password")
        target.setString(VaultSettingsKeys.encryption, "password")
        target.setString(VaultSettingsKeys.authKdf, VaultCrypto.kdfArgon2id)
        target.setString(VaultSettingsKeys.authCredentials,
                           VaultCrypto.base64URLEncode(credentials.verifier))
        target.setString(VaultSettingsKeys.authSalt, VaultCrypto.base64URLEncode(salt))
        target.setInteger(VaultSettingsKeys.authMemoryKiB, memoryKiB)
        target.setInteger(VaultSettingsKeys.authIterations, iterations)
        target.setInteger(VaultSettingsKeys.authParallelism, parallelism)
        return credentials.key
    }

    private func deriveSessionKey(password: String) throws -> [UInt8] {
        guard let saltText = settings.string(VaultSettingsKeys.authSalt),
              let verifierText = settings.string(VaultSettingsKeys.authCredentials),
              let salt = VaultCrypto.base64URLDecode(saltText),
              let verifier = VaultCrypto.base64URLDecode(verifierText),
              !salt.isEmpty, !verifier.isEmpty else {
            throw VaultOpenError.corrupt
        }
        let rawKdf = settings.string(VaultSettingsKeys.authKdf)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased() ?? ""
        let kdf = rawKdf.isEmpty ? VaultCrypto.kdfPbkdf2 : rawKdf

        let credentials: VaultCrypto.Credentials
        switch kdf {
        case VaultCrypto.kdfArgon2id:
            let memoryKiB = settings.integer(VaultSettingsKeys.authMemoryKiB)
                ?? VaultCrypto.argon2MemoryKiB
            let iterations = settings.integer(VaultSettingsKeys.authIterations)
                ?? VaultCrypto.argon2Iterations
            let parallelism = settings.integer(VaultSettingsKeys.authParallelism)
                ?? VaultCrypto.argon2Parallelism
            do {
                credentials = try VaultCrypto.argon2idCredentials(
                    password: password,
                    salt: salt,
                    memoryKiB: memoryKiB,
                    iterations: iterations,
                    parallelism: parallelism
                )
            } catch {
                throw VaultOpenError.corrupt
            }
        case VaultCrypto.kdfPbkdf2:
            let iterations = settings.integer(VaultSettingsKeys.authIterations)
                ?? VaultCrypto.minAuthIterations
            do {
                credentials = try VaultCrypto.credentials(
                    password: password,
                    salt: salt,
                    iterations: iterations
                )
            } catch {
                throw VaultOpenError.corrupt
            }
        default:
            throw VaultOpenError.corrupt
        }

        guard VaultCrypto.constantTimeEquals(verifier, credentials.verifier) else {
            throw VaultOpenError.authenticationFailed
        }
        return credentials.key
    }

    private func decrypt(key: [UInt8], bytes: Data? = nil) throws -> Unlocked {
        let sealed: Data
        do {
            if let bytes { sealed = bytes } else { sealed = try vaultFile.read() }
        } catch {
            throw VaultOpenError.ioFailure
        }
        var plain: Data
        do {
            if VaultContainer.isContainer(sealed) { plain = try VaultContainer.openWithKey(key, data: sealed, protocolPolicy: protocolPolicy) }
            else { plain = try VaultCrypto.open(sealed, key: key) }
        } catch VaultCryptoError.authenticationFailed {
            throw VaultOpenError.authenticationFailed
        } catch let error as VaultOpenError {
            throw error
        } catch {
            throw VaultOpenError.ioFailure
        }
        defer { plain.resetBytes(in: 0..<plain.count) }
        do {
            guard let text = String(data: plain, encoding: .utf8) else { throw VaultOpenError.corrupt }
            return Unlocked(entries: try documents.decode(text), key: key)
        } catch {
            throw VaultOpenError.corrupt
        }
    }

    private func writeEncrypted(key: [UInt8], entries: [Entry], newHeader: VaultContainer.Header? = nil) throws {
        let text: String
        do {
            text = try documents.encode(entries)
        } catch {
            throw VaultWriteError.invalidInput
        }
        let plain = Data(text.utf8)
        // Leave room for the nonce and the tag, as `writeEncrypted` in `:core` does.
        guard plain.count <= InputPolicy.maxDocumentBytes - 64 else {
            throw VaultWriteError.invalidInput
        }
        let ciphertext: Data
        do {
            var descriptor = newHeader
            if descriptor == nil, vaultFile.exists() {
                let prior = try vaultFile.read()
                if VaultContainer.isContainer(prior) {
                    var authenticated = try VaultContainer.openWithKey(key, data: prior, protocolPolicy: protocolPolicy)
                    authenticated.resetBytes(in: 0..<authenticated.count)
                    descriptor = try VaultContainer.header(prior, protocolPolicy: protocolPolicy)
                }
            }
            if let descriptor { ciphertext = try VaultContainer.seal(key: key, header: descriptor, plaintext: plain, protocolPolicy: protocolPolicy) }
            else { ciphertext = try VaultCrypto.sealWithRandomNonce(plain, key: key) }
        } catch {
            throw VaultWriteError.ioFailure
        }
        let workflow = persistence.transaction(requiresBackup: false)
        do {
            var verified: Data
            if VaultContainer.isContainer(ciphertext) {
                verified = try VaultContainer.openWithKey(key, data: ciphertext, protocolPolicy: protocolPolicy)
            } else { verified = try VaultCrypto.open(ciphertext, key: key) }
            verified.resetBytes(in: 0..<verified.count)
            guard workflow.verified(), workflow.beginPromotion() else { throw VaultWriteError.ioFailure }
            try vaultFile.writeAtomic(ciphertext)
            guard workflow.promoted() else { throw VaultWriteError.ioFailure }
        } catch {
            workflow.promotionFailed()
            throw VaultWriteError.ioFailure
        }
    }
}

public enum VaultOpenError: Error, Equatable {
    case missing
    case authenticationFailed
    case ioFailure
    case corrupt
}

public enum VaultWriteError: Error, Equatable {
    case alreadyExists
    case invalidInput
    case ioFailure
}

/// A file-backed `AtomicByteStore` for the app's `Documents` folder.
///
/// `Data.write(options: .atomic)` stages the bytes in a temporary file in the same directory and
/// renames it into place, so a failed write leaves the previous target intact; the read-back check
/// proves the promoted bytes are the ones that were handed over. The file is excluded from iCloud
/// backups by the caller when that is wanted (`isExcludedFromBackup`), because the vault has its own
/// encrypted backup story.
public struct FileByteStore: AtomicByteStore {
    public let url: URL

    public init(url: URL) {
        self.url = url
    }

    public func exists() -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    public func read() throws -> Data {
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        let size = (attributes[.size] as? NSNumber)?.intValue ?? 0
        guard size <= InputPolicy.maxDocumentBytes else {
            throw FileByteStoreError.tooLarge
        }
        do { return try BoundedFileReader.read(url) }
        catch BoundedFileReader.Failure.tooLarge { throw FileByteStoreError.tooLarge }
    }

    public func writeAtomic(_ data: Data) throws {
        guard data.count <= InputPolicy.maxDocumentBytes else {
            throw FileByteStoreError.tooLarge
        }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                withIntermediateDirectories: true)
        try data.write(to: url, options: [.atomic])
        let promoted = try read()
        guard promoted == data else { throw FileByteStoreError.verificationFailed }
    }

    public func delete() {
        try? FileManager.default.removeItem(at: url)
    }
}

public enum FileByteStoreError: Error, Equatable {
    case tooLarge
    case verificationFailed
}

/// A plist-backed `KeyValueSettings`, the mirror of the desktop `vault.properties` file.
///
/// It holds the salt, the iteration count and the verifier — the material needed to *check* a
/// password, none of which unlocks a vault on its own. The password, the derived key and any Keychain
/// item stay out of it; the app keeps the unlocked key in memory only.
public final class PropertyListSettings: KeyValueSettings {
    private let url: URL
    private var values: [String: Any]

    public init(url: URL) {
        self.url = url
        if let data = try? BoundedFileReader.read(url),
           let parsed = try? PropertyListSerialization.propertyList(from: data, format: nil),
           let dictionary = parsed as? [String: Any] {
            values = dictionary
        } else {
            values = [:]
        }
    }

    public func string(_ key: String) -> String? { values[key] as? String }

    public func setString(_ key: String, _ value: String) {
        values[key] = value
        persist()
    }

    public func keys() -> Set<String> { Set(values.keys) }

    public func remove(_ key: String) {
        guard values.removeValue(forKey: key) != nil else { return }
        persist()
    }

    public func integer(_ key: String) -> Int? { values[key] as? Int }

    public func setInteger(_ key: String, _ value: Int) {
        values[key] = value
        persist()
    }

    /// Merge only imported keys, preserving vault material and settings changed since preview.
    /// Publish the in-memory view only after the complete atomic file write succeeds.
    public func mergeAtomic(_ changes: [String: Any]) throws {
        guard !changes.isEmpty else { return }
        var next = values
        next.merge(changes) { _, replacement in replacement }
        let data = try PropertyListSerialization.data(fromPropertyList: next, format: .xml, options: 0)
        guard data.count <= InputPolicy.maxDocumentBytes else { throw FileByteStoreError.tooLarge }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: [.atomic])
        values = next
    }

    private func persist() {
        guard let data = try? PropertyListSerialization.data(fromPropertyList: values,
                                                            format: .xml,
                                                            options: 0) else { return }
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                 withIntermediateDirectories: true)
        try? data.write(to: url, options: [.atomic])
    }
}

/// Only the self-contained promoted vault authorizes publishing these compatibility settings.
private final class CredentialSettings: KeyValueSettings {
    private var strings: [String: String] = [:]
    private var ints: [String: Int] = [:]
    func string(_ key: String) -> String? { strings[key] }
    func integer(_ key: String) -> Int? { ints[key] }
    func setString(_ key: String, _ value: String) { strings[key] = value }
    func setInteger(_ key: String, _ value: Int) { ints[key] = value }
    func publish(to target: KeyValueSettings) {
        for (key, value) in strings { target.setString(key, value) }
        for (key, value) in ints { target.setInteger(key, value) }
    }
}
