import Foundation
import CryptoKit

/// Local vault-key-bound credential record. Not a preferences/export format or PRF fallback.
public enum AutomaticBackupCredentialCodec {
    public static let maxPlaintextBytes = 65536
    private static let magic = Data([0x41, 0x42, 0x50, 1])
    private static let domain = Data("OfflineOTP automatic backup credentials v1".utf8)
    public static func seal(key: [UInt8], plaintext: Data) throws -> Data {
        try seal(key: key, plaintext: plaintext, nonce: VaultCrypto.randomBytes(12))
    }
    static func seal(key: [UInt8], plaintext: Data, nonce: [UInt8]) throws -> Data {
        guard [16, 24, 32].contains(key.count), (1...maxPlaintextBytes).contains(plaintext.count), nonce.count == 12 else {
            throw VaultCryptoError.invalidParameters
        }
        var derived = Array(HMAC<SHA256>.authenticationCode(for: domain, using: SymmetricKey(data: key)))
        defer { for index in derived.indices { derived[index] = 0 } }
        let header = magic + Data(nonce)
        return header + (try VaultCrypto.seal(plaintext, key: derived, nonce: nonce, authenticating: header)).dropFirst(12)
    }
    public static func open(key: [UInt8], encrypted: Data) throws -> Data {
        guard [16, 24, 32].contains(key.count), (33...(maxPlaintextBytes + 32)).contains(encrypted.count), encrypted.prefix(4) == magic else {
            throw VaultCryptoError.invalidParameters
        }
        var derived = Array(HMAC<SHA256>.authenticationCode(for: domain, using: SymmetricKey(data: key)))
        defer { for index in derived.indices { derived[index] = 0 } }
        let header = Data(encrypted.prefix(16))
        return try VaultCrypto.open(Data(encrypted.dropFirst(4)), key: derived, authenticating: header)
    }
}
