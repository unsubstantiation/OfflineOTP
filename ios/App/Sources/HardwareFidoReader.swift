import Foundation
import CryptoKit
import YubiKit
import OfflineOTPKit

/// Offline WebAuthn PRF through the vetted SDK; RP is a fixed local identifier, never contacted.
final class HardwareFidoReader {
    private let native = HardwareNativeOperation()
    deinit { pendingKey?.clear(); native.cancel() }
    private var pendingKey: HardwareEnrollmentKey?
    private static let rp = "offlineotp.invalid"
    func cancel() { pendingKey?.clear(); pendingKey = nil; native.cancel() }

    private static func client(_ connection: any SmartCardConnection) async throws -> WebAuthn.Client {
        let session = try await CTAP2.Session.makeSession(connection: connection)
        return WebAuthn.Client(session: session, origin: try WebAuthn.Origin("https://offlineotp.invalid"),
                               allowedExtensions: [.prf], isPublicSuffix: { $0 != Self.rp })
    }
    private static func authorization(_ pin: String) -> WebAuthn.Authorization {
        // One submitted PIN per ceremony, no retry/fallback loop. Built-in UV may be used if no PIN supplied.
        pin.isEmpty ? .uvOnly : .pin(pin)
    }
    private static func verified(_ authData: Data) -> Bool {
        let bytes = Array(authData)
        return bytes.count >= 37 && Data(bytes.prefix(32)) == Data(SHA256.hash(data: Data(rp.utf8)))
            && bytes[32] & 0x05 == 0x05 // user presence AND verification
    }
    private static func evaluate(_ client: WebAuthn.Client, credential: Data, salt: Data, pin: String) async throws -> Data {
        guard (1...1024).contains(credential.count), salt.count == 32 else { throw VaultCryptoError.invalidParameters }
        try Task.checkCancellation()
        let options = WebAuthn.Authentication.Options(challenge: Data(VaultCrypto.randomBytes(32)), rpId: rp,
            allowCredentials: [.init(id: credential)], userVerification: .required, timeout: .seconds(25),
            extensions: .init(prf: .eval(first: salt)))
        let responses = try await client.getAssertion(options, authorization: authorization(pin)).value
        guard responses.count == 1, let response = responses.first, verified(response.rawAuthenticatorData),
              VaultCrypto.constantTimeEquals(Array(credential), Array(response.credentialId)),
              var prf = response.clientExtensionResults.prf?.results.first, prf.count == 32 else {
            throw VaultCryptoError.authenticationFailed
        }
        do { try Task.checkCancellation() }
        catch { prf.resetBytes(in: 0..<prf.count); throw error }
        // Caller owns this output, wraps/opens immediately and resets its Data after use.
        return prf
    }
    func enroll(transport: HardwareTransport, pin: String, vaultKey: Data, completion: @escaping (Data?, String?) -> Void) {
        cancel()
        guard pin.utf16.count <= InputPolicy.maxTextChars, [16, 24, 32].contains(vaultKey.count) else {
            completion(nil, "Invalid PIN or vault key."); return
        }
        let material = HardwareEnrollmentKey(vaultKey)
        pendingKey = material
        native.start(transport, operation: { connection in
            let client = try await Self.client(connection)
            let options = WebAuthn.Registration.Options(challenge: Data(VaultCrypto.randomBytes(32)),
                rp: .init(id: Self.rp, name: "OfflineOTP"),
                user: .init(id: Data(VaultCrypto.randomBytes(32)), name: "OfflineOTP", displayName: "OfflineOTP"),
                residentKey: .discouraged, userVerification: .required, attestation: .none,
                pubKeyCredParams: [.es256], timeout: .seconds(25), extensions: .init(prf: .enable))
            let registered = try await client.makeCredential(options, authorization: Self.authorization(pin)).value
            guard registered.clientExtensionResults.prf?.enabled == true, Self.verified(registered.rawAuthenticatorData),
                  (1...1024).contains(registered.credentialId.count) else { throw VaultCryptoError.authenticationFailed }
            try Task.checkCancellation()
            let salt = Data(VaultCrypto.randomBytes(32))
            var prf = try await Self.evaluate(client, credential: registered.credentialId, salt: salt, pin: pin)
            defer { prf.resetBytes(in: 0..<prf.count) }
            try Task.checkCancellation()
            guard var key = material.take() else { throw CancellationError() }
            defer { key.resetBytes(in: 0..<key.count) }
            return try HardwareUnlockEnvelope.seal(credentialID: registered.credentialId, salt: salt, prf: prf, vaultKey: key)
        }, dispose: { data in data.resetBytes(in: 0..<data.count) }, completion: { [weak self] result in
            material.clear()
            if self?.pendingKey === material { self?.pendingKey = nil }
            switch result {
            case .success(let envelope): completion(envelope, nil)
            case .failure: completion(nil, "Enrollment failed, timed out or was cancelled. Check PRF/UV support and PIN. No PIN retry was made. A credential may already have been created on the key; cancellation cannot undo that.")
            }
        })
    }
    func unlock(transport: HardwareTransport, pin: String, envelope: Data, completion: @escaping (Data?, String?) -> Void) {
        cancel()
        guard pin.utf16.count <= InputPolicy.maxTextChars, let request = HardwareUnlockEnvelope.request(envelope) else {
            completion(nil, "The local hardware-unlock record is invalid. Use the vault password."); return
        }
        native.start(transport, operation: { connection in
            let client = try await Self.client(connection)
            var prf = try await Self.evaluate(client, credential: request.credentialID, salt: request.salt, pin: pin)
            defer { prf.resetBytes(in: 0..<prf.count) }
            guard let key = HardwareUnlockEnvelope.open(envelope, prf: prf) else { throw VaultCryptoError.authenticationFailed }
            return key
        }, dispose: { data in data.resetBytes(in: 0..<data.count) }, completion: { result in
            switch result {
            case .success(let key): completion(key, nil)
            case .failure: completion(nil, "Hardware unlock failed, timed out or was cancelled. Check the enrolled PRF key, PIN and touch. No automatic retry was made. Password unlock remains available.")
            }
        })
    }
}

/// Revocable one-use snapshot: a cancelled connection task cannot later retrieve the enrollment key.
/// Data/crypto copy-on-write internals still cannot guarantee physical wiping of every prior copy.
final class HardwareEnrollmentKey {
    private let lock = NSLock()
    private var key: Data
    init(_ key: Data) { self.key = key }
    func take() -> Data? {
        lock.lock(); defer { lock.unlock() }
        guard !key.isEmpty else { return nil }
        let value = key
        key.resetBytes(in: 0..<key.count); key = Data()
        return value
    }
    func clear() {
        lock.lock(); defer { lock.unlock() }
        key.resetBytes(in: 0..<key.count); key = Data()
    }
    deinit { key.resetBytes(in: 0..<key.count) }
}
