import XCTest
import UIKit
import OfflineOTPKit
@testable import OfflineOTP

/// The app's wiring around the vault: which folder it uses, what a wrong password says, what a save
/// writes, and that the list rules reach the files instead of only the screen.
final class VaultStoreTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("offlineotp-ios-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func makeStore() -> VaultStore {
        VaultStore(directory: directory)
    }

    private func vaultAdapter(_ store: VaultStore) -> VaultCoordinator {
        VaultCoordinator(store: store, onExport: { _, _ in }, onImport: { _ in }, onOpenLink: { _ in })
    }

    private func unlockedStore() -> VaultStore {
        let store = makeStore()
        store.unlock(password: "correct horse", confirmation: "correct horse", creating: true)
        return store
    }

    private func draft(_ label: String, issuer: String = "Issuer", kind: EntryKind = .totp,
                       tags: [String] = []) throws -> Entry {
        try EntryDraft.create(kind: kind, issuer: issuer, label: label, secret: "JBSWY3DPEHPK3PXP",
                             digits: "6", timing: "30", algorithm: .sha1, tags: tags,
                             newEntry: true)
    }

    private func rawEntry(_ label: String) throws -> Entry {
        try Entry.parse(.object([
            "secret": .string("JBSWY3DPEHPK3PXP"),
            "issuer": .string("Issuer"),
            "label": .string(label),
            "digits": .int(6),
            "type": .string("TOTP"),
            "algorithm": .string("SHA1"),
            "thumbnail": .string("Default"),
            "last_used": .int(0),
            "used_frequency": .int(0),
            "period": .int(30),
            "tags": .array([]),
        ]))
    }

    // MARK: - Unlocking

    func testTheFolderIsTheDocumentsDataDirectory() {
        let fileManager = FileManager.default
        let documents = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first
        XCTAssertEqual(VaultStore.defaultDirectory(fileManager: fileManager),
                       documents?.appendingPathComponent("data", isDirectory: true))
    }

    func testAFreshFolderHasNoVaultAndCreatingOneUnlocksIt() {
        let store = makeStore()
        XCTAssertFalse(store.hasVault)
        XCTAssertFalse(store.isUnlocked)

        store.unlock(password: "correct horse", confirmation: "correct horse", creating: true)
        XCTAssertNil(store.message)
        XCTAssertTrue(store.hasVault)
        XCTAssertTrue(store.isUnlocked)
        XCTAssertTrue(store.entries.isEmpty)
        XCTAssertTrue(FileManager.default.fileExists(
            atPath: directory.appendingPathComponent(VaultStore.vaultFilename).path))
    }

    func testTheShortPasswordAndTheMismatchAreExplainedRatherThanRefusedSilently() {
        let store = makeStore()
        store.unlock(password: "short", confirmation: "short", creating: true)
        XCTAssertFalse(store.isUnlocked)
        XCTAssertEqual(store.message, "Use at least 6 characters.")

        store.unlock(password: "correct horse", confirmation: "correct hors", creating: true)
        XCTAssertFalse(store.isUnlocked)
        XCTAssertEqual(store.message, "The two passwords do not match.")
        XCTAssertFalse(store.hasVault)
    }

    func testAWrongPasswordSaysTheSameThingWhateverTheReason() {
        let store = unlockedStore()
        XCTAssertTrue(store.isUnlocked)

        let second = makeStore()
        second.unlock(password: "wrong horse")
        XCTAssertFalse(second.isUnlocked)
        XCTAssertEqual(second.message, "That password does not open this vault.")
        // A failed unlock must not reveal as much as an error name would.
        XCTAssertFalse(second.message?.contains("authentication") ?? true)
    }

    func testLockingForgetsTheEntriesAndAnEmptyFolderSaysSo() {
        let store = unlockedStore()
        store.lock()
        XCTAssertFalse(store.isUnlocked)
        XCTAssertTrue(store.entries.isEmpty)
        XCTAssertTrue(store.hasVault)

        store.unlock(password: "correct horse")
        XCTAssertTrue(store.isUnlocked)

        let empty = VaultStore(directory: directory.appendingPathComponent("nowhere"))
        empty.unlock(password: "correct horse")
        XCTAssertEqual(empty.message, "There is no vault in this folder yet.")
    }

    func testSaveIsRefusedWhileLocked() {
        let store = makeStore()
        XCTAssertFalse(store.save())
    }

    // MARK: - Entries

    func testASavedEntryIsVisibleToASecondStoreOverTheSameFolder() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try rawEntry("alice@example.com")))
        XCTAssertEqual(store.entries.count, 1)
        XCTAssertTrue(store.setDetailsVisible(at: 0, true))

        let reopened = makeStore()
        reopened.unlock(password: "correct horse")
        XCTAssertEqual(reopened.entries.count, 1)
        XCTAssertEqual(reopened.entries.first?.issuer, "Issuer")
        XCTAssertEqual(reopened.code(for: reopened.entries[0],
                                     at: Date(timeIntervalSince1970: 1_700_000_000)),
                       "324550")
    }

    func testAnEntryWithoutACodeReturnsNothingInsteadOfThrowing() throws {
        let store = unlockedStore()
        let note = try draft("Recovery", kind: .note)
        XCTAssertTrue(store.add(note))
        XCTAssertTrue(store.setDetailsVisible(at: 0, true))
        XCTAssertNil(store.code(for: store.entries[0],
                                at: Date(timeIntervalSince1970: 1_700_000_000)))
    }

    func testToggleRevealPersistsHideAndRevealAndUsageDoesNotRevealHiddenEntry() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try rawEntry("alice")))
        XCTAssertTrue(store.setDetailsVisible(at: 0, true))
        let original = store.entries[0]
        store.toggleReveal(at: 0)
        XCTAssertFalse(store.isRevealed(store.entries[0]))
        XCTAssertFalse(store.entries[0].detailsVisible)
        XCTAssertNil(store.code(for: store.entries[0], at: Date(timeIntervalSince1970: 1_700_000_000)))
        XCTAssertEqual(try store.entries[0].code(unixTime: 1_700_000_000), "324550")
        XCTAssertTrue(store.recordUse(at: 0))
        XCTAssertFalse(store.isRevealed(store.entries[0]))
        let reopenedHidden = makeStore()
        reopenedHidden.unlock(password: "correct horse")
        XCTAssertFalse(reopenedHidden.isRevealed(reopenedHidden.entries[0]))
        reopenedHidden.toggleReveal(at: 0)
        XCTAssertTrue(reopenedHidden.isRevealed(reopenedHidden.entries[0]))
        let reopenedRevealed = makeStore()
        reopenedRevealed.unlock(password: "correct horse")
        XCTAssertTrue(reopenedRevealed.isRevealed(reopenedRevealed.entries[0]))
        store.lock()
        XCTAssertFalse(store.isRevealed(original))
    }

    func testAConcealedEntryShowsNoCodeAndTheRevealIsStored() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try rawEntry("alice")))
        let date = Date(timeIntervalSince1970: 1_700_000_000)
        // Concealed is the default a document without `details_visible` means, and both other
        // clients redact the token in that state.
        XCTAssertEqual(store.entries[0].detailsVisible, false)
        XCTAssertNil(store.code(for: store.entries[0], at: date))

        XCTAssertTrue(store.setDetailsVisible(at: 0, true))
        XCTAssertEqual(store.code(for: store.entries[0], at: date), "324550")

        // Persisted as vault data, so a second instance over the same folder agrees.
        let reopened = makeStore()
        reopened.unlock(password: "correct horse")
        XCTAssertEqual(reopened.entries[0].detailsVisible, true)

        // Concealing writes the field away again, keeping the legacy bytes for the default state.
        XCTAssertTrue(store.setDetailsVisible(at: 0, false))
        XCTAssertNil(store.entries[0].fields["details_visible"])
        XCTAssertFalse(store.setDetailsVisible(at: 99, true))
    }

    func testAddingAnEntrySelectsItsTabAndRefusesAnIdenticalOne() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try draft("alice")))
        XCTAssertEqual(store.entryCount(for: .otp), 1)
        XCTAssertTrue(store.add(try draft("Recovery", kind: .note)))
        // Both other clients select the tab of the entry they just added.
        XCTAssertEqual(store.category, .notes)

        // The same account twice is refused even though the copy carries its own usage stamp:
        // identity is `Entry.equals`' — issuer, label, seed, kind and token parameters — not the
        // metadata, which is the rule `EntryList.addEntry` applies on the phone and the desktop.
        XCTAssertFalse(store.add(try draft("alice")))
        XCTAssertEqual(store.message, "This vault already holds that entry.")
        XCTAssertEqual(store.entries.count, 2)

        // A different account from the same issuer is a new row, and its tab is selected again.
        XCTAssertTrue(store.add(try draft("bob")))
        XCTAssertEqual(store.entries.count, 3)
        XCTAssertEqual(store.category, .otp)
    }

    func testDeletingAndEditingPersist() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try draft("alice")))
        XCTAssertTrue(store.add(try draft("bob")))

        var edited = store.entries[1]
        edited.setUsedFrequency(4)
        XCTAssertTrue(store.update(at: 1, with: edited))
        XCTAssertFalse(store.remove(at: 9))

        let reopened = makeStore()
        reopened.unlock(password: "correct horse")
        XCTAssertEqual(reopened.entries.map(\.label), ["alice", "bob"])
        XCTAssertEqual(reopened.entries[1].usedFrequency, 4)

        XCTAssertTrue(store.remove(at: 0))
        XCTAssertEqual(store.entries.map(\.label), ["bob"])
        XCTAssertEqual(VaultStore.displayName(store.entries[0]), "bob")
    }

    // MARK: - Usage

    func testCopyingRecordsUseAndTheStepAdvancesTheCounter() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try draft("alice")))
        XCTAssertTrue(store.recordUse(at: 0, now: Date(timeIntervalSince1970: 1_700_000_000)))
        XCTAssertEqual(store.entries[0].usedFrequency, 1)
        XCTAssertEqual(store.entries[0].lastUsed, 1_700_000_000_000)

        // A time-based entry has no step to take.
        XCTAssertFalse(store.advanceCounter(at: 0))

        let hotp = try Entry.parse(.object([
            "secret": .string("KRSXG5A="),
            "issuer": .string("Issuer"),
            "label": .string("counter"),
            "digits": .int(8),
            "type": .string("HOTP"),
            "algorithm": .string("SHA256"),
            "thumbnail": .string(""),
            "last_used": .int(0),
            "used_frequency": .int(0),
            "counter": .int(0),
            "tags": .array([]),
        ]))
        XCTAssertTrue(store.add(hotp))
        XCTAssertTrue(store.advanceCounter(at: 1))
        XCTAssertEqual(store.entries[1].counter, 1)
        XCTAssertEqual(store.entries[1].usedFrequency, 1)

        let reopened = makeStore()
        reopened.unlock(password: "correct horse")
        XCTAssertEqual(reopened.entries[1].counter, 1)
        XCTAssertEqual(reopened.entries[1].usedFrequency, 1)
    }

    // MARK: - The list rules reach the files

    func testTheTabsSearchAndTagsComposeOverTheStoredEntries() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try draft("alice-work", tags: ["work"])))
        XCTAssertTrue(store.add(try draft("alice-home", tags: ["home"])))
        XCTAssertTrue(store.add(try draft("bob", tags: ["work"])))
        XCTAssertTrue(store.add(try draft("Recovery", kind: .note)))

        store.category = .otp
        XCTAssertEqual(store.visibleIndices.map { store.entries[$0].label },
                       ["alice-work", "alice-home", "bob"])

        store.query = "alice"
        XCTAssertEqual(store.visibleIndices.map { store.entries[$0].label },
                       ["alice-work", "alice-home"])

        // A live search survives a tab change, and the tab still narrows it.
        store.category = .notes
        XCTAssertEqual(store.visibleIndices.count, 0)
        store.category = .otp

        // Emptying the search brings the tag filter back.
        store.query = ""
        store.toggleTag("work")
        XCTAssertEqual(store.visibleIndices.map { store.entries[$0].label }, ["alice-work", "bob"])
        store.toggleTag("home")
        XCTAssertEqual(store.visibleIndices.count, 3)
        store.setTagFunctionality(.and)
        // Both tags are now selected and no entry carries both, so the intersection is empty.
        XCTAssertEqual(store.visibleIndices.count, 0)
        store.toggleTag("home")
        XCTAssertEqual(store.visibleIndices.map { store.entries[$0].label }, ["alice-work", "bob"])

        // The chip list, ordered case-insensitively and without duplicates.
        XCTAssertEqual(store.tags(for: .otp), ["home", "work"])
        store.clearFilters()
        XCTAssertEqual(store.visibleIndices.count, 3)
        XCTAssertEqual(store.entryCount(for: .notes), 1)
    }

    func testTheSortModeIsRememberedBesideTheVaultAndNeverInsideIt() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try draft("bob", issuer: "Zeta")))
        XCTAssertTrue(store.add(try draft("alice", issuer: "Alpha")))

        let vaultFile = directory.appendingPathComponent(VaultStore.vaultFilename)
        let before = try Data(contentsOf: vaultFile)

        store.setSortMode(.issuer)
        XCTAssertEqual(store.visibleIndices.map { store.entries[$0].label }, ["alice", "bob"])

        // A client preference, not vault data: the vault's bytes are untouched, so an older build
        // still opens it and sorting never rewrites the document.
        XCTAssertEqual(try Data(contentsOf: vaultFile), before)
        let settings = PropertyListSettings(
            url: directory.appendingPathComponent(VaultStore.settingsFilename))
        XCTAssertEqual(settings.string(SortMode.settingsKey), "ISSUER")

        // A second session over the same folder restores the order.
        let reopened = makeStore()
        XCTAssertEqual(reopened.sortMode, .issuer)
        reopened.unlock(password: "correct horse")
        XCTAssertEqual(reopened.visibleIndices.map { reopened.entries[$0].label }, ["alice", "bob"])

        store.setSortMode(.unsorted)
        XCTAssertEqual(store.visibleIndices.map { store.entries[$0].label }, ["bob", "alice"])
    }

    func testAMissingSortPreferenceFallsBackToTheVaultOrder() throws {
        let store = unlockedStore()
        XCTAssertEqual(store.sortMode, .unsorted)
        // An unreadable value must not fail the unlock — the desktop client degrades the same way.
        let settings = PropertyListSettings(
            url: directory.appendingPathComponent(VaultStore.settingsFilename))
        settings.setString(SortMode.settingsKey, "nonsense")
        XCTAssertEqual(makeStore().sortMode, .unsorted)
    }

    func testCopyingSetsTheClipboardAndAnExpiry() {
        // The pasteboard is a system service; this only checks the app's own arithmetic around it.
        XCTAssertEqual(Clipboard.expirySeconds, 30)
        XCTAssertTrue(Clipboard.copy("324550", now: Date(timeIntervalSince1970: 1_700_000_000)))
    }

    // MARK: - Backups, restores and links

    func testAnExportedBackupRestoresIntoAnotherVault() throws {
        let source = unlockedStore()
        XCTAssertTrue(source.add(try rawEntry("alice@example.com")))
        XCTAssertTrue(source.add(try draft("bob", issuer: "Acme", tags: ["work"])))

        let exported = try XCTUnwrap(source.exportBackup(password: "backup pass",
                                                        now: Date(timeIntervalSince1970: 0)))
        // The file is dated, so two exports never overwrite each other, and it is the encrypted
        // container the other clients write.
        XCTAssertTrue(exported.name.hasPrefix("otp_accounts_"))
        XCTAssertTrue(exported.name.hasSuffix(".json.aes"))
        XCTAssertTrue(exported.data.count > 44)

        // Another vault, in another folder: a second store over the same folder would be the same
        // vault, and a merge into it would correctly add nothing.
        let elsewhere = directory.appendingPathComponent("second", isDirectory: true)
        try FileManager.default.createDirectory(at: elsewhere, withIntermediateDirectories: true)
        let other = VaultStore(directory: elsewhere)
        other.unlock(password: "correct horse", confirmation: "correct horse", creating: true)
        XCTAssertTrue(other.isUnlocked)
        let plan = try XCTUnwrap(other.restore(encrypted: exported.data, password: "backup pass",
                                              replace: false))
        XCTAssertEqual(plan.added, 2)
        XCTAssertEqual(other.entries.map(\.label).sorted(), ["alice@example.com", "bob"])
        XCTAssertEqual(other.entries.first { $0.label == "bob" }?.tags, ["work"])
        // The restore reaches the file, not only the screen.
        let reopened = VaultStore(directory: elsewhere)
        reopened.unlock(password: "correct horse")
        XCTAssertEqual(reopened.entries.count, 2)
    }

    func testFirstRunRestoreCreatesNewVaultWithAllEntriesAndNewPassword() throws {
        let source = unlockedStore()
        XCTAssertTrue(source.add(try rawEntry("alice")))
        XCTAssertTrue(source.add(try draft("bob")))
        let exported = try XCTUnwrap(source.exportBackup(password: "backup password"))

        let elsewhere = directory.appendingPathComponent("first-run-restore", isDirectory: true)
        try FileManager.default.createDirectory(at: elsewhere, withIntermediateDirectories: true)
        let destination = VaultStore(directory: elsewhere)
        XCTAssertTrue(destination.restoreNewVault(encrypted: exported.data,
            backupPassword: "backup password", newPassword: "new vault password",
            confirmation: "new vault password"))
        XCTAssertEqual(destination.entries.map(\\.label).sorted(), ["alice", "bob"])
        XCTAssertTrue(destination.isUnlocked)
        let reopened = VaultStore(directory: elsewhere)
        reopened.unlock(password: "new vault password")
        XCTAssertTrue(reopened.isUnlocked)
        XCTAssertEqual(reopened.entries.count, 2)
    }

    func testFirstRunRestoreRejectsWrongBackupPasswordWithoutCreatingVault() throws {
        let source = unlockedStore()
        XCTAssertTrue(source.add(try rawEntry("alice")))
        let exported = try XCTUnwrap(source.exportBackup(password: "backup password"))
        let elsewhere = directory.appendingPathComponent("bad-first-run-restore", isDirectory: true)
        try FileManager.default.createDirectory(at: elsewhere, withIntermediateDirectories: true)
        let destination = VaultStore(directory: elsewhere)
        XCTAssertFalse(destination.restoreNewVault(encrypted: exported.data,
            backupPassword: "wrong", newPassword: "new vault password",
            confirmation: "new vault password"))
        XCTAssertFalse(destination.hasVault)
    }

    func testARestoreMergesByDefaultAndKeepsTheExistingEntries() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try rawEntry("kept")))

        // The same entry plus one the vault does not have yet.
        let imported = try rawEntry("kept")
        let extra = try rawEntry("extra")
        let document = try VaultDocument.encode([imported, extra])
        let plan = try XCTUnwrap(store.restore(encrypted:
            EncryptedBackup.sealWithFreshParameters(password: "backup pass", plain: document),
            password: "backup pass", replace: false))
        XCTAssertEqual(plan.added, 1)
        XCTAssertEqual(store.entries.map(\.label), ["extra", "kept"])
    }

    func testReplacingTakesTheBackupVerbatim() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try rawEntry("current")))
        let document = try VaultDocument.encode([try rawEntry("imported")])
        let data = try EncryptedBackup.sealWithFreshParameters(password: "pw", plain: document)
        let plan = try XCTUnwrap(store.restore(encrypted: data, password: "pw", replace: true))
        XCTAssertEqual(plan.entries.map(\.label), ["imported"])
        XCTAssertEqual(store.entries.map(\.label), ["imported"])
    }

    func testAWrongBackupPasswordLeavesTheVaultAlone() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try rawEntry("kept")))
        let exported = try XCTUnwrap(store.exportBackup(password: "backup pass"))

        XCTAssertNil(store.restore(encrypted: exported.data, password: "wrong", replace: false))
        XCTAssertEqual(store.message, BackupWording.wrongPassword)
        XCTAssertEqual(store.entries.map(\.label), ["kept"])
    }

    func testAPlainTextBackupIsReadAndAnEmptyOneIsRefused() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try rawEntry("kept")))

        // Plain-text backups were removed — the import path no longer exists. Verify the
        // encrypted path's empty-backup refusal and that exports are never plain text.
        let document = try VaultDocument.encode([try rawEntry("plain")])
        let exported = try XCTUnwrap(store.exportBackup(password: "backup pass"))
        XCTAssertNotEqual(String(data: exported.data, encoding: .utf8), document)

        let emptyData = try EncryptedBackup.sealWithFreshParameters(password: "pw", plain: "[]")
        XCTAssertNil(store.restore(encrypted: emptyData, password: "pw", replace: false))
        XCTAssertEqual(store.message, BackupWording.emptyBackup)
        XCTAssertNil(store.restore(encrypted: Data(), password: "pw", replace: false))
        XCTAssertEqual(store.message, BackupWording.unreadableBackup)
    }

    func testAPastedLinkBecomesAnEntry() throws {
        let store = unlockedStore()
        XCTAssertEqual(store.importUri("otpauth://totp/Example:alice@example.com?"
                                       + "secret=JBSWY3DPEHPK3PXP&issuer=Example&tags=work"), .added)
        XCTAssertEqual(store.entries.count, 1)
        XCTAssertEqual(store.entries[0].issuer, "Example")
        XCTAssertEqual(store.entries[0].tags, ["work"])
        // An imported account is concealed like any other, so it shows no code until it is revealed.
        XCTAssertNil(store.code(for: store.entries[0],
                                at: Date(timeIntervalSince1970: 1_700_000_000)))
        XCTAssertTrue(store.setDetailsVisible(at: 0, true))
        XCTAssertEqual(store.code(for: store.entries[0],
                                 at: Date(timeIntervalSince1970: 1_700_000_000)), "324550")

        XCTAssertEqual(store.importUri("otpauth://totp/x?secret=not-a-seed"),
                       .rejected("INVALID_SECRET"))
        XCTAssertEqual(store.message, OtpParseError.invalidSecret.message)
        XCTAssertEqual(store.entries.count, 1)
        // A link pasted with a trailing newline still imports; the clipboard adds one often.
        XCTAssertEqual(store.importUri("otpauth://totp/bob?secret=JBSWY3DPEHPK3PXP\n"), .added)
    }

    func testASecondAccountFromAnIssuerIsAQuestionNotAFailure() throws {
        let store = unlockedStore()
        let moment = Date(timeIntervalSince1970: 1_700_000_000)
        XCTAssertEqual(store.importUri("otpauth://totp/Acme:one?secret=JBSWY3DPEHPK3PXP"
                                       + "&issuer=Acme", now: moment), .added)
        // Same issuer, different account: ask first, as both other clients do on a QR import.
        XCTAssertEqual(store.importUri("otpauth://totp/Acme:two?secret=JBSWY3DPEHPK3PXP"
                                       + "&issuer=acme "), .duplicateIssuer("acme"))
        XCTAssertEqual(store.entries.count, 1)
        XCTAssertEqual(store.importUri("otpauth://totp/Acme:two?secret=JBSWY3DPEHPK3PXP"
                                       + "&issuer=acme ", allowDuplicateIssuer: true), .added)
        XCTAssertEqual(store.entries.count, 2)
        XCTAssertEqual(VaultStore.ImportOutcome.duplicateIssuerMessage("Acme"),
                       "An account for Acme is already in your vault. Add another account from this "
                       + "issuer anyway?")
        // The identical account is refused: the vault would hold it twice.
        XCTAssertEqual(store.importUri("otpauth://totp/Acme:one?secret=JBSWY3DPEHPK3PXP"
                                       + "&issuer=Acme", allowDuplicateIssuer: true,
                                       now: moment), .duplicateEntry)
        XCTAssertEqual(store.entries.count, 2)
        XCTAssertTrue(store.hasIssuer("  ACME  "))
        XCTAssertFalse(store.hasIssuer("   "))
        XCTAssertFalse(store.hasIssuer("Github"))
    }

    func testAGenericOrUnsupportedCodeIsExplained() throws {
        let store = unlockedStore()
        // A Wi-Fi joining code is a real QR payload that is not an account: the parser says so, the
        // same way `QrImport` reports `Outcome.Invalid` rather than "nothing recognised".
        XCTAssertEqual(store.importPayload("WIFI:S:net;T:WPA;P:pass;;"), .rejected("INVALID_URI"))
        XCTAssertEqual(store.message, OtpParseError.invalidUri.message)
        // A Google Authenticator export is collected rather than added as one entry; a code that
        // cannot be decoded is explained in the export's own words, as on Android.
        guard case .migration(let step) = store.importPayload("otpauth-migration://offline?data=abc")
        else { return XCTFail("a migration code must be routed to the export import") }
        XCTAssertNil(step.entries)
        XCTAssertEqual(step.message, MigrationWording.invalid)
        XCTAssertEqual(store.message, MigrationWording.invalid)
        XCTAssertEqual(store.importPayload("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP"), .added)
        XCTAssertEqual(store.entries.count, 1)
    }

    func testOnlyOtpEntriesHaveALinkToShowAsACode() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try rawEntry("alice@example.com")))
        XCTAssertTrue(store.add(try draft("Recovery", kind: .note)))
        XCTAssertEqual(store.otpUri(for: 0),
                       "otpauth://totp/alice%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=Issuer")
        XCTAssertNil(store.otpUri(for: 1))
        XCTAssertNil(store.otpUri(for: 9))
    }

    // MARK: - Google Authenticator exports

    /// One account, one code: `Example:alice` with the RFC 6238 seed.
    private let migrationSingle = "otpauth-migration://offline?data=CjYKFDEyMzQ1Njc4OTAxMjM0NTY3ODkw"
        + "Eg1FeGFtcGxlOmFsaWNlGgdFeGFtcGxlIAEoATACOAAQAQ=="
    /// The first and second code of one two-code export.
    private let migrationFirst = "otpauth-migration://offline?data=CjYKFDEyMzQ1Njc4OTAxMjM0NTY3ODkw"
        + "Eg1FeGFtcGxlOmFsaWNlGgdFeGFtcGxlIAEoATACOAAQARgCKAc="
    private let migrationSecond = "otpauth-migration://offline?data=CjQKFDIzNDU2Nzg5MDEyMzQ1Njc4OTAx"
        + "EgtFeGFtcGxlOmJvYhoHRXhhbXBsZSABKAEwAjgAEAEYAiABKAc="
    /// A code from a different export, offered while the two-code one is open.
    private let migrationForeign = "otpauth-migration://offline?data=CjIKFDM0NTY3ODkwMTIzNDU2Nzg5MDEy"
        + "EgtPdGhlcjpjYXJvbBoFT3RoZXIgASgBMAI4ABABGAIgASgI"

    func testAnExportIsCollectedUntilTheLastCodeArrives() {
        let store = unlockedStore()
        let first = store.addMigrationImportCode(migrationFirst)
        XCTAssertNil(first.entries)
        XCTAssertEqual(first.received, 1)
        XCTAssertEqual(first.expected, 2)
        XCTAssertEqual(first.message,
                       "Scanned 1 of 2 QR codes. Scan or choose the next code from the same export.")
        // Nothing is written before the whole export is present.
        XCTAssertTrue(store.entries.isEmpty)

        let second = store.addMigrationImportCode(migrationSecond)
        XCTAssertEqual(second.received, 2)
        XCTAssertEqual(second.expected, 2)
        XCTAssertEqual(second.entries?.map(\.label), ["alice", "bob"])
        XCTAssertEqual(second.entries?.map(\.issuer), ["Example", "Example"])
        XCTAssertEqual(store.entries.count, 0)
        // Asking for the export again would start over: it was handed to the confirmation.
        XCTAssertNil(store.openMigrationImport)
    }

    func testAnExportIsWrittenOnlyOnceItIsConfirmed() throws {
        let store = unlockedStore()
        let step = store.addMigrationImportCode(migrationSingle)
        let entries = try XCTUnwrap(step.entries)
        XCTAssertEqual(entries.count, 1)
        XCTAssertTrue(store.entries.isEmpty)

        let result = store.confirmMigrationImport(entries)
        XCTAssertEqual(result, .imported(added: 1, skipped: 0))
        XCTAssertEqual(result.message, "Imported 1 accounts; skipped 0 identical accounts.")
        XCTAssertEqual(store.entries.count, 1)
        XCTAssertEqual(store.entries[0].label, "alice")
        XCTAssertEqual(store.entries[0].issuer, "Example")
        // A code from the import can be read at once, and the account is concealed like every other.
        XCTAssertFalse(store.entries[0].detailsVisible)
        XCTAssertEqual(store.category, .otp)

        let reopened = makeStore()
        reopened.unlock(password: "correct horse")
        XCTAssertEqual(reopened.entries.count, 1)
        XCTAssertEqual(reopened.entries[0].label, "alice")
    }

    func testAnIdenticalAccountIsSkippedRatherThanImportedTwice() throws {
        let store = unlockedStore()
        let first = try XCTUnwrap(store.addMigrationImportCode(migrationSingle).entries)
        XCTAssertEqual(store.confirmMigrationImport(first), .imported(added: 1, skipped: 0))
        // The same export again: the account is the same account even though its last-used stamp
        // has moved, which is what Android's `Entry.equals` decides.
        let again = try XCTUnwrap(store.addMigrationImportCode(migrationSingle).entries)
        let result = store.confirmMigrationImport(again)
        XCTAssertEqual(result, .imported(added: 0, skipped: 1))
        XCTAssertEqual(store.entries.count, 1)
    }

    func testAForeignOrRepeatedCodeKeepsTheExportOpen() {
        let store = unlockedStore()
        _ = store.addMigrationImportCode(migrationFirst)
        XCTAssertEqual(store.addMigrationImportCode(migrationForeign).message,
                       "This code belongs to a different export. Choose a code from the original "
                       + "export.")
        XCTAssertEqual(store.addMigrationImportCode(migrationFirst).message,
                       "This export QR code was already scanned.")
        // The export still completes with the code it was waiting for.
        XCTAssertNotNil(store.addMigrationImportCode(migrationSecond).entries)
    }

    func testALockEndsAnExportInProgress() {
        let store = unlockedStore()
        let generation = store.generation
        _ = store.addMigrationImportCode(migrationFirst)
        XCTAssertNotNil(store.openMigrationImport)
        store.lock()
        XCTAssertNil(store.openMigrationImport)
        XCTAssertGreaterThan(store.generation, generation)

        store.unlock(password: "correct horse")
        // The other half of an export from the previous session completes nothing.
        let step = store.addMigrationImportCode(migrationSecond)
        XCTAssertNil(step.entries)
        XCTAssertEqual(step.received, 1)
        XCTAssertTrue(store.entries.isEmpty)
    }

    func testTheConfirmationNamesTheAccountsAndNeverTheSecrets() throws {
        let store = unlockedStore()
        let entries = try XCTUnwrap(store.addMigrationImportCode(migrationSingle).entries)
        let preview = MigrationWording.preview(entries)
        XCTAssertTrue(preview.hasPrefix("Import 1 accounts from this export?\n\n"))
        XCTAssertTrue(preview.contains("Example — alice"))
        XCTAssertTrue(preview.hasSuffix(MigrationWording.duplicateNotice))
        // The seed behind the account is not in the confirmation.
        XCTAssertFalse(preview.contains("12345678901234567890"))
        XCTAssertFalse(preview.contains(entries[0].secretBase32 ?? "\u{0}"))
    }

    // MARK: - Parity: password change, auto-lock, tags, reorder, credentials & OpenPGP

    func testChangePasswordAndAutoLockWorkInPlace() throws {
        let store = unlockedStore()
        XCTAssertEqual(store.inactivityLockSeconds, 600)
        XCTAssertTrue(store.add(try draft("alice", issuer: "GitHub")))

        let defaultBase = Date()
        store.noteUserActivity(now: defaultBase)
        store.checkInactivityAutoLock(now: defaultBase.addingTimeInterval(599))
        XCTAssertTrue(store.isUnlocked)
        store.checkInactivityAutoLock(now: defaultBase.addingTimeInterval(600))
        XCTAssertFalse(store.isUnlocked)

        store.unlock(password: "correct horse")
        XCTAssertTrue(store.isUnlocked)
        store.setInactivityLockSeconds(60)
        XCTAssertEqual(store.inactivityLockSeconds, 60)

        let base = Date()
        store.noteUserActivity(now: base)
        store.checkInactivityAutoLock(now: base.addingTimeInterval(30))
        XCTAssertTrue(store.isUnlocked)
        store.checkInactivityAutoLock(now: base.addingTimeInterval(61))
        XCTAssertFalse(store.isUnlocked)

        store.unlock(password: "correct horse")
        XCTAssertTrue(store.isUnlocked)
        XCTAssertTrue(store.changePassword(
            currentPassword: "correct horse",
            newPassword: "new vault passphrase",
            confirmation: "new vault passphrase"
        ))
        store.lock()
        store.unlock(password: "correct horse")
        XCTAssertFalse(store.isUnlocked)
        store.unlock(password: "new vault passphrase")
        XCTAssertTrue(store.isUnlocked)
        XCTAssertEqual(store.entries.count, 1)
    }

    @MainActor
    func testComposePasswordChangeAdapterRejectsClosedAndStaleSessions() {
        let store = unlockedStore()
        let closed = vaultAdapter(store)
        XCTAssertNil(closed.action(key: "passwordChange"))
        closed.invalidate()
        XCTAssertNotNil(closed.action(key: "confirm"))
        let stale = vaultAdapter(store)
        XCTAssertNil(stale.action(key: "passwordChange"))
        stale.change(key: "current", value: "correct horse")
        stale.change(key: "next", value: "new password")
        stale.change(key: "confirmation", value: "new password")
        store.lock()
        store.unlock(password: "correct horse")
        XCTAssertNotNil(stale.action(key: "confirm"))
        stale.invalidate()
        store.lock()
        store.unlock(password: "correct horse")
        XCTAssertTrue(store.isUnlocked)
    }

    @MainActor
    func testComposePasswordChangeAdapterAllowsRetryAndPreservesEntries() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try draft("preserved")))
        let adapter = vaultAdapter(store)
        defer { adapter.invalidate() }
        XCTAssertNil(adapter.action(key: "passwordChange"))
        adapter.change(key: "current", value: "correct horse")
        adapter.change(key: "next", value: "new password")
        adapter.change(key: "confirmation", value: "mismatch")
        XCTAssertNotNil(adapter.action(key: "confirm"))
        adapter.change(key: "current", value: "wrong password")
        adapter.change(key: "confirmation", value: "new password")
        XCTAssertNotNil(adapter.action(key: "confirm"))
        adapter.change(key: "current", value: "correct horse")
        XCTAssertNil(adapter.action(key: "confirm"))
        store.lock()
        store.unlock(password: "new password")
        XCTAssertTrue(store.isUnlocked)
        XCTAssertEqual(store.entries.map(\.label), ["preserved"])
    }

    @MainActor
    func testNativeRequestAuthorityCannotSurviveSurfaceReplacementBackgroundOrClose() throws {
        let store = unlockedStore()
        let adapter = vaultAdapter(store)
        let first = adapter.nativeRequestID
        XCTAssertTrue(adapter.acceptsNativeResult(first))
        adapter.tool(action: "settings")
        XCTAssertFalse(adapter.acceptsNativeResult(first))
        let second = adapter.nativeRequestID
        XCTAssertTrue(adapter.acceptsNativeResult(second))
        XCTAssertNil(adapter.action(key: "dismiss"))
        XCTAssertFalse(adapter.acceptsNativeResult(second))
        let third = adapter.nativeRequestID
        NotificationCenter.default.post(name: UIScene.didEnterBackgroundNotification, object: nil)
        XCTAssertFalse(adapter.acceptsNativeResult(third))
        adapter.invalidate()
        XCTAssertFalse(adapter.acceptsNativeResult(adapter.nativeRequestID))
    }

    @MainActor
    func testCentralEntryReplacementPreservesOpaqueMetadataOnDisk() throws {
        let store = unlockedStore()
        var original = try rawEntry("alice")
        original.set("future_extension", .object(["version": .int(2), "opaque": .null]))
        XCTAssertTrue(store.add(original))
        XCTAssertTrue(store.update(at: 0, with: try rawEntry("changed")))
        store.lock()
        let reopened = makeStore()
        reopened.unlock(password: "correct horse")
        XCTAssertTrue(reopened.isUnlocked)
        XCTAssertEqual("changed", reopened.entries.first?.label)
        XCTAssertEqual(original.fields["future_extension"], reopened.entries.first?.fields["future_extension"])
        reopened.lock()
    }

    @MainActor
    func testBackupSaveRevisionOnlyAdvancesAfterSuccessfulPersistence() throws {
        let store = unlockedStore()
        let initial = store.persistedRevision
        XCTAssertTrue(store.add(try rawEntry("alice")))
        XCTAssertEqual(initial + 1, store.persistedRevision)
        XCTAssertFalse(store.add(try rawEntry("alice")))
        XCTAssertEqual(initial + 1, store.persistedRevision)
        store.setHideIssuer(!store.hideIssuer)
        XCTAssertEqual(initial + 1, store.persistedRevision)
        store.lock()
        let lockedRevision = store.persistedRevision
        XCTAssertFalse(store.save())
        XCTAssertEqual(lockedRevision, store.persistedRevision)
    }

    @MainActor
    func testAutomaticBackupConfigurationRejectsInvalidInputsAndStaleSessions() throws {
        let store = unlockedStore()
        let adapter = vaultAdapter(store)
        XCTAssertNil(adapter.action(key: "automaticBackup"))
        adapter.change(key: "automaticPassword", value: "backup-secret")
        adapter.change(key: "automaticConfirmation", value: "different")
        XCTAssertNotNil(adapter.action(key: "automaticEnable"))
        adapter.change(key: "automaticMinutes", value: "0")
        XCTAssertNotNil(adapter.action(key: "automaticEnable"))
        store.lock()
        XCTAssertNotNil(adapter.action(key: "automaticEnable"))
        adapter.invalidate()
    }

    @MainActor
    func testHardwareSeedCopyRequiresSelectionFreshEntryAndExplicitConsent() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try rawEntry("alice")))
        let adapter = vaultAdapter(store)
        XCTAssertNotNil(adapter.action(key: "hardwareSeedNFC"))
        XCTAssertNil(adapter.action(key: "hardwareSeedCopy"))
        XCTAssertNotNil(adapter.action(key: "hardwareSeedSelect:-1"))
        XCTAssertNotNil(adapter.action(key: "hardwareSeedSelect:1"))
        XCTAssertNil(adapter.action(key: "hardwareSeedSelect:0"))
        XCTAssertNotNil(adapter.action(key: "hardwareSeedNFC")) // No consent: must not open hardware.
        XCTAssertNotNil(adapter.action(key: "hardwareSeedUSB"))
        adapter.change(key: "hardwareSeedConsent", value: "true")
        XCTAssertTrue(store.update(at: 0, with: try rawEntry("changed")))
        XCTAssertNotNil(adapter.action(key: "hardwareSeedNFC")) // Stale selection cannot export a changed seed.
        XCTAssertNil(adapter.action(key: "dismiss"))
        XCTAssertNotNil(adapter.action(key: "hardwareSeedUSB"))
        adapter.invalidate()
    }

    @MainActor
    func testBackgroundRejectsHardwareExportAndDeferredBackupActionsWithoutLockingVault() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try rawEntry("alice")))
        let adapter = vaultAdapter(store)
        XCTAssertNil(adapter.action(key: "hardwareSeedCopy"))
        XCTAssertNil(adapter.action(key: "hardwareSeedSelect:0"))
        adapter.change(key: "hardwareSeedConsent", value: "true")
        NotificationCenter.default.post(name: UIScene.didEnterBackgroundNotification, object: nil)
        XCTAssertTrue(store.isUnlocked)
        XCTAssertNotNil(adapter.action(key: "hardwareSeedNFC"))
        XCTAssertNotNil(adapter.action(key: "automaticEnable"))
        adapter.invalidate()
    }

    @MainActor
    func testHardwareSeedCopyCannotSurviveCloseOrLock() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try rawEntry("alice")))
        let adapter = vaultAdapter(store)
        XCTAssertNil(adapter.action(key: "hardwareSeedCopy"))
        XCTAssertNil(adapter.action(key: "hardwareSeedSelect:0"))
        adapter.change(key: "hardwareSeedConsent", value: "true")
        XCTAssertNil(adapter.action(key: "dismiss"))
        XCTAssertNil(adapter.action(key: "hardwareSeedCopy"))
        XCTAssertNotNil(adapter.action(key: "hardwareSeedNFC")) // Reopening cleared selection/consent.
        store.lock()
        XCTAssertNotNil(adapter.action(key: "hardwareSeedUSB"))
        adapter.invalidate()
    }

    @MainActor
    func testHardwareCalculationCannotUseAnUnlistedAccountOrExpiredCode() throws {
        let store = unlockedStore()
        let adapter = vaultAdapter(store)
        XCTAssertNil(adapter.action(key: "hardwareOath"))
        XCTAssertNotNil(adapter.action(key: "hardwareCalculate:0"))
        XCTAssertNotNil(adapter.action(key: "hardwareCalculate:-1"))
        XCTAssertNotNil(adapter.action(key: "hardwareCopy"))
        XCTAssertNil(adapter.action(key: "dismiss"))
        XCTAssertNotNil(adapter.action(key: "hardwareCalculate:0"))
        adapter.invalidate()
    }

    @MainActor
    func testHardwareMutationsRequireListedAccountAndExplicitCurrentConfirmation() throws {
        let store = unlockedStore()
        let adapter = vaultAdapter(store)
        XCTAssertNil(adapter.action(key: "hardwareOath"))
        for command in ["hardwareDeleteSelect:0", "hardwareDeleteSelect:-1", "hardwareRenameSelect:0", "hardwareRenameSelect:-1",
                        "hardwareDeleteConfirm", "hardwareRenameConfirm"] {
            XCTAssertNotNil(adapter.action(key: command), command)
        }
        // Forging draft/index/confirmation cannot create a native account identity.
        adapter.change(key: "hardwareDeleteIndex", value: "0")
        adapter.change(key: "hardwareDeleteConfirmation", value: "DELETE")
        adapter.change(key: "hardwareRenameIndex", value: "0")
        adapter.change(key: "hardwareRenameConfirmation", value: "RENAME")
        adapter.change(key: "hardwareRenameName", value: "new")
        XCTAssertNotNil(adapter.action(key: "hardwareDeleteConfirm"))
        XCTAssertNotNil(adapter.action(key: "hardwareRenameConfirm"))
        XCTAssertNil(adapter.action(key: "dismiss"))
        XCTAssertNotNil(adapter.action(key: "hardwareDeleteConfirm"))
        XCTAssertNotNil(adapter.action(key: "hardwareRenameConfirm"))
        store.lock()
        XCTAssertNotNil(adapter.action(key: "hardwareDeleteConfirm"))
        XCTAssertNotNil(adapter.action(key: "hardwareRenameConfirm"))
        adapter.invalidate()
    }

    @MainActor
    func testComposePreferencesImportPlansWithoutApplyingUntilConfirmed() throws {
        let store = unlockedStore()
        store.setInactivityLockSeconds(60)
        let exported = try PreferencesExport.export(store.preferencesPort)
        store.setInactivityLockSeconds(30)
        let adapter = vaultAdapter(store)
        // Planning validates the document without touching any setting.
        adapter.imported(data: exported.bytes, kind: "preferences")
        XCTAssertEqual(store.inactivityLockSeconds, 30)
        XCTAssertNil(adapter.action(key: "confirm"))
        XCTAssertEqual(store.inactivityLockSeconds, 60)
        adapter.invalidate()
    }

    @MainActor
    func testComposePreferencesImportDiskFailureDoesNotPublishAndCanRetry() throws {
        let store = unlockedStore()
        store.setInactivityLockSeconds(60)
        let exported = try PreferencesExport.export(store.preferencesPort)
        store.setInactivityLockSeconds(30)
        let adapter = vaultAdapter(store)
        defer { adapter.invalidate() }
        adapter.imported(data: exported.bytes, kind: "preferences")
        let file = directory.appendingPathComponent(VaultStore.settingsFilename)
        let original = try Data(contentsOf: file)
        try FileManager.default.removeItem(at: file)
        try FileManager.default.createDirectory(at: file, withIntermediateDirectories: false)
        defer {
            try? FileManager.default.removeItem(at: file)
            try? original.write(to: file, options: [.atomic])
        }
        XCTAssertTrue(adapter.action(key: "confirm")?.contains("could not be saved") == true)
        XCTAssertEqual(30, store.inactivityLockSeconds)
        XCTAssertEqual(.integer(30), store.preferencesPort.read(PreferencesExport.inactivityLockKey))
        try FileManager.default.removeItem(at: file)
        try original.write(to: file, options: [.atomic])
        XCTAssertNil(adapter.action(key: "confirm"))
        XCTAssertEqual(60, store.inactivityLockSeconds)
        XCTAssertEqual(60, VaultStore(directory: directory).inactivityLockSeconds)
    }

    @MainActor
    func testComposePreferencesImportRefusesForeignFileAndStaleSession() throws {
        let store = unlockedStore()
        let foreign = try PreferencesExport.export(store.preferencesPort, platform: "desktop")
        let refused = vaultAdapter(store)
        refused.imported(data: foreign.bytes, kind: "preferences")
        XCTAssertNotNil(refused.action(key: "confirm"))
        refused.invalidate()
        let exported = try PreferencesExport.export(store.preferencesPort)
        let stale = vaultAdapter(store)
        stale.imported(data: exported.bytes, kind: "preferences")
        store.lock()
        store.unlock(password: "correct horse")
        XCTAssertNotNil(stale.action(key: "confirm"))
        stale.invalidate()
    }

    @MainActor
    func testComposeDeleteConfirmationRemovesOnlyTheCurrentEntry() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try draft("alice")))
        XCTAssertTrue(store.add(try draft("bob")))
        store.toggleReveal(at: 0)
        let adapter = vaultAdapter(store)
        XCTAssertNil(adapter.action(key: "delete:0"))
        XCTAssertNil(adapter.action(key: "confirm"))
        XCTAssertEqual(store.entries.map(\.label), ["bob"])
        adapter.invalidate()
    }

    @MainActor
    func testComposeDeleteConfirmationRejectsStaleAndClosedSessions() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try draft("alice")))
        store.toggleReveal(at: 0)
        let stale = vaultAdapter(store)
        XCTAssertNil(stale.action(key: "delete:0"))
        store.lock()
        store.unlock(password: "correct horse")
        XCTAssertNotNil(stale.action(key: "confirm"))
        XCTAssertEqual(store.entries.count, 1)
        stale.invalidate()
        let closed = vaultAdapter(store)
        XCTAssertNil(closed.action(key: "delete:0"))
        closed.invalidate()
        XCTAssertNotNil(closed.action(key: "confirm"))
        XCTAssertEqual(store.entries.count, 1)
    }

    func testManualReorderTagsAndCredentialInterchange() throws {
        struct EchoOpenPgpPort: OpenPgpPort {
            func encrypt(plaintext: Data, recipientKeyBlocks: [Data]) throws -> Data {
                Data("PGP:".utf8) + plaintext
            }
            func decrypt(message: Data, secretKeyBlock: Data, passphrase: String?) throws -> Data {
                guard message.starts(with: Data("PGP:".utf8)) else {
                    throw OpenPgpError.decryptionFailed(reason: "bad header")
                }
                return message.dropFirst(4)
            }
        }

        let store = VaultStore(directory: directory, openPgp: EchoOpenPgpPort())
        store.unlock(password: "correct horse", confirmation: "correct horse", creating: true)
        XCTAssertTrue(store.add(try draft("first", issuer: "Alpha")))
        XCTAssertTrue(store.add(try draft("second", issuer: "Beta")))
        XCTAssertTrue(store.canReorder)
        XCTAssertTrue(store.moveDisplayed(from: 0, to: 1))
        XCTAssertEqual(store.entries.map(\.label), ["second", "first"])

        XCTAssertTrue(store.setTags(at: 0, tags: ["ops", "prod"]))
        XCTAssertEqual(store.entries[0].tags, ["ops", "prod"])

        let csv = Data("name,url,username,password\r\nGitLab,gitlab.example.com,alice,pw123\r\n".utf8)
        XCTAssertNotNil(store.importBrowserPasswords(filename: "logins.csv", data: csv, mode: .individual))
        XCTAssertEqual(store.category, .passwords)

        let mail = try EmailMaterial.create(
            name: "Ops Mail",
            address: "ops@example.com",
            incomingHost: "imap.example.com",
            incomingPort: 993,
            incomingEncryption: 0,
            incomingProtocol: 0,
            incomingUser: "ops@example.com",
            incomingPassword: "in-pw",
            outgoingHost: "smtp.example.com",
            outgoingPort: 465,
            outgoingEncryption: 0,
            outgoingUser: "ops@example.com",
            outgoingPassword: "out-pw"
        )
        XCTAssertTrue(store.addEmailAccount(mail))
        XCTAssertNotNil(store.exportFairEmailBackup())
        XCTAssertNotNil(store.exportThunderbirdBackup())

        let pgpExport = try XCTUnwrap(store.exportOpenPgpBackup(recipientKeyBlocks: [Data("pub".utf8)]))
        XCTAssertTrue(pgpExport.name.hasSuffix(".json.gpg"))
        let restored = try XCTUnwrap(store.restoreOpenPgpBackup(
            message: pgpExport.data,
            secretKeyBlock: Data("sec".utf8),
            passphrase: nil,
            replace: true
        ))
        XCTAssertEqual(restored.entries.count, store.entries.count)
    }

    @MainActor
    func testSessionRevealedPasswordsExportAndArtworkAndThunderbirdConfirmWork() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try draft("ops-totp", issuer: "Internal Corp")))
        XCTAssertTrue(store.setThumbnail(at: 0, name: "GitHub"))
        XCTAssertEqual(store.entries[0].thumbnail, "GitHub")
        XCTAssertTrue(store.setThumbnail(at: 0, name: ""))
        XCTAssertEqual(store.entries[0].thumbnail, "Default")

        let csv = Data("name,url,username,password\r\nGitLab,gitlab.example.com,alice,pw123\r\n".utf8)
        XCTAssertNotNil(store.importBrowserPasswords(filename: "logins.csv", data: csv, mode: .individual))
        let pwIndex = try XCTUnwrap(store.entries.firstIndex(where: { $0.kind == .password }))
        XCTAssertNil(store.exportBrowserPasswordsCsv())
        store.toggleReveal(at: pwIndex)
        let revealedCsv = try XCTUnwrap(store.exportBrowserPasswordsCsv())
        XCTAssertTrue(String(decoding: revealedCsv.data, as: UTF8.self).contains("pw123"))

        let plan = store.passwordExportPlan()
        XCTAssertEqual(plan.groups.count, 1)
        XCTAssertEqual(plan.groups[0].dialect, .chrome)
        XCTAssertNotNil(store.exportBrowserPasswordsCsvGroup(indices: plan.groups[0].indices, dialect: plan.groups[0].dialect))

        let tbCsv = Data("url,username,password\r\nimap://mail.example.com,ops@example.com,imap-secret\r\n".utf8)
        XCTAssertEqual(store.previewThunderbirdBackupCount(data: tbCsv), 1)
        let adapter = vaultAdapter(store)
        adapter.imported(data: tbCsv, kind: "thunderbird")
        XCTAssertFalse(store.entries.contains(where: { $0.kind == .email }))
        XCTAssertNil(adapter.action(key: "confirm"))
        XCTAssertTrue(store.entries.contains(where: { $0.kind == .email }))
        adapter.invalidate()
    }

    @MainActor
    func testAtomicAutoLockSavePreservesPreviousPolicyOnDiskFailureAndReopensTogether() throws {
        let store = unlockedStore()
        try store.saveAutoLockSettings(seconds: 45, relockOnBackground: true)
        XCTAssertEqual(45, store.inactivityLockSeconds); XCTAssertTrue(store.relockOnBackground)
        let file = directory.appendingPathComponent(VaultStore.settingsFilename)
        let original = try Data(contentsOf: file)
        try FileManager.default.removeItem(at: file)
        try FileManager.default.createDirectory(at: file, withIntermediateDirectories: false)
        defer {
            try? FileManager.default.removeItem(at: file)
            try? original.write(to: file, options: [.atomic])
        }
        XCTAssertThrowsError(try store.saveAutoLockSettings(seconds: 0, relockOnBackground: false))
        XCTAssertEqual(45, store.inactivityLockSeconds); XCTAssertTrue(store.relockOnBackground)
        try FileManager.default.removeItem(at: file)
        try original.write(to: file, options: [.atomic])
        let reopened = VaultStore(directory: directory)
        XCTAssertEqual(45, reopened.inactivityLockSeconds); XCTAssertTrue(reopened.relockOnBackground)
        XCTAssertThrowsError(try store.saveAutoLockSettings(seconds: -1, relockOnBackground: false))
    }

    @MainActor
    func testCardDisplayBatchRejectsInvalidFieldsWithoutPartialEditsAndReopens() throws {
        let store = unlockedStore()
        let before = try Data(contentsOf: directory.appendingPathComponent(VaultStore.settingsFilename))
        XCTAssertThrowsError(try store.saveCardDisplaySettings(["layout": "FULL", "labelSizeSp": "99"]))
        XCTAssertEqual("DEFAULT", store.cardLayout)
        XCTAssertEqual(before, try Data(contentsOf: directory.appendingPathComponent(VaultStore.settingsFilename)))
        XCTAssertThrowsError(try store.saveCardDisplaySettings(["layout": "FULL", "hideIssuer": "yes"]))
        XCTAssertEqual("DEFAULT", store.cardLayout)
        try store.saveCardDisplaySettings(["layout": "COMPACT", "hideIssuer": "true", "labelSizeSp": "20",
            "thumbnailSizeDp": "48", "tapSingle": "COPY", "tapToRevealTimeoutSeconds": "60", "tokenGroupSize": "2"])
        XCTAssertEqual("COMPACT", store.cardLayout)
        XCTAssertTrue(store.hideIssuer)
        let reopened = VaultStore(directory: directory)
        XCTAssertEqual("COMPACT", reopened.cardLayout)
        XCTAssertEqual(20, reopened.labelSizeSp)
        XCTAssertEqual(48, reopened.thumbnailSizeDp)
        XCTAssertEqual("COPY", reopened.tapSingle)
        XCTAssertEqual(60, reopened.tapToRevealTimeoutSeconds)
        XCTAssertEqual(2, reopened.tokenGroupSize)
    }

    @MainActor
    func testComposeCardDisplaySaveFailureDoesNotChangeLiveOrCachedSettingsAndRetrySucceeds() throws {
        let store = unlockedStore(); let adapter = vaultAdapter(store)
        defer { adapter.invalidate() }
        XCTAssertNil(adapter.action(key: "cardLayout"))
        adapter.change(key: "layout", value: "FULL")
        adapter.change(key: "hideIssuer", value: "true")
        let file = directory.appendingPathComponent(VaultStore.settingsFilename)
        let original = try Data(contentsOf: file)
        try FileManager.default.removeItem(at: file)
        try FileManager.default.createDirectory(at: file, withIntermediateDirectories: false)
        defer {
            try? FileManager.default.removeItem(at: file)
            try? original.write(to: file, options: [.atomic])
        }
        XCTAssertTrue(adapter.action(key: "confirm")?.contains("could not be saved") == true)
        XCTAssertEqual("DEFAULT", store.cardLayout); XCTAssertFalse(store.hideIssuer)
        try FileManager.default.removeItem(at: file)
        try original.write(to: file, options: [.atomic])
        XCTAssertNil(adapter.action(key: "confirm"))
        XCTAssertEqual("FULL", store.cardLayout); XCTAssertTrue(store.hideIssuer)
    }

    @MainActor
    func testDisplayPreferencesSearchFieldsAndTagRulePersistAndRoundTrip() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try draft("ops-label", issuer: "AcmeIssuer")))
        store.toggleReveal(at: 0)

        let adapter = vaultAdapter(store)
        XCTAssertNil(adapter.action(key: "cardLayout"))
        adapter.change(key: "layout", value: "COMPACT")
        adapter.change(key: "hideIssuer", value: "true")
        adapter.change(key: "showPreviousToken", value: "false")
        adapter.change(key: "showIndividualTimeouts", value: "false")
        adapter.change(key: "tokenGroupSize", value: "2")
        XCTAssertNil(adapter.action(key: "confirm"))
        XCTAssertEqual(store.cardLayout, "COMPACT")
        XCTAssertTrue(store.hideIssuer)
        XCTAssertFalse(store.showPreviousToken)
        XCTAssertFalse(store.showIndividualTimeouts)
        XCTAssertEqual(store.tokenGroupSize, 2)

        XCTAssertNil(adapter.action(key: "searchIncludes"))
        adapter.change(key: "selectedIndices", value: "LABEL,TAGS")
        XCTAssertNil(adapter.action(key: "confirm"))
        XCTAssertEqual(store.searchIncludes, [.label, .tags])
        store.query = "AcmeIssuer"
        XCTAssertTrue(store.displayedIndices.isEmpty)
        store.query = "ops-label"
        XCTAssertEqual(store.displayedIndices.count, 1)

        adapter.tool(action: "tagRule")
        XCTAssertEqual(store.tagFunctionality, .and)

        let exported = try PreferencesExport.export(store.preferencesPort)
        store.setCardLayout("DEFAULT")
        store.setHideIssuer(false)
        store.setShowPreviousToken(true)
        store.setShowIndividualTimeouts(true)
        store.setTokenGroupSize(3)
        store.setSearchIncludes(Set(SearchInclude.allCases))
        store.setTagFunctionality(.or)

        adapter.imported(data: exported.bytes, kind: "preferences")
        XCTAssertNil(adapter.action(key: "confirm"))
        XCTAssertEqual(store.cardLayout, "COMPACT")
        XCTAssertTrue(store.hideIssuer)
        XCTAssertFalse(store.showPreviousToken)
        XCTAssertFalse(store.showIndividualTimeouts)
        XCTAssertEqual(store.tokenGroupSize, 2)
        XCTAssertEqual(store.searchIncludes, [.label, .tags])
        XCTAssertEqual(store.tagFunctionality, .and)
        adapter.invalidate()
    }

    @MainActor
    func testEditorKeepsStoredSecretWhenBlankAndGuardsTypeChangesAndEmptyLinScpExport() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try draft("alice@example.com", issuer: "GitHub")))
        let originalSecret = store.entries[0].secretBase32
        store.toggleReveal(at: 0)

        let adapter = vaultAdapter(store)
        defer { adapter.invalidate() }

        XCTAssertEqual(adapter.action(key: "linScpExport"),
                       "There are no SSH keys or password entries in this vault.")

        adapter.card(index: 0, action: "edit")
        adapter.change(key: "issuer", value: "GitHub Enterprise")
        adapter.change(key: "secret", value: "")
        XCTAssertNil(adapter.action(key: "confirm"))
        XCTAssertEqual(store.entries[0].issuer, "GitHub Enterprise")
        XCTAssertEqual(store.entries[0].secretBase32, originalSecret)

        adapter.card(index: 0, action: "edit")
        adapter.change(key: "type", value: "HOTP")
        adapter.change(key: "secret", value: "")
        XCTAssertEqual(adapter.action(key: "confirm"),
                       "Re-enter the secret when changing OTP type.")
        adapter.change(key: "secret", value: "JBSWY3DPEHPK3PXP")
        XCTAssertNil(adapter.action(key: "confirm"))
        XCTAssertEqual(store.entries[0].kind, .hotp)

        adapter.card(index: 0, action: "counter")
        adapter.change(key: "counter", value: "9")
        XCTAssertNil(adapter.action(key: "confirm"))
        XCTAssertEqual(store.entries[0].counter, 9)

        adapter.importedQrImagePayload("otpauth://totp/GitHub%20Enterprise:bob@example.com?secret=KRSXG5A=&issuer=GitHub%20Enterprise")
        XCTAssertNotNil(adapter.action(key: "qrImageImport"))
        adapter.change(key: "allowAdditional", value: "true")
        XCTAssertNil(adapter.action(key: "qrImageImport"))
        XCTAssertEqual(store.entries.count, 2)

        let emptyStore = unlockedStore()
        let emptyExport = try XCTUnwrap(emptyStore.exportBackup(password: "backup-pass"))
        XCTAssertNil(store.previewEncryptedBackupCount(encrypted: emptyExport.data, password: "backup-pass"))
        XCTAssertEqual(store.message, BackupWording.emptyBackup)
    }
}
