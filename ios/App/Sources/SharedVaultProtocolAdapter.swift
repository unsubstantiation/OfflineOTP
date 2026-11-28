import Foundation
import OfflineOTPKit
import OfflineOTPPresentation

/// Only bounded public header prefixes cross this bridge. Keys, passwords, plaintext and crypto
/// operations stay in the vetted native providers. Shared rejection never invokes the oracle.
struct SharedVaultProtocolAdapter: VaultProtocolAdapter {
    private let policy = VaultProtocol()
    private func bytes(_ data: Data) -> KotlinByteArray {
        let out = KotlinByteArray(size: Int32(data.count))
        for (i, value) in data.enumerated() { out.set(index: Int32(i), value: Int8(bitPattern: value)) }
        return out
    }
    private func data(_ bytes: KotlinByteArray) -> Data {
        Data((0..<Int(bytes.size)).map { UInt8(bitPattern: bytes.get(index: Int32($0))) })
    }
    private func count(_ data: Data) throws -> Int32 {
        guard data.count <= InputPolicy.maxDocumentBytes else { throw EncryptedBackupError.tooLarge }
        return Int32(data.count)
    }
    func vaultHeader(_ source: Data) throws -> Data {
        guard let header = policy.vaultHeader(prefix: bytes(Data(source.prefix(40))), totalBytes: try count(source)).header else {
            throw VaultOpenError.corrupt
        }
        return data(header.bytes())
    }
    func makeVaultHeader(kdf: String, salt: [UInt8], memoryKiB: Int, iterations: Int, parallelism: Int) throws -> Data {
        guard let memory = Int32(exactly: memoryKiB), let rounds = Int32(exactly: iterations), let lanes = Int32(exactly: parallelism),
              let header = policy.makeVaultHeader(kdf: kdf, salt: bytes(Data(salt)), memoryKiB: memory,
                iterations: rounds, parallelism: lanes).header else { throw VaultOpenError.corrupt }
        return data(header.bytes())
    }
    func backupHeader(_ source: Data) throws -> EncryptedBackup.Header {
        guard let header = policy.backupHeader(prefix: bytes(Data(source.prefix(30))), totalBytes: try count(source)).header else {
            throw EncryptedBackupError.invalidParameters
        }
        return EncryptedBackup.Header(kdf: header.kdf == .argon2id ? .argon2id : .pbkdf2,
            iterations: Int(header.iterations), memoryKiB: Int(header.memoryKiB), parallelism: Int(header.parallelism),
            salt: Array(data(header.salt())), encrypted: Data(source.dropFirst(Int(header.encryptedOffset))))
    }
    func makeBackupHeader(salt: [UInt8], memoryKiB: Int, iterations: Int, parallelism: Int) throws -> Data {
        guard let memory = Int32(exactly: memoryKiB), let rounds = Int32(exactly: iterations), let lanes = Int32(exactly: parallelism),
              let header = policy.makeBackupHeader(salt: bytes(Data(salt)), memoryKiB: memory,
                iterations: rounds, parallelism: lanes).header else { throw EncryptedBackupError.invalidParameters }
        return data(header.bytes())
    }
    func route(_ source: Data, format: String, variant: String) throws -> String {
        guard let route = policy.select(prefix: bytes(Data(source.prefix(40))), totalBytes: try count(source), format: format, variant: variant).route else {
            throw EncryptedBackupError.invalidParameters
        }
        switch route {
        case .vaultCopy: return "vault-copy"
        case .offlineotp: return "offlineotp"
        case .andotpPbkdf2: return "andotp-pbkdf2"
        case .andotpPre063: return "pre063"
        case .plain: return "plain"
        case .linscp: return "linscp"
        default: throw EncryptedBackupError.invalidParameters
        }
    }
}
