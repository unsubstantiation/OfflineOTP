import Foundation
import OfflineOTPKit

/// Foreground/session-only encrypted backups. UI ownership is main-queue confined; a Work token
/// transfers a detached document to a serial worker and cancels publication after lock/disable.
final class SessionAutomaticBackup {
    private final class Scope {
        let url: URL
        let granted: Bool
        init(_ url: URL) { self.url = url; granted = url.startAccessingSecurityScopedResource() }
        deinit { if granted { url.stopAccessingSecurityScopedResource() } }
    }
    private var scope: Scope?
    private struct Input {
        let document: String
        let password: String
        let recipients: [Data]
        let signingKey: Data?
        let signingPassphrase: String
        let vaultBytes: Data?
        var fileExtension: String { vaultBytes != nil ? "offlineotp" : recipients.isEmpty ? "aes" : "pgp" }
        let scope: Scope
    }
    private final class Work {
        private let lock = NSLock()
        private var cancelled = false
        private var input: Input?
        init(_ input: Input) { self.input = input }
        func take() -> Input? {
            lock.lock(); defer { lock.unlock() }
            guard !cancelled else { return nil }
            let value = input; input = nil; return value
        }
        func cancel() { lock.lock(); cancelled = true; input = nil; lock.unlock() }
        func active() -> Bool { lock.lock(); defer { lock.unlock() }; return !cancelled }
    }
    private static let worker = DispatchQueue(label: "OfflineOTP.automatic-backup", qos: .utility)
    private let enqueue: (@escaping () -> Void) -> Void
    private let seal: (String, String) throws -> Data
    private let schedule: (TimeInterval, @escaping () -> Void) -> (() -> Void)
    private var cancelTimer: (() -> Void)?
    private var timerEpoch = UUID()
    private var pendingChange = false
    init(enqueue: ((@escaping () -> Void) -> Void)? = nil,
         seal: ((String, String) throws -> Data)? = nil,
         schedule: ((TimeInterval, @escaping () -> Void) -> (() -> Void))? = nil) {
        self.enqueue = enqueue ?? { task in SessionAutomaticBackup.worker.async(execute: task) }
        self.seal = seal ?? { password, document in
            try EncryptedBackup.sealWithFreshParameters(password: password, plain: document)
        }
        self.schedule = schedule ?? { delay, action in
            let timer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { _ in action() }
            return { timer.invalidate() }
        }
    }
    private func stopTimer() { timerEpoch = UUID(); cancelTimer?(); cancelTimer = nil }
    private func scheduleNext(_ delay: TimeInterval) {
        stopTimer()
        let id = timerEpoch
        cancelTimer = schedule(delay) { [weak self] in
            guard let self = self, self.timerEpoch == id else { return }
            self.cancelTimer = nil; self.runNow()
        }
    }
    /// Coalesce successful saves without overlapping snapshots or extending the first-change delay.
    func vaultChanged() {
        guard enabled, !pendingChange else { return }
        pendingChange = true
        if !busy {
            scheduleNext(AutomaticBackupPolicy.changeDelay)
            message = "Vault saved. Automatic backup scheduled shortly."; changed?()
        }
    }
    private var password = ""
    private var recipientKeys: [Data] = []
    private var sealRecipients: ((String, [Data]) throws -> Data)?
    private var signingKey: Data?
    private var signingPassphrase = ""
    private var sealSigned: ((String, [Data], Data, String) throws -> Data)?
    var vaultCopy: Bool { vaultSnapshot != nil }
    private var vaultSnapshot: (() -> Data?)?
    var signed: Bool { signingKey != nil }
    var openPgp: Bool { !recipientKeys.isEmpty }
    private var directory: URL?
    private var interval: TimeInterval = 900
    private var retention = 10
    private var snapshot: (() -> String?)?
    private var changed: (() -> Void)?
    private var work: Work?
    private(set) var enabled = false
    private(set) var busy = false
    private(set) var failed = false
    func dismissNotice() { failed = false; changed?() }
    private(set) var message = "Disabled. Enable for this unlocked session."

    @discardableResult
    func configure(directory: URL, password: String, confirmation: String, minutes: Int, keep: Int,
                   snapshot: @escaping () -> String?, changed: @escaping () -> Void, beforeEnable: () throws -> Void = {},
                   recipientKeys: [Data] = [], sealRecipients: ((String, [Data]) throws -> Data)? = nil,
                   admitted: () -> Bool = { true }, signingKey: Data? = nil, signingPassphrase: String = "",
                   sealSigned: ((String, [Data], Data, String) throws -> Data)? = nil,
                   vaultSnapshot: (() -> Data?)? = nil) -> Bool {
        let valid: Bool
        if vaultSnapshot != nil {
            valid = (1...1440).contains(minutes) && (1...100).contains(keep) && password.isEmpty && confirmation.isEmpty
                && recipientKeys.isEmpty && signingKey == nil
        } else if recipientKeys.isEmpty {
            valid = AutomaticBackupPolicy.valid(minutes: minutes, retention: keep,
                passwordUTF16Units: password.utf16.count, passwordsMatch: password == confirmation)
        } else {
            valid = AutomaticBackupConfiguration.validRecipients(recipientKeys) && (1...1440).contains(minutes)
                && (1...100).contains(keep) && password.isEmpty && confirmation.isEmpty && sealRecipients != nil
        }
        let validSigning = signingKey.map { !$0.isEmpty && $0.count <= InputPolicy.maxDocumentBytes
            && signingPassphrase.utf16.count <= InputPolicy.maxTextChars && !recipientKeys.isEmpty && sealSigned != nil } ?? true
        guard !busy, admitted(), validSigning, valid else { return false }
        let scope = Scope(directory)
        do {
            let root = directory.standardizedFileURL.resolvingSymlinksInPath()
            var isDirectory: ObjCBool = false
            guard FileManager.default.fileExists(atPath: root.path, isDirectory: &isDirectory), isDirectory.boolValue else { return false }
            let managed = root.appendingPathComponent("OfflineOTP-automatic", isDirectory: true)
            if FileManager.default.fileExists(atPath: managed.path) {
                let values = try managed.resourceValues(forKeys: [.isSymbolicLinkKey, .isDirectoryKey])
                guard values.isSymbolicLink != true, values.isDirectory == true else { return false }
                if let mode = try FileManager.default.attributesOfItem(atPath: managed.path)[.posixPermissions] as? NSNumber {
                    try FileManager.default.setAttributes([.posixPermissions: mode.intValue & 0o700], ofItemAtPath: managed.path)
                }
            } else {
                try FileManager.default.createDirectory(at: managed, withIntermediateDirectories: false,
                    attributes: [.posixPermissions: 0o700, .protectionKey: FileProtectionType.complete])
            }
            if let vaultSnapshot { guard vaultSnapshot() != nil else { return false } }
            try beforeEnable()
            guard admitted() else { return false }
            disable()
            self.scope = scope
            self.directory = managed; self.password = password
            self.recipientKeys = recipientKeys; self.sealRecipients = sealRecipients
            self.signingKey = signingKey; self.signingPassphrase = signingPassphrase; self.sealSigned = sealSigned
            interval = TimeInterval(minutes * 60); retention = keep
            self.vaultSnapshot = vaultSnapshot
            self.snapshot = snapshot; self.changed = changed; enabled = true
            runNow()
            return true
        } catch { return false }
    }

    func disable() {
        stopTimer(); pendingChange = false
        work?.cancel(); work = nil
        vaultSnapshot = nil
        password = ""; signingKey = nil; signingPassphrase = ""; sealSigned = nil; recipientKeys = []; sealRecipients = nil; directory = nil; snapshot = nil; scope = nil
        enabled = false; busy = false; failed = false
        message = "Disabled. Existing backups were kept."
        let notify = changed; changed = nil; notify?()
    }

    func runNow() {
        guard enabled, !busy, let directory = directory, let scope = scope else { return }
        stopTimer(); pendingChange = false
        let bytes: Data?
        let document: String
        if let vaultSnapshot {
            guard let saved = vaultSnapshot() else { fail(); return }
            bytes = saved; document = ""
        } else {
            guard let serialized = snapshot?() else { fail(); return }
            document = serialized; bytes = nil
        }
        let keep = retention
        let task = Work(Input(document: document, password: password, recipients: recipientKeys, signingKey: signingKey, signingPassphrase: signingPassphrase, vaultBytes: bytes, scope: scope)); work = task; busy = true
        message = "Writing automatic backup…"; changed?()
        let seal = self.seal
        let sealRecipients = self.sealRecipients
        let sealSigned = self.sealSigned
        enqueue { [weak self] in
            guard let input = task.take() else { return }
            defer { withExtendedLifetime(input.scope) {} }
            guard task.active() else { return }
            var success = false
            do {
                var encrypted: Data
                if let vaultBytes = input.vaultBytes { encrypted = vaultBytes }
                else if let key = input.signingKey {
                    guard let sealSigned else { throw VaultCryptoError.invalidParameters }
                    encrypted = try sealSigned(input.document, input.recipients, key, input.signingPassphrase)
                } else if input.recipients.isEmpty { encrypted = try seal(input.password, input.document) }
                else {
                    guard let sealRecipients else { throw VaultCryptoError.invalidParameters }
                    encrypted = try sealRecipients(input.document, input.recipients)
                }
                defer { encrypted.resetBytes(in: 0..<encrypted.count) }
                guard task.active() else { return }
                var coordinationError: NSError?
                var writeError: Error?
                NSFileCoordinator(filePresenter: nil).coordinate(writingItemAt: directory, options: .forMerging,
                    error: &coordinationError) { coordinatedDirectory in
                    do {
                        guard task.active() else { throw CocoaError(.userCancelled) }
                        let values = try coordinatedDirectory.resourceValues(forKeys: [.isSymbolicLinkKey, .isDirectoryKey])
                        guard values.isSymbolicLink != true, values.isDirectory == true else { throw CocoaError(.fileWriteNoPermission) }
                        let stamp = String(format: "%016lld", Int64(Date().timeIntervalSince1970 * 1000))
                        let name = "auto-\(stamp)-\(UUID().uuidString.lowercased()).\(input.fileExtension)"
                        let target = coordinatedDirectory.appendingPathComponent(name)
                        // Exclusive creation: no overwrite of any existing backup. No plaintext file.
                        try encrypted.write(to: target, options: [.withoutOverwriting, .completeFileProtection])
                        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: target.path)
                        if task.active() { try Self.prune(coordinatedDirectory, keep: keep, fileExtension: input.fileExtension) }
                    } catch { writeError = error }
                }
                if let error = coordinationError { throw error }
                if let error = writeError { throw error }
                success = true
            } catch { /* No raw paths, passwords or crypto diagnostics in presentation. */ }
            let succeeded = success
            DispatchQueue.main.async { [weak self] in
                guard let self = self, self.work === task, task.active() else { return }
                self.work = nil; self.busy = false
                guard succeeded else { self.fail(); return }
                self.message = self.pendingChange ? "Automatic backup saved. New changes pending; another backup is scheduled shortly."
                    : "Automatic backup saved. Next periodic backup in \(Int(self.interval / 60)) minutes."
                self.scheduleNext(self.pendingChange ? AutomaticBackupPolicy.changeDelay : self.interval)
                self.changed?()
            }
        }
    }

    func reportSigningRequired() {
        let notify = changed
        disable(); failed = true
        message = "Signing credentials are session-only. Reload them to resume signed backups. No unsigned backup was written."
        notify?()
    }

    func reportRestoreFailure() {
        let notify = changed
        disable(); failed = true
        message = "Saved automatic-backup configuration could not be opened or resumed. After a vault key/password change, re-enter its password and destination, or forget the saved configuration."
        notify?()
    }
    private func fail() {
        let notify = changed
        disable()
        failed = true
        message = "Automatic backup failed and was disabled. Existing backups were kept; check the destination before re-enabling."
        notify?()
    }

    static func prune(_ directory: URL, keep: Int, fileExtension: String = "aes") throws {
        guard ["aes", "pgp", "offlineotp"].contains(fileExtension), (1...100).contains(keep), try directory.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink != true else {
            throw CocoaError(.fileWriteNoPermission)
        }
        let files = try FileManager.default.contentsOfDirectory(at: directory,
            includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        let owned = try files.filter { url in
            guard url.lastPathComponent.range(of: "^auto-[0-9]{16}-[0-9a-f-]{36}\\.\(fileExtension)$", options: .regularExpression) != nil else { return false }
            let values = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
            return values.isRegularFile == true && values.isSymbolicLink != true
        }.sorted { $0.lastPathComponent > $1.lastPathComponent }
        for url in owned.dropFirst(keep) { try FileManager.default.removeItem(at: url) }
    }

    deinit { cancelTimer?(); work?.cancel() }
}
