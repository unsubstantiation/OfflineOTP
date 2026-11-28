import Foundation

/// Framing/policy only. Production uses the Kotlin adapter supplied by the app. Standalone SwiftPM
/// keeps an independent compatibility oracle; there is no runtime fallback after a shared failure.
public protocol VaultProtocolAdapter {
    func vaultHeader(_ data: Data) throws -> Data
    func makeVaultHeader(kdf: String, salt: [UInt8], memoryKiB: Int, iterations: Int, parallelism: Int) throws -> Data
    func backupHeader(_ data: Data) throws -> EncryptedBackup.Header
    func makeBackupHeader(salt: [UInt8], memoryKiB: Int, iterations: Int, parallelism: Int) throws -> Data
    func route(_ data: Data, format: String, variant: String) throws -> String
}

public struct NativeVaultProtocolAdapter: VaultProtocolAdapter {
    public init() { }
    public func vaultHeader(_ data: Data) throws -> Data { try VaultContainer.nativeHeader(data) }
    public func makeVaultHeader(kdf: String, salt: [UInt8], memoryKiB: Int, iterations: Int, parallelism: Int) throws -> Data {
        guard salt.count == 16, kdf == VaultCrypto.kdfArgon2id || kdf == VaultCrypto.kdfPbkdf2 else { throw VaultOpenError.corrupt }
        let argon = kdf == VaultCrypto.kdfArgon2id
        var out = Data([79, 84, 80, 86, 76, 84, 13, 10, 1, 1, argon ? 1 : 2, 0])
        for value in [argon ? memoryKiB : 0, iterations, argon ? parallelism : 0] {
            guard value >= 0, value <= Int(Int32.max) else { throw VaultOpenError.corrupt }
            out.append(contentsOf: [UInt8(truncatingIfNeeded: value >> 24), UInt8(truncatingIfNeeded: value >> 16),
                                   UInt8(truncatingIfNeeded: value >> 8), UInt8(truncatingIfNeeded: value)])
        }
        out.append(contentsOf: salt)
        return try VaultContainer.nativeCheckedHeader(out)
    }
    public func backupHeader(_ data: Data) throws -> EncryptedBackup.Header { try EncryptedBackup.nativeParse(data) }
    public func makeBackupHeader(salt: [UInt8], memoryKiB: Int, iterations: Int, parallelism: Int) throws -> Data {
        guard salt.count == 16 else { throw EncryptedBackupError.invalidParameters }
        try Argon2id.validateParameters(saltCount: salt.count, memoryKiB: memoryKiB, iterations: iterations, parallelism: parallelism, tagLength: 32)
        var out = Data(EncryptedBackup.magic + [1])
        for value in [memoryKiB, iterations] {
            out.append(contentsOf: [UInt8(truncatingIfNeeded: value >> 24), UInt8(truncatingIfNeeded: value >> 16),
                                   UInt8(truncatingIfNeeded: value >> 8), UInt8(truncatingIfNeeded: value)])
        }
        out.append(UInt8(parallelism)); out.append(contentsOf: salt)
        return out
    }
    public func route(_ data: Data, format: String, variant: String) throws -> String {
        guard !data.isEmpty, data.count <= InputPolicy.maxDocumentBytes else { throw EncryptedBackupError.tooLarge }
        let vault = VaultContainer.isContainer(data), linscp = LinScpVault.isEncryptedVault(data)
        if format == "auto" {
            if vault { return "vault-copy" }; if linscp { return "linscp" }
            _ = try backupHeader(data); return "offlineotp"
        }
        if format == "vault-copy" {
            guard vault else { throw EncryptedBackupError.invalidParameters }; return "vault-copy"
        }
        guard !vault, !linscp else { throw EncryptedBackupError.invalidParameters }
        if format == "offlineotp" || (format == "andotp" && variant == "encrypted") {
            let header = try backupHeader(data)
            if format == "offlineotp" { return "offlineotp" }
            guard header.kdf == .pbkdf2 else { throw EncryptedBackupError.invalidParameters }; return "andotp-pbkdf2"
        }
        if format == "andotp" {
            if variant == "plain" { return "plain" }
            if variant == "pre063", data.count > 28, !data.starts(with: EncryptedBackup.magic) { return "pre063" }
        }
        throw EncryptedBackupError.invalidParameters
    }
}
