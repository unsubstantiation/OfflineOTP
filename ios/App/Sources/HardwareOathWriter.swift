import Foundation
import YubiKit
import OfflineOTPKit

/// Explicit vault-to-key seed export only. Never reads seeds from the key or overwrites an ID.
final class HardwareOathWriter {
    private let native = HardwareNativeOperation()
    deinit { native.cancel() }
    func cancel() { native.cancel() }
    func copy(_ entry: Entry, transport: HardwareTransport, password: String, requireTouch: Bool,
              completion: @escaping (String?) -> Void) {
        guard password.utf16.count <= InputPolicy.maxTextChars,
              let names = HardwareOathCopyPolicy.names(for: entry) else {
            completion("Unsupported OATH account parameters or name."); return
        }
        native.start(transport, operation: { connection in
            try Task.checkCancellation()
            let session = try await OATHSession.makeSession(connection: connection)
            if !password.isEmpty { try await session.unlock(password: password) }
            try Task.checkCancellation()
            let credentials = try await session.listCredentials()
            let id = Data(names.identifier.utf8)
            guard credentials.count <= 1024, !credentials.contains(where: { $0.id == id }) else {
                throw CocoaError(.fileWriteFileExists)
            }
            guard var seed = entry.secretBytes, !seed.isEmpty, seed.count <= InputPolicy.maxSecretBytes else {
                throw VaultCryptoError.invalidParameters
            }
            defer { for index in seed.indices { seed[index] = 0 } }
            var secret = Data(seed)
            defer { secret.resetBytes(in: 0..<secret.count) }
            let algorithm: OATHSession.HashAlgorithm
            switch entry.algorithm {
            case .sha1: algorithm = .sha1
            case .sha256: algorithm = .sha256
            case .sha512: algorithm = .sha512
            }
            let type: OATHSession.CredentialType = entry.kind == .hotp
                ? .hotp(counter: UInt32(entry.counter)) : .totp(period: TimeInterval(entry.period))
            let template = OATHSession.CredentialTemplate(type: type, algorithm: algorithm, secret: secret,
                issuer: names.issuer, name: names.account, digits: UInt8(entry.digits), requiresTouch: requireTouch)
            guard template.identifier == names.identifier else { throw VaultCryptoError.invalidParameters }
            try Task.checkCancellation() // No retry: a lost response may already have provisioned the seed.
            _ = try await session.addCredential(template: template)
        }, completion: { result in
            switch result {
            case .success: completion(nil)
            case .failure: completion("Copy failed, timed out or was cancelled. Check OATH password, transport and account support; an existing ID is never overwritten. No retry was made. The account may already exist on the key if its response was lost; inspect the key before trying again.")
            }
        })
    }
}
