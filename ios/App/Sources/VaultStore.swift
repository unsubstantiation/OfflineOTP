import Combine
import Foundation
import OfflineOTPKit

/// The app's view of one vault: locked or unlocked, what the list shows, and the mutations a person
/// can perform — all fail-closed and all persisted before they are published.
///
/// This is the only place in the app that touches the vault files. It is a plain `ObservableObject`
/// driven from the main actor by SwiftUI actions, and it is used from the app's unit tests the same
/// way, so the wiring — where the files live, what a wrong password says, what a save does, what a
/// reveal or a counter step writes — is covered without a UI. Every rule the other clients apply
/// (tabs, search, tag filter, the five orders, usage stamps, the counter ceiling) comes from
/// `OfflineOTPKit`, which mirrors `:core`; this class only sequences it.
public final class VaultStore: ObservableObject {
    /// Where the vault, its `.bck` copy and the settings live: a `data` folder inside the app's
    /// Documents directory, mirroring the desktop client's portable folder and Android's files.
    public static func defaultDirectory(fileManager: FileManager = .default) -> URL {
        let base = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? fileManager.temporaryDirectory
        return base.appendingPathComponent("data", isDirectory: true)
    }

    /// The filenames the other clients use, so a folder can be inspected with the same expectations.
    public static let vaultFilename = "secrets.dat"
    public static let backupFilename = "secrets.dat.bck"
    public static let settingsFilename = "vault.properties.plist"

    @Published public private(set) var isUnlocked = false
    @Published public private(set) var hasVault = false
    @Published public private(set) var entries: [Entry] = []
    /// A sentence for the lock screen or the list: a wrong password, a missing vault, a failed write.
    @Published public private(set) var message: String?

    // MARK: - What the list shows

    /// The selected tab. Every entry has one, including the kinds this build cannot display, so the
    /// tab strip never hides vault data.
    @Published public var category: EntryCategory = .otp
    /// The live search. It survives a tab, tag or sort change, as it does on both other clients.
    @Published public var query: String = ""
    @Published public var selectedTags: [String] = []
    @Published public var includeUntagged = false
    @Published public var tagFunctionality: TagFunctionality = .or
    @Published public private(set) var sortMode: SortMode = .unsorted

    /// The fields the search covers. The desktop client always searches all three, and so does this
    /// one; Android's narrower preference lives in its own settings, which neither client reads.
    public let searchIncludes: Set<SearchInclude> = Set(SearchInclude.allCases)

    /// Whether this device can offer the quick unlock, and which sensor it would use. It changes when
    /// a face is enrolled or removed, so it is asked again whenever the lock screen appears.
    @Published public private(set) var biometry: BiometryAvailability = .unavailable(.other)
    /// Whether the person turned the quick unlock on for this vault: a client preference stored beside
    /// the vault, never inside `secrets.dat`.
    @Published public private(set) var biometricUnlockEnabled = false

    /// The client preference key, next to `ui_sort_mode` in the settings file.
    public static let biometricSettingKey = "biometric_unlock"
    static let biometricEnabled = "enabled"
    static let biometricDisabled = "disabled"

    private let vault: PasswordVault
    private let settings: PropertyListSettings
    private let directory: URL
    private let biometrics: BiometricKeyStore
    private var key: [UInt8] = []

    /// How many times this store has been unlocked. A Google Authenticator export can span several QR
    /// codes, and the codes are only allowed to complete an export if they all arrive inside one
    /// unlocked session — the `VaultSession.generation()` rule Android applies.
    @Published public private(set) var generation = 0

    /// The export being collected, if one is open.
    private var migration: MigrationBatchCollector?

    /// `directory` is injectable so the app tests can use a temporary folder instead of Documents,
    /// and `biometrics` so they can drive the quick unlock without a sensor.
    public init(directory: URL = VaultStore.defaultDirectory(),
                biometrics: BiometricKeyStore = KeychainBiometricKeyStore()) {
        self.directory = directory
        self.biometrics = biometrics
        let vaultFile = FileByteStore(url: directory.appendingPathComponent(VaultStore.vaultFilename))
        let backupFile = FileByteStore(
            url: directory.appendingPathComponent(VaultStore.backupFilename))
        let settings = PropertyListSettings(
            url: directory.appendingPathComponent(VaultStore.settingsFilename))
        self.settings = settings
        self.vault = PasswordVault(vaultFile: vaultFile, backupFile: backupFile, settings: settings)
        self.hasVault = vault.isInitialized
        // A client preference, not vault data: it is written beside the vault, under the same key the
        // desktop client uses, and an unreadable value falls back to the vault order.
        self.sortMode = SortMode.stored(settings.string(SortMode.settingsKey))
        // Both are read before the first unlock: the lock screen has to know whether to offer the
        // quick unlock.
        self.biometricUnlockEnabled =
            settings.string(VaultStore.biometricSettingKey) == VaultStore.biometricEnabled
        self.biometry = biometrics.availability()
    }

    // MARK: - Unlocking

    /// Opens the existing vault, or provisions a new one when `creating` is set.
    public func unlock(password: String, confirmation: String = "", creating: Bool = false) {
        message = nil
        if creating {
            create(password: password, confirmation: confirmation)
            return
        }
        do {
            let unlocked = try vault.open(password: password)
            key = unlocked.key
            entries = unlocked.entries
            isUnlocked = true
            beginSession()
        } catch VaultOpenError.authenticationFailed {
            message = "That password does not open this vault."
        } catch VaultOpenError.missing {
            hasVault = false
            message = "There is no vault in this folder yet."
        } catch VaultOpenError.corrupt {
            message = "The vault file cannot be read. Restore a backup or start over."
        } catch {
            message = "The vault could not be opened."
        }
    }

    private func create(password: String, confirmation: String) {
        // The same rules the other two clients apply when a vault is created: a minimum length and a
        // matching confirmation. Enforcing them here means the failure is explained, not a silent
        // refusal by the engine.
        guard password.count >= InputPolicy.minVaultPasswordChars else {
            message = "Use at least \(InputPolicy.minVaultPasswordChars) characters."
            return
        }
        guard password == confirmation else {
            message = "The two passwords do not match."
            return
        }
        do {
            try vault.create(password: password)
            let unlocked = try vault.open(password: password)
            key = unlocked.key
            entries = unlocked.entries
            hasVault = true
            isUnlocked = true
            beginSession()
        } catch VaultWriteError.alreadyExists {
            hasVault = true
            message = "A vault already exists in this folder."
        } catch {
            message = "The vault could not be created."
        }
    }

    /// Forgets the key and the entries; the files stay where they are. The tab, search and order are
    /// left as they were, so unlocking again shows what was on screen (Android keeps its sort
    /// setting across a lock too).
    public func lock() {
        if isUnlocked, !key.isEmpty {
            save()
        }
        key = []
        entries = []
        isUnlocked = false
        message = nil
        // The export, if one was open, belonged to the session that just ended.
        migration?.cancel()
        migration = nil
        generation += 1
        // The lock screen is about to ask whether it can offer the quick unlock; the answer can have
        // changed while the vault was open (a face enrolled in Settings, for instance).
        refreshBiometry()
    }

    // MARK: - The quick unlock

    /// Asks the device again what it can do. Cheap, and the only way to notice a face being enrolled
    /// or removed while the app is running.
    public func refreshBiometry() {
        biometry = biometrics.availability()
    }

    /// Clears the sentence the last action left behind, when the person moves on from it.
    public func clearMessage() {
        message = nil
    }

    /// The sensor named in the interface, e.g. "Face ID".
    public var biometryTitle: String { biometry.kind.title }

    /// True when the lock screen should offer the button: turned on here, and the device still able.
    public var canUnlockWithBiometrics: Bool {
        biometricUnlockEnabled && biometry.isAvailable
    }

    /// Unlocks with the key this device keeps behind Face ID / Touch ID.
    ///
    /// The vault file is untouched by this: the key it returns is the same one the password derives,
    /// and it is the GCM tag that decides whether it opens the file. A key that no longer fits — the
    /// vault was re-created, or the item was dropped when the enrolled face changed — is removed
    /// rather than left to fail again, and the person is told to use the password.
    public func unlockWithBiometrics() {
        guard !isUnlocked, biometricUnlockEnabled else { return }
        refreshBiometry()
        guard biometry.isAvailable else {
            message = biometry.refusal.isEmpty ? BiometricWording.unavailable : biometry.refusal
            return
        }
        guard let account = vault.vaultIdentifier else {
            disableBiometricUnlock(announcing: false)
            message = BiometricWording.stale
            return
        }
        message = nil
        biometrics.load(account: account, reason: BiometricWording.reason) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let sessionKey):
                self.finishBiometricUnlock(sessionKey)
            case .failure(let error):
                if error == .missing { self.disableBiometricUnlock(announcing: false) }
                self.message = error.message
            }
        }
    }

    private func finishBiometricUnlock(_ sessionKey: [UInt8]) {
        guard !isUnlocked else { return }
        do {
            let unlocked = try vault.openWithKey(sessionKey)
            key = unlocked.key
            entries = unlocked.entries
            isUnlocked = true
            beginSession()
            message = nil
        } catch {
            disableBiometricUnlock(announcing: false)
            message = BiometricWording.stale
        }
    }

    /// Turns the quick unlock on. The vault password is asked for even though the vault is open: an
    /// unlocked phone must not be enough to add a face that keeps access after it is locked again.
    @discardableResult
    public func enableBiometricUnlock(password: String) -> Bool {
        guard isUnlocked else { return false }
        refreshBiometry()
        guard biometry.isAvailable else {
            message = biometry.refusal.isEmpty ? BiometricWording.unavailable : biometry.refusal
            return false
        }
        let verified: PasswordVault.Unlocked
        do {
            verified = try vault.open(password: password)
        } catch {
            message = BiometricWording.wrongPassword
            return false
        }
        // The key that just verified has to be the key this session holds, or the stored key would
        // open a different file than the one on screen.
        guard verified.key == key, let account = vault.vaultIdentifier else {
            message = BiometricWording.saveFailed
            return false
        }
        do {
            try biometrics.save(key: verified.key, account: account)
        } catch {
            message = BiometricWording.saveFailed
            return false
        }
        settings.setString(VaultStore.biometricSettingKey, VaultStore.biometricEnabled)
        biometricUnlockEnabled = true
        message = BiometricWording.turnedOn
        return true
    }

    /// Turns it off and drops the key. Turning it off only costs convenience, so it asks nothing.
    public func disableBiometricUnlock() {
        disableBiometricUnlock(announcing: true)
    }

    private func disableBiometricUnlock(announcing: Bool) {
        if let account = vault.vaultIdentifier { biometrics.remove(account: account) }
        settings.setString(VaultStore.biometricSettingKey, VaultStore.biometricDisabled)
        biometricUnlockEnabled = false
        if announcing { message = BiometricWording.turnedOff }
    }

    /// Starts a session: everything that was true of the previous unlock stops being true.
    private func beginSession() {
        generation += 1
        migration?.cancel()
        migration = nil
    }

    // MARK: - The list

    /// The query the current tab, search, tag filter and order make up.
    public var listQuery: EntryListQuery {
        EntryListQuery(category: category, text: query, includes: searchIncludes,
                       selectedTags: selectedTags, includeUntagged: includeUntagged,
                       tagFunctionality: tagFunctionality, sortMode: sortMode)
    }

    /// Source-list indices of the entries on screen, in display order. Indices, not copies, because a
    /// delete or an edit needs the vault's own position and the projection must own nothing.
    public var visibleIndices: [Int] {
        EntryListProjection.visibleIndices(entries, query: listQuery)
    }

    /// Every tag in use, in the order both other clients list them.
    public var allTags: [String] {
        EntryTagFilter.sortedNames(entries.flatMap { $0.tags })
    }

    /// How many entries on the current tab carry each tag; the chip shows this.
    public func tagCounts(for category: EntryCategory) -> [String: Int] {
        var counts: [String: Int] = [:]
        for entry in entries where EntryCategory.of(entry) == category {
            for tag in Set(entry.tags) { counts[tag, default: 0] += 1 }
        }
        return counts
    }

    /// Tags offered on the current tab, so a chip can never filter to an empty list by accident.
    public func tags(for category: EntryCategory) -> [String] {
        let counts = tagCounts(for: category)
        return EntryTagFilter.sortedNames(Array(counts.keys))
    }

    /// How many entries the current tab holds in total, before search and tag filtering.
    public func entryCount(for category: EntryCategory) -> Int {
        entries.filter { EntryCategory.of($0) == category }.count
    }

    public func setSortMode(_ mode: SortMode) {
        sortMode = mode
        // A write failure only loses the preference; the order still applies to this session.
        settings.setString(SortMode.settingsKey, mode.rawValue)
    }

    /// Selects a tag, keeping a live search and the chosen order — Android's drawer and the desktop
    /// dropdown both keep them.
    public func toggleTag(_ tag: String) {
        switch tagFunctionality {
        case .single:
            selectedTags = selectedTags == [tag] ? [] : [tag]
        case .or, .and:
            if let position = selectedTags.firstIndex(of: tag) {
                selectedTags.remove(at: position)
            } else {
                selectedTags.append(tag)
            }
        }
    }

    /// Switches how tags combine. `single` collapses the selection to one tag, as Android's
    /// single-tag mode does.
    public func setTagFunctionality(_ functionality: TagFunctionality) {
        tagFunctionality = functionality
        if functionality == .single, selectedTags.count > 1 {
            selectedTags = Array(selectedTags.prefix(1))
        }
    }

    /// Drops the search and the tag filter, leaving the tab and the order alone.
    public func clearFilters() {
        query = ""
        selectedTags = []
        includeUntagged = false
    }

    // MARK: - Codes

    /// The code to show for an entry right now, or nil when it has none (a note, an SSH key) or when
    /// the entry is concealed. A concealed entry shows nothing — and cannot be copied — until it is
    /// revealed, exactly as the other two clients gate the copy button on `details_visible`.
    public func code(for entry: Entry, at date: Date) -> String? {
        guard entry.detailsVisible else { return nil }
        do {
            return try entry.code(unixTime: Int64(date.timeIntervalSince1970))
        } catch {
            return nil
        }
    }

    /// Records one use of the entry at `index`: the stamp and counter **Last used** and
    /// **Most used** read. Called when a code is copied or a counter stepped, as both other clients
    /// do.
    @discardableResult
    public func recordUse(at index: Int, now: Date = Date()) -> Bool {
        guard entries.indices.contains(index) else { return false }
        var proposed = entries
        EntryUsage.recordUse(&proposed[index], now: EntryUsage.nowMillis(now))
        return commit(proposed)
    }

    /// Advances an HOTP entry by one step and persists the new counter.
    @discardableResult
    public func advanceCounter(at index: Int, now: Date = Date()) -> Bool {
        guard entries.indices.contains(index) else { return false }
        var proposed = entries
        guard EntryUsage.advanceHotp(&proposed[index]) else { return false }
        EntryUsage.recordUse(&proposed[index], now: EntryUsage.nowMillis(now))
        return commit(proposed)
    }

    // MARK: - Entries

    /// Persists the entries. Used after a counter change and on lock; a failed save keeps the
    /// in-memory list, so the next attempt can still succeed.
    @discardableResult
    public func save() -> Bool {
        guard isUnlocked, !key.isEmpty else { return false }
        do {
            try vault.save(key: key, entries: entries)
            return true
        } catch {
            message = "The vault could not be saved. Your entries are still open here."
            return false
        }
    }

    /// Writes `proposed` and only then publishes it, so a failed write never leaves the screen
    /// showing a change the file does not have.
    @discardableResult
    private func commit(_ proposed: [Entry]) -> Bool {
        guard isUnlocked, !key.isEmpty else { return false }
        do {
            try vault.save(key: key, entries: proposed)
        } catch {
            message = "The vault could not be saved. Your entries are still open here."
            return false
        }
        message = nil
        entries = proposed
        return true
    }

    /// Adds an entry at the end and selects the tab it belongs to, as both other clients do. Refuses
    /// an equal entry and a vault already at the shared ceiling, so this client cannot build a vault
    /// the others will not extend.
    @discardableResult
    public func add(_ entry: Entry) -> Bool {
        guard isUnlocked else { return false }
        guard entries.count < InputPolicy.maxEntries else {
            message = "This vault already holds the maximum of \(InputPolicy.maxEntries) entries."
            return false
        }
        guard !entries.contains(entry) else {
            message = "This vault already holds that entry."
            return false
        }
        guard commit(entries + [entry]) else { return false }
        category = EntryCategory.of(entry)
        return true
    }

    /// Removes one entry by position, leaving the rest in their stored order.
    @discardableResult
    public func remove(at index: Int) -> Bool {
        guard entries.indices.contains(index) else { return false }
        var proposed = entries
        proposed.remove(at: index)
        return commit(proposed)
    }

    /// Replaces one entry, keeping the stored order — an edit must not reorder the vault.
    @discardableResult
    public func update(at index: Int, with entry: Entry) -> Bool {
        guard entries.indices.contains(index) else { return false }
        var proposed = entries
        proposed[index] = entry
        return commit(proposed)
    }

    /// Reveals or conceals one entry and persists the choice, which is vault data
    /// (`details_visible`) in both other clients.
    ///
    /// A failed write leaves the entry concealed even if the request was to reveal it: showing
    /// details the file does not agree with is the one outcome neither client allows.
    @discardableResult
    public func setDetailsVisible(at index: Int, _ visible: Bool) -> Bool {
        guard entries.indices.contains(index) else { return false }
        if visible {
            var proposed = entries
            proposed[index].setDetailsVisible(true)
            return commit(proposed)
        }
        var proposed = entries
        proposed[index].setDetailsVisible(false)
        if commit(proposed) { return true }
        entries[index].setDetailsVisible(false)
        return false
    }

    // MARK: - Backups, restores and links

    /// One exported backup: the bytes and the dated name they are written under.
    public struct ExportedBackup {
        public let name: String
        public let data: Data
    }

    /// Encrypts every entry with a password the person typed, under a dated name.
    ///
    /// The backup password is deliberately not the vault password: both other clients ask for it
    /// separately, and reusing one would let a leaked backup open the vault.
    public func exportBackup(password: String, now: Date = Date()) -> ExportedBackup? {
        guard isUnlocked else { return nil }
        guard !password.isEmpty, password.count <= InputPolicy.maxVaultPasswordChars else {
            message = "The backup password is empty or too long."
            return nil
        }
        do {
            let document = try VaultDocument.encode(entries)
            let data = try EncryptedBackup.sealWithFreshParameters(password: password,
                                                                  plain: document)
            return ExportedBackup(name: BackupFileNames.encrypted(now), data: data)
        } catch {
            message = "The backup could not be written."
            return nil
        }
    }

    /// Restores a password-encrypted backup, merging or replacing. Returns the plan, or nil with a
    /// sentence in `message`.
    public func restore(encrypted data: Data, password: String, replace: Bool) -> RestorePlan? {
        do {
            let document = try EncryptedBackup.open(password: password, data: data)
            return install(document: document, replace: replace)
        } catch VaultCryptoError.authenticationFailed {
            message = BackupWording.wrongPassword
            return nil
        } catch EncryptedBackupError.invalidPassword {
            message = "The backup password is empty or too long."
            return nil
        } catch {
            message = BackupWording.unreadableBackup
            return nil
        }
    }

    /// Restores a legacy unencrypted JSON backup. The client reads one; it never writes one.
    public func restore(plainText data: Data, replace: Bool) -> RestorePlan? {
        let document: String
        do {
            document = try PlainTextBackupDecoder.decode(data)
        } catch {
            message = BackupWording.plainTextInvalid
            return nil
        }
        return install(document: document, replace: replace)
    }

    private func install(document: String, replace: Bool) -> RestorePlan? {
        let imported: [Entry]
        do {
            imported = try VaultDocument.decode(document)
        } catch {
            message = "The backup does not contain a readable entry list."
            return nil
        }
        let plan: RestorePlan
        do {
            plan = try VaultRestoreMerge.plan(imported: imported, current: entries,
                                              replace: replace)
        } catch VaultRestoreMergeError.emptyImport {
            message = BackupWording.emptyBackup
            return nil
        } catch {
            message = BackupWording.duplicateBackup
            return nil
        }
        guard plan.entries.count <= InputPolicy.maxEntries else {
            message = "The backup would grow this vault past \(InputPolicy.maxEntries) entries."
            return nil
        }
        // Write first, publish second: a failed restore must not discard the current entries.
        guard commit(plan.entries) else { return nil }
        return plan
    }

    /// The `otpauth://` link for an entry, or nil when it has none. This is also the QR payload, so
    /// **Show QR code** and **Copy link** can never disagree.
    public func otpUri(for index: Int) -> String? {
        guard entries.indices.contains(index) else { return nil }
        return OtpUri.uri(for: entries[index])
    }

    /// What an import did. The duplicate-issuer case is a question, not a failure: the other two
    /// clients ask **Add anyway** when a new account's issuer is already in the vault, and only then
    /// add it, so a second GitHub account is a deliberate choice rather than a silent duplicate.
    public enum ImportOutcome: Equatable {
        case added
        case duplicateIssuer(String)
        case duplicateEntry
        case rejected(String)
        /// A Google Authenticator export code: where the export has got to, and what it holds once
        /// the last code has arrived.
        case migration(MigrationImportStep)

        /// The question the duplicate-issuer case asks, word for word as on Android.
        public static let duplicateIssuerTitle = "Issuer already exists"
        public static func duplicateIssuerMessage(_ issuer: String) -> String {
            "An account for \(issuer) is already in your vault. Add another account from this "
                + "issuer anyway?"
        }
        public static let addAnyway = "Add anyway"

        public var didAdd: Bool { self == .added }
    }

    /// True when a non-blank issuer is already present, ignoring surrounding space and case —
    /// `EntryList.hasIssuer`.
    public func hasIssuer(_ issuer: String) -> Bool {
        let candidate = issuer.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !candidate.isEmpty else { return false }
        return entries.contains {
            $0.issuer.trimmingCharacters(in: .whitespacesAndNewlines)
                .caseInsensitiveCompare(candidate) == .orderedSame
        }
    }

    /// Adds the entry a pasted `otpauth://` link describes. `allowDuplicateIssuer` carries the answer
    /// to the **Add anyway** question.
    public func importUri(_ text: String, allowDuplicateIssuer: Bool = false,
                          now: Date = Date()) -> ImportOutcome {
        // A pasted link may arrive with a stray newline from the clipboard; the parser itself stays
        // byte-exact, as it is for a scanned payload.
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            return insert(try OtpUri.parse(trimmed).entry(now: EntryUsage.nowMillis(now)),
                          allowDuplicateIssuer: allowDuplicateIssuer)
        } catch let error as OtpParseError {
            message = error.message
            return .rejected(error.rawValue)
        } catch {
            message = "That link could not be turned into an entry."
            return .rejected("UNKNOWN")
        }
    }

    /// Adds the entry a QR payload describes. A Google Authenticator export code is not one entry:
    /// it is routed to `addMigrationImportCode`, which collects the codes of one export and hands the
    /// accounts back for a single confirmation.
    public func importPayload(_ text: String, allowDuplicateIssuer: Bool = false,
                              now: Date = Date()) -> ImportOutcome {
        switch QrPayload.classify(text) {
        case .otp(let uri):
            do {
                return insert(try uri.entry(now: EntryUsage.nowMillis(now)),
                              allowDuplicateIssuer: allowDuplicateIssuer)
            } catch let error as OtpParseError {
                message = error.message
                return .rejected(error.rawValue)
            } catch {
                message = "That code could not be turned into an entry."
                return .rejected("UNKNOWN")
            }
        case .migration:
            // A multi-account export is not one entry; `addMigrationImportCode` collects it.
            return .migration(addMigrationImportCode(text, now: now))
        case .invalid(let reason):
            message = OtpParseError(rawValue: reason)?.message ?? OtpParseError.invalidUri.message
            return .rejected(reason)
        case .unrecognized:
            message = "That code does not contain an otpauth:// link."
            return .rejected("UNRECOGNIZED")
        }
    }

    // MARK: - Google Authenticator exports

    /// Where an export has got to. `entries` is filled in only once every code of the export has
    /// arrived: a half-scanned export is never partly imported.
    public struct MigrationImportStep: Equatable {
        /// Codes of this export seen so far, and how many the export says it has.
        public let received: Int
        public let expected: Int
        /// The accounts awaiting confirmation, once the export is complete.
        public let entries: [Entry]?
        /// A sentence for the sheet, or nil when there is nothing to say.
        public let message: String?

        public var isComplete: Bool { entries != nil }
    }

    /// What confirming an export did.
    public enum MigrationImportResult: Equatable {
        case imported(added: Int, skipped: Int)
        case saveFailed
        case locked

        /// The sentence the phone shows afterwards, verbatim.
        public var message: String {
            switch self {
            case .imported(let added, let skipped):
                return MigrationWording.finished(added: added, skipped: skipped)
            case .saveFailed, .locked:
                return MigrationWording.saveFailed
            }
        }
    }

    /// Adds one code of a Google Authenticator export.
    ///
    /// The rules are `MigrationBatchCollector`'s, which mirrors Android's: a code from a different
    /// export is refused but leaves the export open, a repeated code is refused, and an export that
    /// would not fit in the vault ends instead of importing part of it. Nothing is written until
    /// `confirmMigrationImport`.
    @discardableResult
    public func addMigrationImportCode(_ text: String, now: Date = Date())
        -> MigrationImportStep {
        guard isUnlocked else {
            migration = nil
            return MigrationImportStep(received: 0, expected: 0, entries: nil, message: nil)
        }
        let payload: MigrationPayload
        do {
            payload = try MigrationPayload.parse(text)
        } catch let error as MigrationError {
            // The open export survives a code that could not be read, as it does on the phone: the
            // person can scan the right code next.
            message = error.message
            return step(message: error.message)
        } catch {
            message = MigrationWording.invalid
            return step(message: MigrationWording.invalid)
        }

        var collector = migration ?? MigrationBatchCollector(generation: generation)
        let status = collector.add(payload, generation: generation, sessionReady: isUnlocked)
        switch status {
        case .added:
            migration = collector
            let notice = MigrationWording.progress(received: collector.received,
                                                   expected: collector.expected)
            message = nil
            return MigrationImportStep(received: collector.received,
                                       expected: collector.expected, entries: nil,
                                       message: notice)
        case .complete:
            // The counts have to be read before the accounts are taken: taking them closes the
            // collector, which empties it.
            let received = collector.received
            let expected = collector.expected
            migration = collector
            let accounts = collector.takeAccounts(generation: generation, sessionReady: isUnlocked)
            migration = nil
            guard let accounts else {
                message = MigrationWording.invalid
                return step(message: MigrationWording.invalid)
            }
            do {
                let entries = try accounts.map { try $0.entry(now: EntryUsage.nowMillis(now)) }
                message = nil
                return MigrationImportStep(received: received, expected: expected,
                                           entries: entries, message: nil)
            } catch {
                message = MigrationWording.invalid
                return step(message: MigrationWording.invalid)
            }
        case .duplicate, .differentExport, .tooMany, .stale:
            // A duplicate or a foreign code leaves the export open; the other two end it.
            if status == .tooMany || status == .stale {
                migration = nil
            } else {
                migration = collector
            }
            let notice = MigrationWording.message(for: status)
            message = notice.isEmpty ? nil : notice
            return step(message: notice.isEmpty ? nil : notice)
        }
    }

    /// The export as it stands, for a sheet that was dismissed and reopened.
    public var openMigrationImport: MigrationImportStep? {
        guard let migration, migration.isOpen, migration.received > 0 else { return nil }
        return MigrationImportStep(received: migration.received, expected: migration.expected,
                                   entries: nil, message: MigrationWording.progress(
                                    received: migration.received, expected: migration.expected))
    }

    /// Discards an export in progress; nothing was written, so nothing has to be undone.
    public func cancelMigrationImport() {
        migration?.cancel()
        migration = nil
    }

    /// Writes the confirmed accounts as one change, the way Android's `importEntries` does: an entry
    /// the vault already holds is skipped rather than duplicated, the rest are stamped as imported,
    /// and the whole batch is committed once or not at all.
    public func confirmMigrationImport(_ entries: [Entry], now: Date = Date())
        -> MigrationImportResult {
        guard isUnlocked, !key.isEmpty else { return .locked }
        let stamp = EntryUsage.nowMillis(now)
        var proposed = self.entries
        var distinct = Set(proposed)
        var accepted: [Entry] = []
        var skipped = 0
        for entry in entries {
            if distinct.contains(entry) {
                skipped += 1
                continue
            }
            // The vault ceiling is checked per entry, as the phone does: reaching it fails the
            // whole batch instead of importing the first few.
            guard proposed.count < InputPolicy.maxEntries else { return .saveFailed }
            // Imported entries are stamped as just used, so they lead a **Last used** list.
            var imported = entry
            EntryUsage.markImported(&imported, now: stamp)
            distinct.insert(entry)
            proposed.append(imported)
            accepted.append(imported)
        }
        guard !accepted.isEmpty else {
            message = nil
            return .imported(added: 0, skipped: skipped)
        }
        guard commit(proposed) else { return .saveFailed }
        // Android shows the tab of the first imported account.
        if let first = accepted.first { category = EntryCategory.of(first) }
        return .imported(added: accepted.count, skipped: skipped)
    }

    private func step(message: String?) -> MigrationImportStep {
        MigrationImportStep(received: migration?.received ?? 0, expected: migration?.expected ?? 0,
                            entries: nil, message: message)
    }

    /// The one place an imported account is put into the vault, so a link and a QR code share the
    /// duplicate rules instead of each re-implementing them.
    private func insert(_ entry: Entry, allowDuplicateIssuer: Bool) -> ImportOutcome {
        let issuer = entry.issuer.trimmingCharacters(in: .whitespacesAndNewlines)
        if !issuer.isEmpty, hasIssuer(issuer), !allowDuplicateIssuer {
            return .duplicateIssuer(issuer)
        }
        if entries.contains(entry) {
            message = "This vault already holds that entry."
            return .duplicateEntry
        }
        guard entries.count < InputPolicy.maxEntries else {
            message = "This vault already holds the maximum of \(InputPolicy.maxEntries) entries."
            return .rejected("MAX_ENTRIES")
        }
        guard add(entry) else { return .rejected("NOT_ADDED") }
        return .added
    }

    /// The name a confirmation asks about: the label, or the issuer when the label is blank — the
    /// rule both other clients use when they name an entry in a prompt.
    public static func displayName(_ entry: Entry) -> String {
        let label = entry.label.trimmingCharacters(in: .whitespacesAndNewlines)
        if !label.isEmpty { return label }
        let issuer = entry.issuer.trimmingCharacters(in: .whitespacesAndNewlines)
        return issuer.isEmpty ? "this entry" : issuer
    }

    /// The folder this store reads and writes, for the diagnostics line on the lock screen.
    public var folderPath: String { directory.path }
}
