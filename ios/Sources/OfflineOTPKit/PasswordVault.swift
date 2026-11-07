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

    /// The keys this store holds. Defaulted so existing adapters keep compiling; a store that can
    /// enumerate itself should override it, because the preferences export uses it to tell an
    /// *absent* setting from one stored at its default.
    func keys() -> Set<String>
}

extension KeyValueSettings {
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

    public init(vaultFile: AtomicByteStore, backupFile: AtomicByteStore, settings: KeyValueSettings) {
        self.vaultFile = vaultFile
        self.backupFile = backupFile
        self.settings = settings
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
        guard let saltText = settings.string(VaultSettingsKeys.authSalt),
              let salt = VaultCrypto.base64URLDecode(saltText), !salt.isEmpty else {
            return nil
        }
        return VaultCrypto.base64URLEncode(salt)
    }

    /// Provisions fresh credentials and writes an empty encrypted vault.
    public func create(password: String) throws {
        guard !vaultFile.exists(), !backupFile.exists() else {
            throw VaultWriteError.alreadyExists
        }
        guard !password.isEmpty, password.count <= InputPolicy.maxVaultPasswordChars else {
            throw VaultWriteError.invalidInput
        }
        let key = try provisionCredentials(password: password)
        try writeEncrypted(key: key, entries: [])
    }

    /// Verifies the password against the stored verifier, then decrypts the vault.
    public func open(password: String) throws -> Unlocked {
        guard vaultFile.exists() else { throw VaultOpenError.missing }
        let key = try deriveSessionKey(password: password)
        return try decrypt(key: key)
    }

    /// Unlocks with an already-derived key (the hardware-unlock counterpart): no password
    /// verification is possible, so the GCM tag alone decides.
    public func openWithKey(_ key: [UInt8]) throws -> Unlocked {
        guard vaultFile.exists() else { throw VaultOpenError.missing }
        return try decrypt(key: key)
    }

    /// Serializes, encrypts and promotes the whole vault atomically.
    public func save(key: [UInt8], entries: [Entry]) throws {
        try writeEncrypted(key: key, entries: entries)
    }

    /// Copies the current vault bytes over the `.bck` store.
    @discardableResult
    public func backup() -> Bool {
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
        do {
            try vaultFile.writeAtomic(backupFile.read())
            return true
        } catch {
            return false
        }
    }

    /// Verifies `currentPassword`, provisions fresh Argon2id credentials for `newPassword`,
    /// and re-encrypts the vault atomically, rolling back settings + `.bck` on failure.
    public func changePassword(currentPassword: String, newPassword: String) throws -> Unlocked {
        guard !newPassword.isEmpty, newPassword.count <= InputPolicy.maxVaultPasswordChars else {
            throw VaultWriteError.invalidInput
        }
        let unlocked = try open(password: currentPassword)
        guard backup() else {
            throw VaultWriteError.ioFailure
        }
        let priorStrings: [String: String?] = [
            VaultSettingsKeys.authMethod: settings.string(VaultSettingsKeys.authMethod),
            VaultSettingsKeys.encryption: settings.string(VaultSettingsKeys.encryption),
            VaultSettingsKeys.authKdf: settings.string(VaultSettingsKeys.authKdf),
            VaultSettingsKeys.authCredentials: settings.string(VaultSettingsKeys.authCredentials),
            VaultSettingsKeys.authSalt: settings.string(VaultSettingsKeys.authSalt),
        ]
        let priorInts: [String: Int?] = [
            VaultSettingsKeys.authMemoryKiB: settings.integer(VaultSettingsKeys.authMemoryKiB),
            VaultSettingsKeys.authIterations: settings.integer(VaultSettingsKeys.authIterations),
            VaultSettingsKeys.authParallelism: settings.integer(VaultSettingsKeys.authParallelism),
        ]
        func rollbackSettings() {
            for (k, v) in priorStrings {
                if let v { settings.setString(k, v) } else { settings.remove(k) }
            }
            for (k, v) in priorInts {
                if let v { settings.setInteger(k, v) } else { settings.remove(k) }
            }
        }
        do {
            let newKey = try provisionCredentials(password: newPassword)
            do {
                try writeEncrypted(key: newKey, entries: unlocked.entries)
                return Unlocked(entries: unlocked.entries, key: newKey)
            } catch {
                rollbackSettings()
                _ = restoreBackup()
                throw error
            }
        } catch let err as VaultWriteError {
            rollbackSettings()
            throw err
        } catch {
            rollbackSettings()
            throw VaultWriteError.ioFailure
        }
    }

    // MARK: - Internals

    private func provisionCredentials(password: String) throws -> [UInt8] {
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
        settings.setString(VaultSettingsKeys.authMethod, "password")
        settings.setString(VaultSettingsKeys.encryption, "password")
        settings.setString(VaultSettingsKeys.authKdf, VaultCrypto.kdfArgon2id)
        settings.setString(VaultSettingsKeys.authCredentials,
                           VaultCrypto.base64URLEncode(credentials.verifier))
        settings.setString(VaultSettingsKeys.authSalt, VaultCrypto.base64URLEncode(salt))
        settings.setInteger(VaultSettingsKeys.authMemoryKiB, memoryKiB)
        settings.setInteger(VaultSettingsKeys.authIterations, iterations)
        settings.setInteger(VaultSettingsKeys.authParallelism, parallelism)
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

    private func decrypt(key: [UInt8]) throws -> Unlocked {
        let sealed: Data
        do {
            sealed = try vaultFile.read()
        } catch {
            throw VaultOpenError.ioFailure
        }
        let plain: Data
        do {
            plain = try VaultCrypto.open(sealed, key: key)
        } catch VaultCryptoError.authenticationFailed {
            throw VaultOpenError.authenticationFailed
        } catch {
            throw VaultOpenError.ioFailure
        }
        do {
            return Unlocked(entries: try VaultDocument.decode(plain), key: key)
        } catch {
            throw VaultOpenError.corrupt
        }
    }

    private func writeEncrypted(key: [UInt8], entries: [Entry]) throws {
        let text: String
        do {
            text = try VaultDocument.encode(entries)
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
            ciphertext = try VaultCrypto.sealWithRandomNonce(plain, key: key)
        } catch {
            throw VaultWriteError.ioFailure
        }
        do {
            try vaultFile.writeAtomic(ciphertext)
        } catch {
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
        return try Data(contentsOf: url)
    }

    public func writeAtomic(_ data: Data) throws {
        guard data.count <= InputPolicy.maxDocumentBytes else {
            throw FileByteStoreError.tooLarge
        }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                withIntermediateDirectories: true)
        try data.write(to: url, options: [.atomic])
        let promoted = try Data(contentsOf: url)
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
        if let data = try? Data(contentsOf: url),
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

    public func integer(_ key: String) -> Int? { values[key] as? Int }

    public func setInteger(_ key: String, _ value: Int) {
        values[key] = value
        persist()
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
