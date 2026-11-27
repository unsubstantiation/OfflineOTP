import Foundation
import CryptoKit

/// Wire-compatible authenticated wrapper for the Android/desktop FIDO PRF unlock record.
/// This is format support only: callers MUST obtain PRF from a real verified hardware operation.
/// It does not turn OATH codes, FIDO signatures, device IDs or passwords into hardware PRF output.
public enum HardwareUnlockEnvelope {
    private static let magic: [UInt8] = [0x4f, 0x54, 0x50, 0x01]
    private static let domain = Data("OfflineOTP hardware unlock key v1".utf8)
    public struct Request: CustomStringConvertible {
        public let credentialID: Data
        public let salt: Data
        public var description: String { "HardwareUnlockRequest(redacted)" }
    }
    private struct Parsed {
        let request: Request
        let headerLength: Int
        let nonce: [UInt8]
    }
    public static func request(_ envelope: Data) -> Request? { parse(envelope)?.request }

    public static func seal(credentialID: Data, salt: Data, prf: Data, vaultKey: Data) throws -> Data {
        try seal(credentialID: credentialID, salt: salt, prf: prf, vaultKey: vaultKey, nonce: VaultCrypto.randomBytes(12))
    }

    // Explicit nonce is internal for byte-identical golden tests, never a production UI option.
    static func seal(credentialID: Data, salt: Data, prf: Data, vaultKey: Data, nonce: [UInt8]) throws -> Data {
        guard (1...1024).contains(credentialID.count), salt.count == 32, prf.count == 32,
              [16, 24, 32].contains(vaultKey.count), nonce.count == 12 else { throw VaultCryptoError.invalidParameters }
        var header = Data(magic)
        header.append(UInt8(credentialID.count >> 8)); header.append(UInt8(credentialID.count & 255))
        header.append(credentialID); header.append(salt); header.append(contentsOf: nonce)
        var wrappingKey = Array(HMAC<SHA256>.authenticationCode(for: domain, using: SymmetricKey(data: prf)))
        defer { for index in wrappingKey.indices { wrappingKey[index] = 0 } }
        let sealed = try VaultCrypto.seal(vaultKey, key: wrappingKey, nonce: nonce, authenticating: header)
        return header + sealed.dropFirst(12)
    }

    public static func open(_ envelope: Data, prf: Data) -> Data? {
        guard prf.count == 32, let parsed = parse(envelope) else { return nil }
        var wrappingKey = Array(HMAC<SHA256>.authenticationCode(for: domain, using: SymmetricKey(data: prf)))
        defer { for index in wrappingKey.indices { wrappingKey[index] = 0 } }
        let header = Data(envelope.prefix(parsed.headerLength))
        let sealed = Data(parsed.nonce) + envelope.dropFirst(parsed.headerLength)
        guard let key = try? VaultCrypto.open(sealed, key: wrappingKey, authenticating: header), [16, 24, 32].contains(key.count) else { return nil }
        return key
    }

    private static func parse(_ envelope: Data) -> Parsed? {
        // Bounds precede indexing/allocation and exactly match HardwareUnlockEnvelope in :core.
        guard (83...1122).contains(envelope.count) else { return nil }
        let bytes = Array(envelope)
        guard Array(bytes.prefix(4)) == magic else { return nil }
        let length = Int(bytes[4]) * 256 + Int(bytes[5])
        guard (1...1024).contains(length) else { return nil }
        let headerLength = 6 + length + 32 + 12
        guard [32, 40, 48].contains(bytes.count - headerLength) else { return nil }
        let id = Data(bytes[6..<(6 + length)])
        let salt = Data(bytes[(6 + length)..<(6 + length + 32)])
        let nonce = Array(bytes[(headerLength - 12)..<headerLength])
        return Parsed(request: Request(credentialID: id, salt: salt), headerLength: headerLength, nonce: nonce)
    }
}
