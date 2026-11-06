import Foundation

/// Password-encrypted backups, byte-compatible with `EncryptedBackupCodec` and
/// `EncryptedBackupHeader` in `:core`.
///
/// Current writer layout (Argon2id v1.3 + AES-256-GCM):
/// `"OOTP" ‖ 0x01 ‖ 4-byte BE memoryKiB ‖ 4-byte BE iterations ‖ 1-byte parallelism ‖ 16-byte salt
///  ‖ 12-byte nonce ‖ ciphertext ‖ 16-byte tag`.
///
/// Readers also accept the legacy PBKDF2-HMAC-SHA1 layout (`4-byte BE iterations ‖ 12-byte salt ‖
/// 12-byte nonce ‖ ciphertext ‖ 16-byte tag`) so existing backups continue to restore cleanly.
public enum EncryptedBackup {
    public static let magic: [UInt8] = [0x4F, 0x4F, 0x54, 0x50] // "OOTP"
    public static let formatVersionArgon2id: UInt8 = 1

    public static let iterationBytes = 4
    public static let saltBytes = 12
    public static let argon2SaltBytes = 16
    public static let argon2HeaderBytes = 4 + 1 + 4 + 4 + 1 + argon2SaltBytes // 30 bytes
    public static let nonceBytes = VaultCrypto.nonceBytes
    public static let tagBytes = VaultCrypto.tagBytes
    public static let minBytes = iterationBytes + saltBytes + nonceBytes + tagBytes
    public static let minArgon2Bytes = argon2HeaderBytes + nonceBytes + tagBytes
    /// Legacy PBKDF2 reading ceiling: a header asking for more work than this is refused first.
    public static let maxReadIterations = 1_000_000

    public enum Kdf: Equatable {
        case argon2id
        case pbkdf2
    }

    public struct Header: Equatable {
        public let kdf: Kdf
        public let iterations: Int
        public let memoryKiB: Int
        public let parallelism: Int
        public let salt: [UInt8]
        public let encrypted: Data
    }

    /// Validates the framing of an untrusted blob before any key derivation happens.
    public static func parse(_ data: Data) throws -> Header {
        guard data.count >= minBytes else { throw EncryptedBackupError.truncated }
        guard data.count <= InputPolicy.maxDocumentBytes else { throw EncryptedBackupError.tooLarge }
        let bytes = [UInt8](data)

        if bytes[0] == magic[0], bytes[1] == magic[1], bytes[2] == magic[2], bytes[3] == magic[3] {
            guard data.count >= minArgon2Bytes else { throw EncryptedBackupError.truncated }
            let version = bytes[4]
            guard version == formatVersionArgon2id else {
                throw EncryptedBackupError.unsupportedVersion
            }
            let memoryKiB = Int(Int32(bitPattern:
                (UInt32(bytes[5]) << 24) | (UInt32(bytes[6]) << 16)
                    | (UInt32(bytes[7]) << 8) | UInt32(bytes[8])))
            let iterations = Int(Int32(bitPattern:
                (UInt32(bytes[9]) << 24) | (UInt32(bytes[10]) << 16)
                    | (UInt32(bytes[11]) << 8) | UInt32(bytes[12])))
            let parallelism = Int(bytes[13])
            let salt = Array(bytes[14..<(14 + argon2SaltBytes)])
            do {
                try Argon2id.validateParameters(
                    saltCount: salt.count,
                    memoryKiB: memoryKiB,
                    iterations: iterations,
                    parallelism: parallelism,
                    tagLength: VaultCrypto.derivedKeyBytes
                )
            } catch {
                throw EncryptedBackupError.invalidParameters
            }
            let encrypted = Data(bytes[argon2HeaderBytes...])
            return Header(
                kdf: .argon2id,
                iterations: iterations,
                memoryKiB: memoryKiB,
                parallelism: parallelism,
                salt: salt,
                encrypted: encrypted
            )
        }

        let iterations = Int(Int32(bitPattern:
            (UInt32(bytes[0]) << 24) | (UInt32(bytes[1]) << 16)
                | (UInt32(bytes[2]) << 8) | UInt32(bytes[3])))
        guard iterations >= 1, iterations <= maxReadIterations else {
            throw EncryptedBackupError.unsupportedIterations
        }
        let salt = Array(bytes[iterationBytes..<(iterationBytes + saltBytes)])
        let encrypted = Data(bytes[(iterationBytes + saltBytes)...])
        return Header(
            kdf: .pbkdf2,
            iterations: iterations,
            memoryKiB: 0,
            parallelism: 0,
            salt: salt,
            encrypted: encrypted
        )
    }

    /// Encrypts `plain` under Argon2id v1.3 with explicit parameters, salt, and nonce (used both by
    /// `sealWithFreshParameters` and by byte-identical golden fixture verification).
    public static func sealArgon2id(
        password: String,
        plain: String,
        memoryKiB: Int = VaultCrypto.argon2MemoryKiB,
        iterations: Int = VaultCrypto.argon2Iterations,
        parallelism: Int = VaultCrypto.argon2Parallelism,
        salt: [UInt8],
        nonce: [UInt8]
    ) throws -> Data {
        guard password.count <= InputPolicy.maxVaultPasswordChars, !password.isEmpty else {
            throw EncryptedBackupError.invalidPassword
        }
        guard salt.count == argon2SaltBytes else {
            throw EncryptedBackupError.invalidParameters
        }
        do {
            try Argon2id.validateParameters(
                saltCount: salt.count,
                memoryKiB: memoryKiB,
                iterations: iterations,
                parallelism: parallelism,
                tagLength: VaultCrypto.derivedKeyBytes
            )
        } catch {
            throw EncryptedBackupError.invalidParameters
        }

        var key = try VaultCrypto.argon2id(
            password: password,
            salt: salt,
            memoryKiB: memoryKiB,
            iterations: iterations,
            parallelism: parallelism
        )
        defer {
            for i in 0..<key.count { key[i] = 0 }
        }
        let encrypted = try VaultCrypto.seal(Data(plain.utf8), key: key, nonce: nonce)

        var out = Data()
        out.reserveCapacity(argon2HeaderBytes + encrypted.count)
        out.append(contentsOf: magic)
        out.append(formatVersionArgon2id)
        appendBigEndianInt32(memoryKiB, to: &out)
        appendBigEndianInt32(iterations, to: &out)
        out.append(UInt8(parallelism & 0xFF))
        out.append(contentsOf: salt)
        out.append(encrypted)
        return out
    }

    /// Legacy PBKDF2 deterministic sealer retained so the legacy golden fixture test can verify
    /// byte-for-byte framing compatibility.
    public static func seal(password: String, plain: String, iterations: Int, salt: [UInt8],
                            nonce: [UInt8]) throws -> Data {
        guard password.count <= InputPolicy.maxVaultPasswordChars, !password.isEmpty else {
            throw EncryptedBackupError.invalidPassword
        }
        guard iterations >= 1, iterations <= VaultCrypto.maxIterations, salt.count == saltBytes else {
            throw EncryptedBackupError.invalidParameters
        }
        let key = try VaultCrypto.pbkdf2(password: password, salt: salt, iterations: iterations)
        let encrypted = try VaultCrypto.seal(Data(plain.utf8), key: key, nonce: nonce)
        var out = Data()
        appendBigEndianInt32(iterations, to: &out)
        out.append(contentsOf: salt)
        out.append(encrypted)
        return out
    }

    /// Encrypts with Argon2id (`64 MiB, t=3, p=4`), a fresh 16-byte salt, and a fresh 12-byte GCM nonce.
    public static func sealWithFreshParameters(password: String, plain: String) throws -> Data {
        try sealArgon2id(
            password: password,
            plain: plain,
            memoryKiB: VaultCrypto.argon2MemoryKiB,
            iterations: VaultCrypto.argon2Iterations,
            parallelism: VaultCrypto.argon2Parallelism,
            salt: VaultCrypto.randomBytes(argon2SaltBytes),
            nonce: VaultCrypto.randomBytes(nonceBytes)
        )
    }

    /// Decrypts an Argon2id or legacy PBKDF2 backup (or a LinSCP `LSCPVLT2` vault), or fails.
    public static func open(password: String, data: Data) throws -> String {
        if LinScpVault.isEncryptedVault(data) {
            return try LinScpVault.openToDocument(password: password, data: data)
        }
        guard password.count <= InputPolicy.maxVaultPasswordChars, !password.isEmpty else {
            throw EncryptedBackupError.invalidPassword
        }
        let header = try parse(data)
        var key: [UInt8]
        switch header.kdf {
        case .argon2id:
            key = try VaultCrypto.argon2id(
                password: password,
                salt: header.salt,
                memoryKiB: header.memoryKiB,
                iterations: header.iterations,
                parallelism: header.parallelism
            )
        case .pbkdf2:
            key = try VaultCrypto.pbkdf2(password: password, salt: header.salt,
                                         iterations: header.iterations)
        }
        defer {
            for i in 0..<key.count { key[i] = 0 }
        }
        let plain = try VaultCrypto.open(header.encrypted, key: key)
        guard let text = String(data: plain, encoding: .utf8) else {
            throw EncryptedBackupError.invalidUTF8
        }
        return text
    }

    /// Opens a backup and decodes its entries, so a restore cannot half-succeed.
    public static func openEntries(password: String, data: Data) throws -> [Entry] {
        try VaultDocument.decode(try open(password: password, data: data))
    }

    private static func appendBigEndianInt32(_ value: Int, to out: inout Data) {
        out.append(UInt8(truncatingIfNeeded: value >> 24))
        out.append(UInt8(truncatingIfNeeded: value >> 16))
        out.append(UInt8(truncatingIfNeeded: value >> 8))
        out.append(UInt8(truncatingIfNeeded: value))
    }
}

public enum EncryptedBackupError: Error, Equatable {
    case truncated
    case tooLarge
    case unsupportedVersion
    case unsupportedIterations
    case invalidPassword
    case invalidParameters
    case invalidUTF8
}
