import Foundation
import YubiKit
import OfflineOTPKit

/// Native OATH only. No seed export/import, password changes, reset, or FIDO/PIN substitution.
final class HardwareOathReader {
    typealias Transport = HardwareTransport
    struct Account {
        fileprivate let credential: OATHSession.Credential
        var canRename: Bool {
            Data(HardwareOathRenamePolicy.sdkIdentifier(counterBased: counterBased, period: period,
                issuer: credential.issuer, account: credential.name).utf8) == credential.id
        }
        func permitsRename(issuer: String, account: String) -> Bool {
            canRename && HardwareOathRenamePolicy.names(counterBased: counterBased, period: period,
                issuer: issuer, account: account) != nil
        }
        var counterBased: Bool { credential.type.period == nil }
        fileprivate var period: UInt { UInt(credential.type.period ?? 0) }
        fileprivate var identity: HardwareOathPolicy.Identity {
            .init(deviceID: credential.deviceId, label: credential.id.base64EncodedString(), account: credential.name,
                  issuer: credential.issuer, type: UInt(credential.type.code), period: period)
        }
        var label: String {
            String([credential.issuer, credential.name].compactMap { $0 }.filter { !$0.isEmpty }
                .joined(separator: " — ").filter { !$0.isNewline && !$0.isUnsafeHardwareLabelCharacter }.prefix(512))
        }
    }
    struct Rename {
        let issuer: String
        let account: String
    }
    struct Result {
        let accounts: [Account]
        let code: String?
        let expires: Date?
    }
    private let native = HardwareNativeOperation()
    deinit { native.cancel() }
    func cancel() { native.cancel() }
    func start(_ transport: Transport, password: String = "", account: Account? = nil,
               allowCounterAdvance: Bool = false, deleteConfirmed: Bool = false, rename: Rename? = nil, completion: @escaping (Result?, String?) -> Void) {
        guard password.utf16.count <= InputPolicy.maxTextChars else { completion(nil, "OATH password is too long."); return }
        guard !deleteConfirmed || account != nil else { completion(nil, "Select an account before deletion."); return }
        guard rename == nil || (!deleteConfirmed && account != nil) else { completion(nil, "Select one account and one operation."); return }
        if let rename, account?.permitsRename(issuer: rename.issuer, account: rename.account) != true {
            completion(nil, "Unsupported account name or noncanonical old hardware ID. Read the key again. No command was sent."); return
        }
        guard rename != nil || deleteConfirmed || (account.map({ HardwareOathPolicy.permitsCalculation(period: $0.period, counterConsent: allowCounterAdvance) }) ?? true) else {
            completion(nil, "Calculating HOTP advances the key counter. Explicit consent is required."); return
        }
        native.start(transport, operation: { connection in
            let session = try await OATHSession.makeSession(connection: connection)
            let deviceID = await session.deviceId
            guard !deviceID.isEmpty, deviceID.utf8.count <= 4096,
                  account == nil || account?.credential.deviceId == deviceID else { throw CocoaError(.coderInvalidValue) }
            try Task.checkCancellation()
            if !password.isEmpty { try await session.unlock(password: password) }
            try Task.checkCancellation()
            let credentials = try await session.listCredentials()
            guard credentials.count <= 1024, credentials.allSatisfy({ credential in
                guard let period = credential.type.period else { return true }
                return period.isFinite && period >= 1 && period <= 86400 && period.rounded() == period
            }) else { throw CocoaError(.coderInvalidValue) }
            let accounts = credentials.map { Account(credential: $0) }
            guard let selected = account else { return Result(accounts: accounts, code: nil, expires: nil) }
            guard let index = HardwareOathPolicy.uniqueMatch(selected.identity, candidates: accounts.map({ $0.identity })) else {
                throw CocoaError(.coderInvalidValue)
            }
            if let rename {
                guard selected.canRename,
                      let names = HardwareOathRenamePolicy.names(counterBased: selected.counterBased, period: selected.period,
                          issuer: rename.issuer, account: rename.account) else { throw CocoaError(.coderInvalidValue) }
                let newID = Data(names.identifier.utf8)
                guard newID != selected.credential.id, !credentials.contains(where: { $0.id == newID }) else {
                    throw CocoaError(.coderInvalidValue)
                }
                try Task.checkCancellation() // One rename only, never retry an uncertain mutation.
                try await session.renameCredential(credentials[index], newName: names.account, newIssuer: names.issuer)
                return Result(accounts: [], code: nil, expires: nil)
            }
            if deleteConfirmed {
                try Task.checkCancellation() // Last cancellation fence before irreversible command; never retry.
                try await session.deleteCredential(credentials[index])
                return Result(accounts: [], code: nil, expires: nil)
            }
            try Task.checkCancellation() // One calculation only, never retry an uncertain HOTP result.
            let requested = Date()
            let code = try await session.calculateCredentialCode(for: credentials[index], timestamp: requested)
            try Task.checkCancellation()
            guard let expires = HardwareOathPolicy.codeExpiry(otp: code.code, period: selected.period,
                requested: requested, validUntil: code.validTo, now: Date()) else { throw CocoaError(.coderInvalidValue) }
            return Result(accounts: accounts, code: code.code, expires: expires)
        }, completion: { result in
            switch result {
            case .success(let value): completion(value, nil)
            case .failure:
                completion(nil, rename != nil ? "Rename did not report success. It may already have completed or the key may not support renaming. Read the key before retrying; no automatic retry was made. Vault unchanged." : deleteConfirmed ? "Deletion did not report success. It may already have completed; read the key before retrying. No automatic retry was made. Vault unchanged." : "OATH operation failed, timed out or was cancelled. Check key identity, OATH password, touch and transport support. No automatic retry was made. If calculating HOTP, its counter may already have advanced.")
            }
        })
    }
}

private extension Character {
    var isUnsafeHardwareLabelCharacter: Bool {
        unicodeScalars.contains {
            switch $0.properties.generalCategory {
            case .control, .format, .lineSeparator, .paragraphSeparator: return true
            default: return false
            }
        }
    }
}
