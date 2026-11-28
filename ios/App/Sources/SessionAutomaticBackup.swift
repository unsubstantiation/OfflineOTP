import Foundation
import OfflineOTPKit
import OfflineOTPPresentation

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
        private let delivery = InterchangeWorkflow(generation: 0)
        init(_ input: Input) { self.input = input; _ = delivery.preview(count: 1) }
        func take() -> Input? {
            lock.lock(); defer { lock.unlock() }
            guard !cancelled, delivery.consume(currentGeneration: 0, sessionReady: true) else { return nil }
            let value = input; input = nil; return value
        }
        func cancel() { lock.lock(); cancelled = true; delivery.cancel(); input = nil; lock.unlock() }
        func active() -> Bool { lock.lock(); defer { lock.unlock() }; return !cancelled && delivery.current(currentGeneration: 0, sessionReady: true) }
    }
    private static let worker = DispatchQueue(label: "OfflineOTP.automatic-backup", qos: .utility)
    private let enqueue: (@escaping () -> Void) -> Void
    private let seal: (String, String) throws -> Data
    private let schedule: (TimeInterval, @escaping () -> Void) -> (() -> Void)
    private var cancelTimer: (() -> Void)?
    private var timerEpoch = UUID()
    private let automation = AutomaticBackupSchedule()
    private var pendingChange: Bool { automation.pendingChange }
    private var generation: () -> Int = { 0 }
    private var admitted: () -> Bool = { false }
    init(enqueue: ((@escaping () -> Void) -> Void)? = nil,
         seal: ((String, String) throws -> Data)? = nil,
         schedule: ((TimeInterval, @escaping () -> Void) -> (() -> Void))? = nil) {
        self.enqueue = enqueue ?? { task in SessionAutomaticBackup.worker.async(execute: task) }
        self.seal = seal ?? { password, document in
            try EncryptedBackup.sealWithFreshParameters(password: password, plain: document, protocolPolicy: SharedVaultProtocolAdapter())
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
        guard enabled else { return }
        let delay = automation.changed(currentGeneration: Int64(generation()), ready: admitted())
        guard automation.enabled else { disable(); return }
        if delay >= 0 {
            scheduleNext(TimeInterval(delay) / 1000)
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
                   admitted: @escaping () -> Bool = { true }, generation: @escaping () -> Int = { 0 }, signingKey: Data? = nil, signingPassphrase: String = "",
                   sealSigned: ((String, [Data], Data, String) throws -> Data)? = nil,
                   vaultSnapshot: (() -> Data?)? = nil) -> Bool {
        guard let boundedMinutes = Int32(exactly: minutes), let boundedKeep = Int32(exactly: keep),
              BackupAutomationPolicy().timing(minutes: boundedMinutes, keep: boundedKeep),
              password.utf16.count <= InputPolicy.maxTextChars else { return false }
        let valid: Bool
        if vaultSnapshot != nil {
            valid = password.isEmpty && confirmation.isEmpty
                && recipientKeys.isEmpty && signingKey == nil
        } else if recipientKeys.isEmpty {
            valid = BackupAutomationPolicy().valid(minutes: Int32(minutes), keep: Int32(keep),
                passwordUnits: Int32(password.utf16.count), passwordsMatch: password == confirmation)
        } else {
            valid = AutomaticBackupConfiguration.validRecipients(recipientKeys) && password.isEmpty && confirmation.isEmpty && sealRecipients != nil
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
            self.snapshot = snapshot; self.changed = changed; self.generation = generation; self.admitted = admitted
            _ = automation.enable(generation: Int64(generation()), minutes: Int32(minutes)); enabled = true
            runNow()
            return true
        } catch { return false }
    }

    func disable() {
        stopTimer(); automation.disable(); admitted = { false }; generation = { 0 }
        work?.cancel(); work = nil
        vaultSnapshot = nil
        password = ""; signingKey = nil; signingPassphrase = ""; sealSigned = nil; recipientKeys = []; sealRecipients = nil; directory = nil; snapshot = nil; scope = nil
        enabled = false; busy = false; failed = false
        message = "Disabled. Existing backups were kept."
        let notify = changed; changed = nil; notify?()
    }

    func runNow() {
        guard enabled, !busy, let directory = directory, let scope = scope else { return }
        guard admitted() else { disable(); return }
        let ticket = automation.begin(currentGeneration: Int64(generation()), ready: admitted())
        guard ticket != 0 else { return }
        stopTimer()
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
                let delay = self.automation.complete(ticket: ticket, currentGeneration: Int64(self.generation()), ready: self.admitted(), success: succeeded)
                guard succeeded else { self.fail(); return }
                guard delay >= 0 else { self.disable(); return }
                self.message = self.pendingChange ? "Automatic backup saved. New changes pending; another backup is scheduled shortly."
                    : "Automatic backup saved. Next periodic backup in \(Int(self.interval / 60)) minutes."
                self.scheduleNext(TimeInterval(delay) / 1000)
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
            let values = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
            return values.isRegularFile == true && values.isSymbolicLink != true
        }
        let plan = AutomaticBackupRetention().managedPlan(names: owned.map { $0.lastPathComponent }, keep: Int32(keep), fileExtension: fileExtension)
        for name in plan { try FileManager.default.removeItem(at: directory.appendingPathComponent(name)) }
    }

    deinit { cancelTimer?(); work?.cancel() }
}
