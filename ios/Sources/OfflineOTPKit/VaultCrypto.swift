import Foundation
import CryptoKit
import CommonCrypto

/// The cryptographic primitives the vault and the encrypted backup use.
///
/// Everything here has to produce the same bytes as
/// `core/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/CryptoPrimitives.kt`:
///
///  - PBKDF2-HMAC-SHA1, 256-bit output. The vault splits it in half (first 16 bytes = AES key, last
///    16 bytes = stored verifier); the backup uses all 32 bytes as the key.
///  - AES-GCM with a 96-bit nonce and a 128-bit tag, written as `nonce ‖ ciphertext ‖ tag`.
///
/// The golden fixtures in `desktop/src/test/resources/golden/` are what prove it; nothing here is
/// meant to be taken on trust.
public enum VaultCrypto {
    /// PBKDF2 output size in bytes (256 bits), then halved by `credentials`.
    public static let derivedKeyBytes = 32
    public static let halfBytes = 16
    public static let saltBytes = 16
    public static let nonceBytes = 12
    public static let tagBytes = 16
    public static let minAuthIterations = 30000
    public static let minBackupIterations = 140000
    public static let maxBackupIterations = 160000
    public static let maxIterations = 20_000_000

    /// The two halves of the derived material: the AES key and the value stored as the verifier.
    public struct Credentials: Equatable {
        public let key: [UInt8]
        public let verifier: [UInt8]
    }

    /// Raw PBKDF2-HMAC-SHA1 output.
    public static func pbkdf2(password: String, salt: [UInt8], iterations: Int) throws -> [UInt8] {
        guard !password.isEmpty, password.count <= InputPolicy.maxVaultPasswordChars,
              salt.count >= 8, iterations >= 1, iterations <= maxIterations else {
            throw VaultCryptoError.invalidParameters
        }
        let passwordBytes = Array(password.utf8)
        var derived = [UInt8](repeating: 0, count: derivedKeyBytes)
        let status = derived.withUnsafeMutableBufferPointer { derivedBuffer -> Int32 in
            passwordBytes.withUnsafeBufferPointer { passwordBuffer -> Int32 in
                salt.withUnsafeBufferPointer { saltBuffer -> Int32 in
                    // `CCKeyDerivationPBKDF` takes the password as Int8; the bytes are the UTF-8
                    // encoding Java's `PBEKeySpec` uses, so both produce the same key material.
                    let passwordPointer = passwordBuffer.baseAddress
                        .map { UnsafeRawPointer($0).assumingMemoryBound(to: Int8.self) }
                    return CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        passwordPointer,
                        passwordBytes.count,
                        saltBuffer.baseAddress,
                        salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA1),
                        UInt32(iterations),
                        derivedBuffer.baseAddress,
                        derivedBuffer.count)
                }
            }
        }
        guard status == kCCSuccess else { throw VaultCryptoError.derivationFailed }
        return derived
    }

    /// Splits PBKDF2 output the way both existing clients do: lower half is the AES key, upper half
    /// is the verifier the settings file stores.
    public static func credentials(password: String, salt: [UInt8],
                                   iterations: Int) throws -> Credentials {
        let derived = try pbkdf2(password: password, salt: salt, iterations: iterations)
        return Credentials(key: Array(derived[0..<halfBytes]),
                           verifier: Array(derived[halfBytes..<derivedKeyBytes]))
    }

    /// AES-GCM seal with an explicit nonce, returned as `nonce ‖ ciphertext ‖ tag`.
    public static func seal(_ plaintext: Data, key: [UInt8], nonce: [UInt8]) throws -> Data {
        guard key.count == halfBytes || key.count == 24 || key.count == derivedKeyBytes else {
            throw VaultCryptoError.invalidKey
        }
        guard nonce.count == nonceBytes else { throw VaultCryptoError.invalidNonce }
        let symmetricKey = SymmetricKey(data: Data(key))
        let gcmNonce: AES.GCM.Nonce
        do {
            gcmNonce = try AES.GCM.Nonce(data: Data(nonce))
        } catch {
            throw VaultCryptoError.invalidNonce
        }
        do {
            let sealed = try AES.GCM.seal(plaintext, using: symmetricKey, nonce: gcmNonce)
            return Data(nonce) + sealed.ciphertext + sealed.tag
        } catch {
            throw VaultCryptoError.authenticationFailed
        }
    }

    /// AES-GCM seal with a fresh random nonce, the form both existing clients write.
    public static func sealWithRandomNonce(_ plaintext: Data, key: [UInt8]) throws -> Data {
        try seal(plaintext, key: key, nonce: randomBytes(nonceBytes))
    }

    /// AES-GCM open of `nonce ‖ ciphertext ‖ tag`. A wrong key, a tampered byte or a truncated
    /// blob all fail the same way: the tag is the only gate.
    public static func open(_ sealed: Data, key: [UInt8]) throws -> Data {
        guard key.count == halfBytes || key.count == 24 || key.count == derivedKeyBytes else {
            throw VaultCryptoError.invalidKey
        }
        guard sealed.count > nonceBytes + tagBytes else { throw VaultCryptoError.authenticationFailed }
        let noncePart = Data(sealed.prefix(nonceBytes))
        let body = sealed.dropFirst(nonceBytes)
        let ciphertext = Data(body.dropLast(tagBytes))
        let tag = Data(body.suffix(tagBytes))
        let symmetricKey = SymmetricKey(data: Data(key))
        do {
            let nonce = try AES.GCM.Nonce(data: noncePart)
            let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
            return try AES.GCM.open(box, using: symmetricKey)
        } catch {
            throw VaultCryptoError.authenticationFailed
        }
    }

    /// Base64, URL-safe alphabet with padding, as Java's `Base64.getUrlEncoder()` writes it.
    public static func base64URLEncode(_ bytes: [UInt8]) -> String {
        Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
    }

    public static func base64URLDecode(_ text: String) -> [UInt8]? {
        guard !text.isEmpty else { return [] }
        var normalized = text.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while normalized.count % 4 != 0 { normalized += "=" }
        guard let data = Data(base64Encoded: normalized) else { return nil }
        return [UInt8](data)
    }

    /// Comparison that does not stop at the first differing byte, matching `MessageDigest.isEqual`.
    public static func constantTimeEquals(_ left: [UInt8], _ right: [UInt8]) -> Bool {
        guard left.count == right.count else { return false }
        var difference: UInt8 = 0
        for index in 0..<left.count {
            difference |= left[index] ^ right[index]
        }
        return difference == 0
    }

    /// A cryptographically secure random byte string (`SecureRandom` on the other platforms).
    public static func randomBytes(_ count: Int) -> [UInt8] {
        guard count > 0 else { return [] }
        var generator = SystemRandomNumberGenerator()
        return (0..<count).map { _ in UInt8.random(in: UInt8.min...UInt8.max, using: &generator) }
    }
}

public enum VaultCryptoError: Error, Equatable {
    case invalidParameters
    case derivationFailed
    case invalidKey
    case invalidNonce
    case authenticationFailed
}
