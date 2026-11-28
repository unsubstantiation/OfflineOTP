import XCTest
import OfflineOTPPresentation
@testable import OfflineOTP

final class SessionAutomaticBackupTests: XCTestCase {
    func testSharedSchedulingRejectsLateCompletionAndCoalescesSuccessfulSaves() {
        let schedule = AutomaticBackupSchedule()
        XCTAssertTrue(schedule.enable(generation: 7, minutes: 15))
        let first = schedule.begin(currentGeneration: 7, ready: true)
        XCTAssertGreaterThan(first, 0)
        XCTAssertEqual(schedule.changed(currentGeneration: 7, ready: true), -1)
        XCTAssertEqual(schedule.complete(ticket: first, currentGeneration: 7, ready: true, success: true), 1000)
        let second = schedule.begin(currentGeneration: 7, ready: true)
        schedule.disable()
        XCTAssertEqual(schedule.complete(ticket: second, currentGeneration: 7, ready: true, success: true), -1)
        XCTAssertFalse(schedule.enabled)
        XCTAssertEqual(BackupAutomationPolicy().resume(unlocked: true, requiresSigning: true, signingAvailable: false), "SIGNING_REQUIRED")
    }

    func testGenerationChangePreventsAnotherNativeSnapshot() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        var queued: [() -> Void] = []; var generation = 1; var ready = true
        let backup = SessionAutomaticBackup(enqueue: { queued.append($0) })
        defer { backup.disable() }
        XCTAssertTrue(backup.configure(directory: root, password: "secret", confirmation: "secret", minutes: 1, keep: 2,
            snapshot: { "[]" }, changed: {}, admitted: { ready }, generation: { generation }))
        generation = 2; ready = false
        backup.vaultChanged()
        XCTAssertFalse(backup.enabled)
        queued.removeFirst()() // canceled token cannot write even though work was already queued
        XCTAssertEqual([], try FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent("OfflineOTP-automatic").path))
    }

    @MainActor
    func testSavedVaultCopiesBypassEntrySerializationAndEncryptionAndCancelQueuedWrites() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        var queued: [() -> Void] = []; var timers: [Scheduled] = []; var reads = 0
        let bytes = Data([4, 3, 2, 1])
        let backup = SessionAutomaticBackup(enqueue: { queued.append($0) }, seal: { _, _ in
            XCTFail("a saved-file copy must not re-encrypt"); return Data()
        }, schedule: { delay, action in
            let timer = Scheduled(delay, action); timers.append(timer); return { timer.cancelled = true }
        })
        defer { backup.disable() }
        var completed: XCTestExpectation?
        XCTAssertTrue(backup.configure(directory: root, password: "", confirmation: "", minutes: 1, keep: 1,
            snapshot: { XCTFail("must not serialize entries"); return nil }, changed: {
                if !backup.busy { let done = completed; completed = nil; done?.fulfill() }
            }, vaultSnapshot: { reads += 1; return bytes }))
        XCTAssertTrue(backup.vaultCopy); XCTAssertEqual(2, reads)
        let finished = expectation(description: "copy delivered"); completed = finished
        queued.removeFirst()(); await fulfillment(of: [finished], timeout: 2)
        let managed = root.appendingPathComponent("OfflineOTP-automatic", isDirectory: true)
        let files = try FileManager.default.contentsOfDirectory(at: managed, includingPropertiesForKeys: nil)
        XCTAssertEqual(1, files.count); XCTAssertEqual("offlineotp", files[0].pathExtension)
        XCTAssertEqual(bytes, try Data(contentsOf: files[0])); XCTAssertEqual(60, timers.last?.delay)
        backup.vaultChanged(); XCTAssertEqual(1, timers.last?.delay)
        timers.last?.action(); XCTAssertEqual(1, queued.count)
        backup.disable(); queued.removeFirst()()
        XCTAssertEqual(1, try FileManager.default.contentsOfDirectory(atPath: managed.path).count)
        XCTAssertFalse(backup.vaultCopy)
    }

    func testRetentionOnlyPrunesOwnedRegularFilesAndKeepsNewest() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        for index in 1...4 {
            let stamp = String(format: "%016d", index)
            try Data([1]).write(to: folder.appendingPathComponent("auto-\(stamp)-00000000-0000-0000-0000-000000000000.aes"))
        }
        let manual = folder.appendingPathComponent("manual.aes")
        try Data([9]).write(to: manual)
        let link = folder.appendingPathComponent("auto-0000000000000000-00000000-0000-0000-0000-000000000000.aes")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: manual)
        try SessionAutomaticBackup.prune(folder, keep: 2)
        let remaining = try FileManager.default.contentsOfDirectory(atPath: folder.path)
        XCTAssertEqual(4, remaining.count) // Two managed backups, manual file and untouched symlink.
        XCTAssertTrue(remaining.contains(where: { $0.contains("0000000000000004") }))
        XCTAssertEqual(Data([9]), try Data(contentsOf: manual))
        XCTAssertThrowsError(try SessionAutomaticBackup.prune(folder, keep: 0))
    }

    func testInvalidConfigurationNeverStartsSnapshotOrPersistsCredentials() {
        let backup = SessionAutomaticBackup()
        XCTAssertFalse(backup.configure(directory: FileManager.default.temporaryDirectory, password: "secret", confirmation: "different", minutes: 15, keep: 10,
            snapshot: { XCTFail("invalid configuration took a snapshot"); return nil }, changed: {}))
        XCTAssertFalse(backup.enabled); XCTAssertFalse(backup.busy)
        backup.disable()
        XCTAssertFalse(backup.enabled)
    }

    func testNilSessionSnapshotDisablesImmediatelyWithoutAWorkerWrite() {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let backup = SessionAutomaticBackup()
        XCTAssertTrue(backup.configure(directory: folder, password: "secret", confirmation: "secret", minutes: 1, keep: 2,
            snapshot: { nil }, changed: {}))
        XCTAssertFalse(backup.enabled); XCTAssertFalse(backup.busy)
        XCTAssertTrue(backup.message.contains("failed"))
    }
    private final class Scheduled {
        let delay: TimeInterval
        let action: () -> Void
        var cancelled = false
        init(_ delay: TimeInterval, _ action: @escaping () -> Void) { self.delay = delay; self.action = action }
    }
    @MainActor
    func testQueuedCancellationRevokesInputBeforeEncryption() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        var queued: [() -> Void] = []
        var calls = 0
        let backup = SessionAutomaticBackup(enqueue: { queued.append($0) }, seal: { _, _ in calls += 1; return Data([1]) })
        XCTAssertTrue(backup.configure(directory: folder, password: "secret", confirmation: "secret", minutes: 15, keep: 3,
            snapshot: { "queued synthetic document" }, changed: {}))
        XCTAssertEqual(1, queued.count)
        backup.disable(); queued.removeFirst()()
        XCTAssertEqual(0, calls); XCTAssertFalse(backup.busy)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: folder.appendingPathComponent("OfflineOTP-automatic").path).isEmpty)
    }
    @MainActor
    func testChangesDuringWorkCoalesceAndRetiredTimersCannotRestartWork() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        var queued: [() -> Void] = []
        var timers: [Scheduled] = []
        var sealed: [String] = []
        var document = "first"
        let backup = SessionAutomaticBackup(enqueue: { queued.append($0) }, seal: { _, plain in
            sealed.append(plain); return Data([1])
        }, schedule: { delay, action in
            let scheduled = Scheduled(delay, action); timers.append(scheduled)
            return { scheduled.cancelled = true }
        })
        defer { backup.disable() }
        var completed: XCTestExpectation?
        XCTAssertTrue(backup.configure(directory: folder, password: "secret", confirmation: "secret", minutes: 15, keep: 3,
            snapshot: { document }, changed: {
                if !backup.busy { let done = completed; completed = nil; done?.fulfill() }
            }))
        document = "latest"; for _ in 0..<5 { backup.vaultChanged() }
        XCTAssertEqual(1, queued.count)
        let first = expectation(description: "first write delivered"); completed = first
        queued.removeFirst()()
        await fulfillment(of: [first], timeout: 2)
        XCTAssertEqual(["first"], sealed)
        XCTAssertEqual(1, timers.count); XCTAssertEqual(1, timers[0].delay)
        for _ in 0..<5 { backup.vaultChanged() }
        XCTAssertEqual(1, timers.count) // New saves cannot postpone the first scheduled catch-up.
        timers[0].action(); XCTAssertEqual(1, queued.count)
        let second = expectation(description: "latest write delivered"); completed = second
        queued.removeFirst()()
        await fulfillment(of: [second], timeout: 2)
        XCTAssertEqual(["first", "latest"], sealed)
        XCTAssertEqual(900, timers.last?.delay)
        timers[0].action(); XCTAssertTrue(queued.isEmpty)
        let retired = try XCTUnwrap(timers.last)
        backup.disable(); retired.action(); backup.vaultChanged()
        XCTAssertTrue(queued.isEmpty); XCTAssertFalse(backup.enabled)
    }

    @MainActor
    func testOpenPgpQueueCancellationAndSeparateRetentionNamespace() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        var queued: [() -> Void] = []; var calls = 0
        let backup = SessionAutomaticBackup(enqueue: { queued.append($0) }, seal: { _, _ in
            XCTFail("password encoder selected for OpenPGP"); return Data()
        })
        XCTAssertTrue(backup.configure(directory: root, password: "", confirmation: "", minutes: 15, keep: 2,
            snapshot: { "synthetic vault" }, changed: {}, recipientKeys: [Data([1, 2])],
            sealRecipients: { _, _ in calls += 1; return Data([99]) }))
        XCTAssertTrue(backup.openPgp)
        backup.disable(); queued.removeFirst()()
        XCTAssertEqual(0, calls)
        let managed = root.appendingPathComponent("OfflineOTP-automatic")
        for ext in ["aes", "pgp"] {
            for i in 1...3 {
                let name = String(format: "auto-%016d-00000000-0000-0000-0000-000000000000.%@", i, ext)
                try Data([1]).write(to: managed.appendingPathComponent(name))
            }
        }
        try SessionAutomaticBackup.prune(managed, keep: 1, fileExtension: "pgp")
        let files = try FileManager.default.contentsOfDirectory(atPath: managed.path)
        XCTAssertEqual(3, files.filter { $0.hasSuffix(".aes") }.count)
        XCTAssertEqual(1, files.filter { $0.hasSuffix(".pgp") }.count)
        XCTAssertThrowsError(try SessionAutomaticBackup.prune(managed, keep: 1, fileExtension: "../aes"))
    }
    func testPersistenceHookCannotReenableAfterAdmissionIsRevoked() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        var permitted = true
        let backup = SessionAutomaticBackup(enqueue: { _ in XCTFail("revoked worker enqueued") })
        XCTAssertFalse(backup.configure(directory: root, password: "secret", confirmation: "secret", minutes: 15, keep: 2,
            snapshot: { XCTFail("revoked snapshot"); return nil }, changed: {}, beforeEnable: { permitted = false },
            admitted: { permitted }))
        XCTAssertFalse(backup.enabled)
    }

    @MainActor
    func testOpenPgpUsesDetachedCertificatesAndPublishesSuccessOnlyAfterWrite() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        var queue: [() -> Void] = []
        let backup = SessionAutomaticBackup(enqueue: { queue.append($0) })
        defer { backup.disable() }
        let done = expectation(description: "OpenPGP backup delivered")
        var certificate = Data([7, 8])
        XCTAssertTrue(backup.configure(directory: root, password: "", confirmation: "", minutes: 15, keep: 2,
            snapshot: { "synthetic vault" }, changed: { if backup.enabled && !backup.busy { done.fulfill() } },
            recipientKeys: [certificate], sealRecipients: { document, keys in
                XCTAssertEqual("synthetic vault", document); XCTAssertEqual(Data([7, 8]), keys.first)
                return Data([99, 100])
            }))
        certificate.resetBytes(in: 0..<certificate.count)
        XCTAssertTrue(backup.busy)
        queue.removeFirst()()
        await fulfillment(of: [done], timeout: 2)
        let files = try FileManager.default.contentsOfDirectory(at: root.appendingPathComponent("OfflineOTP-automatic"), includingPropertiesForKeys: nil)
        XCTAssertEqual(1, files.count); XCTAssertEqual("pgp", files[0].pathExtension)
        XCTAssertEqual(Data([99, 100]), try Data(contentsOf: files[0]))
    }

    @MainActor
    func testSignedJobNeverFallsBackUnsignedAndCancellationReleasesQueuedCredentials() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        var queue: [() -> Void] = []; var calls = 0
        let backup = SessionAutomaticBackup(enqueue: { queue.append($0) })
        XCTAssertTrue(backup.configure(directory: root, password: "", confirmation: "", minutes: 15, keep: 2,
            snapshot: { "synthetic vault" }, changed: {}, recipientKeys: [Data([1])],
            sealRecipients: { _, _ in XCTFail("unsigned fallback"); return Data() },
            signingKey: Data([7, 8]), signingPassphrase: "session-pass",
            sealSigned: { _, _, _, _ in calls += 1; throw CocoaError(.fileReadNoPermission) }))
        XCTAssertTrue(backup.signed)
        backup.disable(); queue.removeFirst()()
        XCTAssertEqual(0, calls); XCTAssertFalse(backup.signed)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent("OfflineOTP-automatic").path).isEmpty)
        backup.reportSigningRequired()
        XCTAssertTrue(backup.failed); XCTAssertTrue(backup.message.contains("No unsigned backup"))
    }

}
