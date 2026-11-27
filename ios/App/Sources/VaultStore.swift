import Combine
import Foundation
import OfflineOTPKit

/// The app's view of one vault: locked or unlocked, what the list shows, and the mutations a person
/// can perform — all fail-closed and all persisted before they are published.
///
/// This is the only place in the app that touches the vault files. It is a plain `ObservableObject`
/// driven from the main actor by the UIKit/Compose adapter actions, and it is used from unit tests the same
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
    @Published public private(set) var persistedRevision = 0
    @Published private var sessionVisibility: [Entry: Bool] = [:]
    private var revealedAtDates: [Entry: Date] = [:]

    public func isRevealed(_ entry: Entry) -> Bool {
        isUnlocked && (sessionVisibility[entry] ?? entry.detailsVisible)
    }

    public func displayEntry(_ entry: Entry) -> Entry {
        var displayed = entry
        displayed.setDetailsVisible(isRevealed(entry))
        return displayed
    }

    public func toggleReveal(at index: Int) {
        guard isUnlocked, entries.indices.contains(index) else { return }
        _ = setDetailsVisible(at: index, !isRevealed(entries[index]))
    }

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

    /// The fields the search covers (`LABEL`, `ISSUER`, `TAGS`), persisted under `ui_search_includes`.
    @Published public private(set) var searchIncludes: Set<SearchInclude> = Set(SearchInclude.allCases)

    /// Whether this device can offer the quick unlock, and which sensor it would use. It changes when
    /// a face is enrolled or removed, so it is asked again whenever the lock screen appears.
    @Published public private(set) var biometry: BiometryAvailability = .unavailable(.other)
    /// Whether the person turned the quick unlock on for this vault: a client preference stored beside
    /// the vault, never inside `secrets.dat`.
    @Published public private(set) var biometricUnlockEnabled = false

    /// The client preference key, next to `ui_sort_mode` in the settings file.
    public static let biometricSettingKey = "biometric_unlock"
    /// The portable-preferences port over this vault's plist, the peer of Android's
    /// `AndroidPreferencesPort` and the desktop's `DesktopPreferencesPort`.
    ///
    /// This client moves sort order and inactivity delay, not backup passwords or OpenPGP key grants.
    /// The `auth_*` entries live in the same file but belong to the
    /// vault and are never readable through this port.
    public var preferencesPort: PreferencesExport.SettingsPort { PreferencesPort(settings: settings) }

    private final class PreferencesPort: PreferencesExport.AtomicSettingsPort {
        private let settings: PropertyListSettings
        init(settings: PropertyListSettings) { self.settings = settings }

        func storedKeys() -> Set<String> { settings.keys() }

        func read(_ key: String) -> PreferencesExport.Value? {
            guard let entry = PreferencesExport.setting(key) else { return nil }
            switch entry.kind {
            case .integer:
                // 0 is how this app spells "disabled", so an explicit zero is a value, not absence.
                guard let value = settings.integer(key) else { return nil }
                return .integer(value)
            case .string:
                guard let value = settings.string(key) else { return nil }
                return .text(value)
            case .boolean:
                guard let value = settings.string(key) else { return nil }
                return .boolean(value == "true")
            case .stringSet:
                guard let value = settings.string(key) else { return nil }
                return .textSet(value.split(separator: "\n").map(String.init))
            case .secret, .deviceBound:
                return nil
            }
        }

        func writeBatch(_ values: [String: PreferencesExport.Value]) throws {
            var changes: [String: Any] = [:]
            for (key, value) in values {
                guard let entry = PreferencesExport.setting(key) else {
                    throw PreferencesExport.PreferencesError.typeMismatch(key)
                }
                switch (entry.kind, value) {
                case (.integer, .integer(let number)): changes[key] = number
                case (.string, .text(let text)): changes[key] = text
                case (.boolean, .boolean(let flag)): changes[key] = flag ? "true" : "false"
                case (.stringSet, .textSet(let items)): changes[key] = items.joined(separator: "\n")
                default: throw PreferencesExport.PreferencesError.typeMismatch(key)
                }
            }
            try settings.mergeAtomic(changes)
        }

        /// Writes one value.
        ///
        /// The port contract is deliberately non-throwing: `PreferencesExport.apply` validates and
        /// types every value *before* it calls this, so a key or type that does not match the
        /// catalog cannot reach here. Writing nothing is the safe outcome if that ever changes —
        /// it is strictly better than crashing a settings import, and the codec's own tests pin the
        /// validation that makes this branch unreachable.
        func write(_ key: String, _ value: PreferencesExport.Value) {
            guard let entry = PreferencesExport.setting(key) else { return }
            switch (entry.kind, value) {
            case (.integer, .integer(let number)):
                settings.setInteger(key, number)
            case (.string, .text(let text)):
                settings.setString(key, text)
            case (.boolean, .boolean(let flag)):
                settings.setString(key, flag ? "true" : "false")
            case (.stringSet, .textSet(let values)):
                settings.setString(key, values.joined(separator: "\n"))
            default:
                return
            }
        }
    }

    public static let inactivitySettingKey = "ui_inactivity_lock_seconds"
    public static let cardLayoutSettingKey = "ui_card_layout"
    public static let labelDisplaySettingKey = "ui_label_display"
    public static let labelSizeSpSettingKey = "ui_label_size_sp"
    public static let tagFunctionalitySettingKey = "ui_tag_functionality"
    public static let hideIssuerSettingKey = "ui_hide_issuer"
    public static let showPrevTokenSettingKey = "ui_show_prev_token"
    public static let showIndividualTimeoutsSettingKey = "ui_show_individual_timeouts"
    public static let hideGlobalTimeoutSettingKey = "ui_hide_global_timeout"
    public static let showThumbnailsSettingKey = "ui_show_thumbnails"
    public static let thumbnailSizeDpSettingKey = "ui_thumbnail_size_dp"
    public static let highlightTokenSettingKey = "ui_highlight_token"
    public static let tapSingleSettingKey = "ui_tap_single"
    public static let tapDoubleSettingKey = "ui_tap_double"
    public static let tapToRevealTimeoutSettingKey = "ui_tap_to_reveal_timeout"
    public static let focusSearchOnStartSettingKey = "ui_focus_search_on_start"
    public static let relockBackgroundSettingKey = "ui_relock_background"
    public static let backupAppendDateTimeSettingKey = "ui_backup_append_date_time"
    public static let splitGroupSizeSettingKey = "ui_split_group_size"
    public static let searchIncludesSettingKey = "ui_search_includes"
    public static let selectedTagsSettingKey = "ui_selected_tags"
    public static let untaggedSelectedSettingKey = "ui_untagged_selected"
    public static let usedTokensShownSettingKey = "ui_used_tokens_shown"
    static let biometricEnabled = "enabled"
    static let biometricDisabled = "disabled"

    public static let defaultInactivityLockSeconds = 600

    @Published public private(set) var inactivityLockSeconds = VaultStore.defaultInactivityLockSeconds
    @Published public private(set) var cardLayout = "DEFAULT"
    @Published public private(set) var labelDisplay = "TRUNCATE"
    @Published public private(set) var labelSizeSp = 0
    @Published public private(set) var hideIssuer = false
    @Published public private(set) var showPreviousToken = true
    @Published public private(set) var showIndividualTimeouts = true
    @Published public private(set) var hideGlobalTimeout = false
    @Published public private(set) var showThumbnails = true
    @Published public private(set) var thumbnailSizeDp = 0
    @Published public private(set) var highlightToken = true
    @Published public private(set) var tapSingle = "REVEAL"
    @Published public private(set) var tapDouble = "NOTHING"
    @Published public private(set) var tapToRevealTimeoutSeconds = 0
    @Published public private(set) var focusSearchOnStart = false
    @Published public private(set) var relockOnBackground = false
    @Published public private(set) var backupAppendDateTime = true
    @Published public private(set) var tokenGroupSize = 3
    @Published public private(set) var usedTokensDialogShown = false
    private var lastActivityDate = Date()

    private let vault: PasswordVault
    private let settings: PropertyListSettings
    private let directory: URL
    private let biometrics: BiometricKeyStore
    private let openPgp: OpenPgpPort
    private var key: [UInt8] = []

    /// How many times this store has been unlocked. A Google Authenticator export can span several QR
    /// codes, and the codes are only allowed to complete an export if they all arrive inside one
    /// unlocked session — the `VaultSession.generation()` rule Android applies.
    @Published public private(set) var generation = 0

    /// The export being collected, if one is open.
    private var migration: MigrationBatchCollector?

    /// `directory` is injectable so the app tests can use a temporary folder instead of Documents,
    /// and `biometrics` / `openPgp` so they can drive quick unlock and OpenPGP without external hardware.
    public init(directory: URL = VaultStore.defaultDirectory(),
                biometrics: BiometricKeyStore = KeychainBiometricKeyStore(),
                openPgp: OpenPgpPort = RustOpenPgpPort()) {
        self.directory = directory
        self.biometrics = biometrics
        self.openPgp = openPgp
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
        self.inactivityLockSeconds = VaultStore.storedInactivityLockSeconds(
            settings.integer(VaultStore.inactivitySettingKey))
        self.cardLayout = VaultStore.storedCardLayout(settings.string(VaultStore.cardLayoutSettingKey))
        self.labelDisplay = VaultStore.storedLabelDisplay(settings.string(VaultStore.labelDisplaySettingKey))
        self.labelSizeSp = VaultStore.storedLabelSizeSp(settings.integer(VaultStore.labelSizeSpSettingKey))
        self.tagFunctionality = VaultStore.storedTagFunctionality(
            settings.string(VaultStore.tagFunctionalitySettingKey))
        self.hideIssuer = settings.string(VaultStore.hideIssuerSettingKey) == "true"
        self.showPreviousToken = VaultStore.storedShowPreviousToken(
            settings.string(VaultStore.showPrevTokenSettingKey))
        self.showIndividualTimeouts = VaultStore.storedShowIndividualTimeouts(
            settings.string(VaultStore.showIndividualTimeoutsSettingKey))
        self.hideGlobalTimeout = settings.string(VaultStore.hideGlobalTimeoutSettingKey) == "true"
        self.showThumbnails = VaultStore.storedShowThumbnails(
            settings.string(VaultStore.showThumbnailsSettingKey))
        self.thumbnailSizeDp = VaultStore.storedThumbnailSizeDp(
            settings.integer(VaultStore.thumbnailSizeDpSettingKey))
        self.highlightToken = VaultStore.storedHighlightToken(
            settings.string(VaultStore.highlightTokenSettingKey))
        self.tapSingle = VaultStore.storedTapMode(
            settings.string(VaultStore.tapSingleSettingKey), fallback: "REVEAL")
        self.tapDouble = VaultStore.storedTapMode(
            settings.string(VaultStore.tapDoubleSettingKey), fallback: "NOTHING")
        self.tapToRevealTimeoutSeconds = VaultStore.storedRevealTimeoutSeconds(
            settings.integer(VaultStore.tapToRevealTimeoutSettingKey))
        self.focusSearchOnStart = settings.string(VaultStore.focusSearchOnStartSettingKey) == "true"
        self.relockOnBackground = settings.string(VaultStore.relockBackgroundSettingKey) == "true"
        self.backupAppendDateTime = VaultStore.storedBackupAppendDateTime(
            settings.string(VaultStore.backupAppendDateTimeSettingKey))
        self.tokenGroupSize = VaultStore.storedTokenGroupSize(
            settings.integer(VaultStore.splitGroupSizeSettingKey))
        self.searchIncludes = VaultStore.storedSearchIncludes(
            settings.string(VaultStore.searchIncludesSettingKey))
        self.includeUntagged = settings.string(VaultStore.untaggedSelectedSettingKey) == "true"
        self.usedTokensDialogShown = settings.string(VaultStore.usedTokensShownSettingKey) == "true"
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

    /// First-run restore: decrypts an encrypted backup and provisions a new local vault in one write.
    /// The backup password is intentionally independent of the new vault password; entries replace
    /// nothing because this path is only available before a vault exists.
    public func restoreNewVault(encrypted data: Data, backupPassword: String,
                                newPassword: String, confirmation: String) -> Bool {
        message = nil
        guard !hasVault, !isUnlocked else { message = "A vault already exists here."; return false }
        guard newPassword.count >= InputPolicy.minVaultPasswordChars,
              newPassword.count <= InputPolicy.maxVaultPasswordChars else {
            message = "Use a new vault password between 6 and 1024 characters."; return false
        }
        guard newPassword == confirmation else { message = "The new vault passwords do not match."; return false }
        do {
            let document = try EncryptedBackup.open(password: backupPassword, data: data)
            let restored = try VaultDocument.decode(document)
            try vault.create(password: newPassword, entries: restored)
            let unlocked = try vault.open(password: newPassword)
            key = unlocked.key
            entries = unlocked.entries
            hasVault = true
            isUnlocked = true
            beginSession()
            return true
        } catch VaultCryptoError.authenticationFailed {
            message = BackupWording.wrongPassword
        } catch EncryptedBackupError.invalidPassword {
            message = "The backup password is empty or too long."
        } catch {
            message = BackupWording.unreadableBackup
        }
        return false
    }

    private func create(password: String, confirmation: String) {
        // The same rules the other two clients apply when a vault is created: a minimum length and a
        // matching confirmation. Enforcing them here means the failure is explained, not a silent
        // refusal by the engine.
        guard password.count >= InputPolicy.minVaultPasswordChars else {
            message = "Use at least \(InputPolicy.minVaultPasswordChars) characters."
            return
        }
        guard password.count <= InputPolicy.maxVaultPasswordChars else {
            message = "Use at most \(InputPolicy.maxVaultPasswordChars) characters."
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
        sessionVisibility.removeAll()
        revealedAtDates.removeAll()
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

    /// Safe first-run file-picker feedback; does not expose or retain the selected document.
    public func reportRestoreSelectionFailure(_ text: String) {
        guard !hasVault, !isUnlocked else { return }
        message = text
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
        reconcileStoredTagSelection()
        lastActivityDate = Date()
    }

    private func reconcileStoredTagSelection() {
        if let rawTags = settings.string(VaultStore.selectedTagsSettingKey) {
            let available = Set(entries.flatMap(\.tags))
            var parsed: [String] = []
            for item in rawTags.split(separator: "\n") {
                let tag = item.trimmingCharacters(in: .whitespacesAndNewlines)
                if !tag.isEmpty && available.contains(tag) && !parsed.contains(tag) {
                    parsed.append(tag)
                }
            }
            if tagFunctionality == .single && parsed.count > 1 {
                parsed = Array(parsed.prefix(1))
            }
            selectedTags = parsed
        } else if !selectedTags.isEmpty {
            let available = Set(entries.flatMap(\.tags))
            var filtered = selectedTags.filter { available.contains($0) }
            if tagFunctionality == .single && filtered.count > 1 {
                filtered = Array(filtered.prefix(1))
            }
            selectedTags = filtered
        }
        if let rawUntagged = settings.string(VaultStore.untaggedSelectedSettingKey) {
            includeUntagged = rawUntagged == "true"
        }
    }

    private func persistTagSelection() {
        settings.setString(VaultStore.selectedTagsSettingKey, selectedTags.joined(separator: "\n"))
        settings.setString(VaultStore.untaggedSelectedSettingKey, includeUntagged ? "true" : "false")
    }

    /// Re-reads the delay, sort mode and display preferences from the store, for a setting a
    /// preferences import just replaced underneath the UI.
    public func reloadInactivityLock() {
        inactivityLockSeconds = VaultStore.storedInactivityLockSeconds(
            settings.integer(VaultStore.inactivitySettingKey))
        sortMode = SortMode.stored(settings.string(SortMode.settingsKey))
        cardLayout = VaultStore.storedCardLayout(settings.string(VaultStore.cardLayoutSettingKey))
        labelDisplay = VaultStore.storedLabelDisplay(settings.string(VaultStore.labelDisplaySettingKey))
        labelSizeSp = VaultStore.storedLabelSizeSp(settings.integer(VaultStore.labelSizeSpSettingKey))
        tagFunctionality = VaultStore.storedTagFunctionality(
            settings.string(VaultStore.tagFunctionalitySettingKey))
        if tagFunctionality == .single, selectedTags.count > 1 {
            selectedTags = Array(selectedTags.prefix(1))
        }
        hideIssuer = settings.string(VaultStore.hideIssuerSettingKey) == "true"
        showPreviousToken = VaultStore.storedShowPreviousToken(
            settings.string(VaultStore.showPrevTokenSettingKey))
        showIndividualTimeouts = VaultStore.storedShowIndividualTimeouts(
            settings.string(VaultStore.showIndividualTimeoutsSettingKey))
        hideGlobalTimeout = settings.string(VaultStore.hideGlobalTimeoutSettingKey) == "true"
        showThumbnails = VaultStore.storedShowThumbnails(
            settings.string(VaultStore.showThumbnailsSettingKey))
        thumbnailSizeDp = VaultStore.storedThumbnailSizeDp(
            settings.integer(VaultStore.thumbnailSizeDpSettingKey))
        highlightToken = VaultStore.storedHighlightToken(
            settings.string(VaultStore.highlightTokenSettingKey))
        tapSingle = VaultStore.storedTapMode(
            settings.string(VaultStore.tapSingleSettingKey), fallback: "REVEAL")
        tapDouble = VaultStore.storedTapMode(
            settings.string(VaultStore.tapDoubleSettingKey), fallback: "NOTHING")
        tapToRevealTimeoutSeconds = VaultStore.storedRevealTimeoutSeconds(
            settings.integer(VaultStore.tapToRevealTimeoutSettingKey))
        focusSearchOnStart = settings.string(VaultStore.focusSearchOnStartSettingKey) == "true"
        relockOnBackground = settings.string(VaultStore.relockBackgroundSettingKey) == "true"
        backupAppendDateTime = VaultStore.storedBackupAppendDateTime(
            settings.string(VaultStore.backupAppendDateTimeSettingKey))
        tokenGroupSize = VaultStore.storedTokenGroupSize(
            settings.integer(VaultStore.splitGroupSizeSettingKey))
        searchIncludes = VaultStore.storedSearchIncludes(
            settings.string(VaultStore.searchIncludesSettingKey))
        reconcileStoredTagSelection()
        usedTokensDialogShown = settings.string(VaultStore.usedTokensShownSettingKey) == "true"
        lastActivityDate = Date()
    }

    private static func storedCardLayout(_ raw: String?) -> String {
        guard let candidate = raw?.uppercased(),
              ["DEFAULT", "COMPACT", "FULL"].contains(candidate) else {
            return "DEFAULT"
        }
        return candidate
    }

    private static func storedLabelDisplay(_ raw: String?) -> String {
        guard let candidate = raw?.uppercased(),
              ["TRUNCATE", "SCROLL", "MULTILINE"].contains(candidate) else {
            return "TRUNCATE"
        }
        return candidate
    }

    private static func storedTagFunctionality(_ raw: String?) -> TagFunctionality {
        guard let candidate = raw?.uppercased(),
              let resolved = TagFunctionality(rawValue: candidate) else {
            return .or
        }
        return resolved
    }

    private static func storedShowPreviousToken(_ raw: String?) -> Bool {
        guard let raw else { return true }
        return raw != "false"
    }

    private static func storedShowIndividualTimeouts(_ raw: String?) -> Bool {
        guard let raw else { return true }
        return raw != "false"
    }

    private static func storedShowThumbnails(_ raw: String?) -> Bool {
        guard let raw else { return true }
        return raw != "false"
    }

    private static func storedLabelSizeSp(_ raw: Int?) -> Int {
        guard let raw, raw == 0 || (12...24).contains(raw) else { return 0 }
        return raw
    }

    private static func storedThumbnailSizeDp(_ raw: Int?) -> Int {
        guard let raw, raw > 0 else { return 0 }
        return min(max(raw, 20), 96)
    }

    private static func storedBackupAppendDateTime(_ raw: String?) -> Bool {
        guard let raw else { return true }
        return raw != "false"
    }

    private static func storedHighlightToken(_ raw: String?) -> Bool {
        guard let raw else { return true }
        return raw != "false"
    }

    private static func storedTapMode(_ raw: String?, fallback: String) -> String {
        guard let candidate = raw?.uppercased(),
              ["NOTHING", "REVEAL", "COPY"].contains(candidate) else {
            return fallback
        }
        return candidate
    }

    private static func storedTokenGroupSize(_ raw: Int?) -> Int {
        guard let raw, [0, 2, 3].contains(raw) else { return 3 }
        return raw
    }

    private static func storedRevealTimeoutSeconds(_ raw: Int?) -> Int {
        guard let raw, raw > 0 else { return 0 }
        return min(max(raw, 5), 600)
    }

    private static func storedSearchIncludes(_ raw: String?) -> Set<SearchInclude> {
        guard let raw, !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return Set(SearchInclude.allCases)
        }
        let parsed = Set(
            raw.split(whereSeparator: { $0 == "\n" || $0 == "," })
                .compactMap { SearchInclude(rawValue: $0.trimmingCharacters(in: .whitespaces).uppercased()) }
        )
        return parsed.isEmpty ? Set(SearchInclude.allCases) : parsed
    }

    public func saveAutoLockSettings(seconds: Int, relockOnBackground: Bool) throws {
        guard isUnlocked, seconds >= 0, Int32(exactly: seconds) != nil else { throw VaultCryptoError.invalidParameters }
        try PreferencesPort(settings: settings).writeBatch([
            PreferencesExport.inactivityLockKey: .integer(seconds),
            PreferencesExport.relockBackgroundKey: .boolean(relockOnBackground)
        ])
        reloadInactivityLock()
    }

    /// Validate the complete submitted form before committing once; never half-apply display edits.
    public func saveCardDisplaySettings(_ draft: [String: String]) throws {
        guard isUnlocked else { throw VaultCryptoError.invalidParameters }
        var changes: [String: PreferencesExport.Value] = [:]
        let enums: [(String, String, [String])] = [
            ("layout", PreferencesExport.cardLayoutKey, ["DEFAULT", "COMPACT", "FULL"]),
            ("labelDisplay", PreferencesExport.labelDisplayKey, ["TRUNCATE", "SCROLL", "MULTILINE"]),
            ("tapSingle", PreferencesExport.tapSingleKey, ["NOTHING", "REVEAL", "COPY"]),
            ("tapDouble", PreferencesExport.tapDoubleKey, ["NOTHING", "REVEAL", "COPY"])
        ]
        for (field, key, allowed) in enums {
            if let raw = draft[field] {
                guard allowed.contains(raw) else { throw VaultCryptoError.invalidParameters }
                changes[key] = .text(raw)
            }
        }
        let flags: [(String, String)] = [
            ("hideIssuer", PreferencesExport.hideIssuerKey),
            ("showPreviousToken", PreferencesExport.showPrevTokenKey),
            ("showIndividualTimeouts", PreferencesExport.showIndividualTimeoutsKey),
            ("showThumbnails", PreferencesExport.showThumbnailsKey),
            ("highlightToken", PreferencesExport.highlightTokenKey),
            ("hideGlobalTimeout", PreferencesExport.hideGlobalTimeoutKey),
            ("focusSearchOnStart", PreferencesExport.focusSearchOnStartKey)
        ]
        for (field, key) in flags {
            if let raw = draft[field] {
                guard raw == "true" || raw == "false" else { throw VaultCryptoError.invalidParameters }
                changes[key] = .boolean(raw == "true")
            }
        }
        let integers: [(String, String, ClosedRange<Int>)] = [
            ("labelSizeSp", PreferencesExport.labelSizeSpKey, 12...24),
            ("thumbnailSizeDp", PreferencesExport.thumbnailSizeDpKey, 20...96),
            ("tapToRevealTimeoutSeconds", PreferencesExport.tapToRevealTimeoutKey, 5...600)
        ]
        for (field, key, range) in integers {
            if let raw = draft[field] {
                guard let number = Int(raw), number == 0 || range.contains(number) else {
                    throw VaultCryptoError.invalidParameters
                }
                changes[key] = .integer(number)
            }
        }
        if let raw = draft["tokenGroupSize"] {
            guard let number = Int(raw), [0, 2, 3].contains(number) else { throw VaultCryptoError.invalidParameters }
            changes[PreferencesExport.splitGroupSizeKey] = .integer(number)
        }
        try PreferencesPort(settings: settings).writeBatch(changes)
        reloadInactivityLock()
    }

    /// Persists the Compose vault card density (`DEFAULT`, `COMPACT`, or `FULL`).
    public func setCardLayout(_ layout: String) {
        let resolved = VaultStore.storedCardLayout(layout)
        cardLayout = resolved
        settings.setString(VaultStore.cardLayoutSettingKey, resolved)
    }

    /// Persists how card labels overflow (`TRUNCATE`, `SCROLL`, or `MULTILINE`).
    public func setLabelDisplay(_ mode: String) {
        let resolved = VaultStore.storedLabelDisplay(mode)
        labelDisplay = resolved
        settings.setString(VaultStore.labelDisplaySettingKey, resolved)
    }

    /// Persists the optional card label font size (`0` = default, or `12...24` sp).
    public func setLabelSizeSp(_ sizeSp: Int) {
        let resolved = VaultStore.storedLabelSizeSp(sizeSp)
        labelSizeSp = resolved
        settings.setInteger(VaultStore.labelSizeSpSettingKey, resolved)
    }

    /// Persists whether issuer thumbnails / letter tiles are shown on cards (`ui_show_thumbnails`).
    public func setShowThumbnails(_ show: Bool) {
        showThumbnails = show
        settings.setString(VaultStore.showThumbnailsSettingKey, show ? "true" : "false")
    }

    /// Persists the optional card thumbnail size (`0` = layout default, or `20...96` dp).
    public func setThumbnailSizeDp(_ sizeDp: Int) {
        let resolved = VaultStore.storedThumbnailSizeDp(sizeDp)
        thumbnailSizeDp = resolved
        settings.setInteger(VaultStore.thumbnailSizeDpSettingKey, resolved)
    }

    /// Persists whether search receives focus when the vault opens (`ui_focus_search_on_start`).
    public func setFocusSearchOnStart(_ focus: Bool) {
        focusSearchOnStart = focus
        settings.setString(VaultStore.focusSearchOnStartSettingKey, focus ? "true" : "false")
    }

    /// Persists whether entering the background locks the vault immediately (`ui_relock_background`).
    public func setRelockOnBackground(_ relock: Bool) {
        relockOnBackground = relock
        settings.setString(VaultStore.relockBackgroundSettingKey, relock ? "true" : "false")
    }

    /// Persists whether backup filenames append a timestamp (`ui_backup_append_date_time`).
    public func setBackupAppendDateTime(_ append: Bool) {
        backupAppendDateTime = append
        settings.setString(VaultStore.backupAppendDateTimeSettingKey, append ? "true" : "false")
    }

    /// Persists whether expiring OTP tokens highlight during their final seconds (`ui_highlight_token`).
    public func setHighlightToken(_ highlight: Bool) {
        highlightToken = highlight
        settings.setString(VaultStore.highlightTokenSettingKey, highlight ? "true" : "false")
    }

    /// Persists the single-tap card action (`NOTHING`, `REVEAL`, or `COPY`).
    public func setTapSingle(_ mode: String) {
        let resolved = VaultStore.storedTapMode(mode, fallback: "REVEAL")
        tapSingle = resolved
        settings.setString(VaultStore.tapSingleSettingKey, resolved)
    }

    /// Persists the double-tap card action (`NOTHING`, `REVEAL`, or `COPY`).
    public func setTapDouble(_ mode: String) {
        let resolved = VaultStore.storedTapMode(mode, fallback: "NOTHING")
        tapDouble = resolved
        settings.setString(VaultStore.tapDoubleSettingKey, resolved)
    }

    /// Persists whether issuer titles stay concealed on unrevealed rows (`ui_hide_issuer`).
    public func setHideIssuer(_ hidden: Bool) {
        hideIssuer = hidden
        settings.setString(VaultStore.hideIssuerSettingKey, hidden ? "true" : "false")
    }

    /// Persists whether the previous rolling code is shown on revealed OTP cards (`ui_show_prev_token`).
    public func setShowPreviousToken(_ show: Bool) {
        showPreviousToken = show
        settings.setString(VaultStore.showPrevTokenSettingKey, show ? "true" : "false")
    }

    /// Persists whether standard 30-second countdown rings are shown on OTP cards (`ui_show_individual_timeouts`).
    public func setShowIndividualTimeouts(_ show: Bool) {
        showIndividualTimeouts = show
        settings.setString(VaultStore.showIndividualTimeoutsSettingKey, show ? "true" : "false")
    }

    /// Persists whether the global 30-second rollover progress bar is hidden (`ui_hide_global_timeout`).
    public func setHideGlobalTimeout(_ hidden: Bool) {
        hideGlobalTimeout = hidden
        settings.setString(VaultStore.hideGlobalTimeoutSettingKey, hidden ? "true" : "false")
    }

    /// Persists the auto-hide delay for revealed cards (`0` = stay visible until hidden).
    public func setTapToRevealTimeoutSeconds(_ seconds: Int) {
        let resolved = VaultStore.storedRevealTimeoutSeconds(seconds)
        tapToRevealTimeoutSeconds = resolved
        if resolved == 0 {
            revealedAtDates.removeAll()
        }
        settings.setInteger(VaultStore.tapToRevealTimeoutSettingKey, resolved)
    }

    /// Persists whether the one-time token-usage sort notice has been shown (`ui_used_tokens_shown`).
    public func setUsedTokensDialogShown(_ shown: Bool) {
        usedTokensDialogShown = shown
        settings.setString(VaultStore.usedTokensShownSettingKey, shown ? "true" : "false")
    }

    /// Persists explicit OTP digit grouping (`0` ungrouped, `2` pairs, `3` triplets).
    public func setTokenGroupSize(_ groupSize: Int) {
        let resolved = VaultStore.storedTokenGroupSize(groupSize)
        tokenGroupSize = resolved
        settings.setInteger(VaultStore.splitGroupSizeSettingKey, resolved)
    }

    /// Persists which fields (`LABEL`, `ISSUER`, `TAGS`) search matches (`ui_search_includes`).
    public func setSearchIncludes(_ includes: Set<SearchInclude>) {
        guard !includes.isEmpty else { return }
        searchIncludes = includes
        let ordered = SearchInclude.allCases.filter { includes.contains($0) }.map(\.rawValue)
        settings.setString(VaultStore.searchIncludesSettingKey, ordered.joined(separator: "\n"))
    }

    private static func storedInactivityLockSeconds(_ raw: Int?) -> Int {
        guard let raw else { return defaultInactivityLockSeconds }
        return max(0, raw)
    }

    /// Persists the idle auto-lock timeout (defaults to 10 minutes; `0` disables auto-lock).
    public func setInactivityLockSeconds(_ seconds: Int) {
        let clamped = max(0, seconds)
        inactivityLockSeconds = clamped
        settings.setInteger(VaultStore.inactivitySettingKey, clamped)
        lastActivityDate = Date()
    }

    /// Resets the inactivity clock when the person interacts with the app.
    public func noteUserActivity(now: Date = Date()) {
        lastActivityDate = now
    }

    /// Locks the vault if an inactivity timeout is configured and has elapsed.
    public func checkInactivityAutoLock(now: Date = Date()) {
        guard isUnlocked, inactivityLockSeconds > 0 else { return }
        if now.timeIntervalSince(lastActivityDate) >= TimeInterval(inactivityLockSeconds) {
            lock()
        }
    }

    /// Changes the vault password in place, updating the Keychain biometric key if quick unlock is on.
    @discardableResult
    public func changePassword(currentPassword: String, newPassword: String, confirmation: String) -> Bool {
        guard isUnlocked else { return false }
        guard newPassword.count >= InputPolicy.minVaultPasswordChars else {
            message = "Use at least \(InputPolicy.minVaultPasswordChars) characters."
            return false
        }
        guard newPassword.count <= InputPolicy.maxVaultPasswordChars else {
            message = "Use at most \(InputPolicy.maxVaultPasswordChars) characters."
            return false
        }
        guard newPassword == confirmation else {
            message = "The two passwords do not match."
            return false
        }
        let priorIdentifier = vault.vaultIdentifier
        do {
            let changed = try vault.changePassword(currentPassword: currentPassword, newPassword: newPassword)
            key = changed.key
            entries = changed.entries
            beginSession() // Invalidates outstanding hardware/backup operation authority after rekey.
            if biometricUnlockEnabled {
                if let priorIdentifier { biometrics.remove(account: priorIdentifier) }
                if let newAccount = vault.vaultIdentifier {
                    do {
                        try biometrics.save(key: changed.key, account: newAccount)
                        message = "Vault password updated."
                    } catch {
                        disableBiometricUnlock(announcing: false)
                        message = "Vault password updated. Turn quick unlock back on if desired."
                    }
                } else {
                    disableBiometricUnlock(announcing: false)
                    message = "Vault password updated."
                }
            } else {
                message = "Vault password updated."
            }
            return true
        } catch VaultOpenError.authenticationFailed {
            message = "The current password is incorrect."
            return false
        } catch {
            message = "The vault password could not be updated."
            return false
        }
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

    /// True when manual reordering is allowed (`UNSORTED`, no active search or tag filter).
    public var canReorder: Bool {
        listQuery.canReorder
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
        persistTagSelection()
    }

    public func toggleUntagged() {
        includeUntagged.toggle()
        persistTagSelection()
    }

    /// Switches how tags combine. `single` collapses the selection to one tag, as Android's
    /// single-tag mode does.
    public func setTagFunctionality(_ functionality: TagFunctionality) {
        tagFunctionality = functionality
        if functionality == .single, selectedTags.count > 1 {
            selectedTags = Array(selectedTags.prefix(1))
            persistTagSelection()
        }
        settings.setString(VaultStore.tagFunctionalitySettingKey, functionality.rawValue)
    }

    /// Drops the search and the tag filter, leaving the tab and the order alone.
    public func clearFilters() {
        query = ""
        selectedTags = []
        includeUntagged = false
        persistTagSelection()
    }

    // MARK: - Codes

    /// Display-only code lookup. Copy explicitly derives a fresh token without revealing it.
    /// Session concealment never changes the persisted entry or prevents explicit token copying.
    public func code(for entry: Entry, at date: Date) -> String? {
        guard isRevealed(entry) else { return nil }
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
            persistedRevision += 1
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
        var visibility: [Entry: Bool] = [:]
        var nextRevealedDates: [Entry: Date] = [:]
        for (index, entry) in proposed.enumerated() {
            if entries.contains(entry) {
                visibility[entry] = isRevealed(entry)
                if let openedAt = revealedAtDates[entry] { nextRevealedDates[entry] = openedAt }
            } else if proposed.count == entries.count, entries.indices.contains(index), !proposed.contains(entries[index]) {
                let previous = entries[index]
                visibility[entry] = isRevealed(previous)
                if let openedAt = revealedAtDates[previous] { nextRevealedDates[entry] = openedAt }
            }
        }
        sessionVisibility = visibility
        revealedAtDates = nextRevealedDates
        entries = proposed
        persistedRevision += 1
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
        var replacement = entry
        replacement.preserveExtensionFields(from: entries[index])
        var proposed = entries
        proposed[index] = replacement
        return commit(proposed)
    }

    /// Updates the tags on any entry kind (including `PASSWORD`, `PASSWORD_ARCHIVE`, and `EMAIL`).
    @discardableResult
    public func setTags(at index: Int, tags: [String]) -> Bool {
        guard entries.indices.contains(index) else { return false }
        let cleaned = EntryDraft.cleanedTags(tags)
        guard cleaned.count <= InputPolicy.maxTags else {
            message = "Use at most \(InputPolicy.maxTags) tags."
            return false
        }
        for tag in cleaned {
            do {
                try InputPolicy.validateText(tag, limit: InputPolicy.maxTagChars)
            } catch {
                message = "Tags may contain at most \(InputPolicy.maxTagChars) characters."
                return false
            }
        }
        var proposed = entries
        proposed[index].setTags(cleaned)
        return commit(proposed)
    }

    /// Updates or resets (`""`) the brand-logo thumbnail name on an entry.
    @discardableResult
    public func setThumbnail(at index: Int, name: String) -> Bool {
        guard entries.indices.contains(index) else { return false }
        if !name.isEmpty, !ThumbnailCatalog.names.contains(name) {
            message = "Unknown brand logo name."
            return false
        }
        var proposed = entries
        proposed[index].setThumbnail(name)
        return commit(proposed)
    }

    /// Swaps or moves an entry within the displayed category slice when `canReorder` is true.
    @discardableResult
    public func moveDisplayed(from displayedOrigin: Int, to displayedDestination: Int) -> Bool {
        guard canReorder else { return false }
        let visible = visibleIndices
        guard visible.indices.contains(displayedOrigin),
              visible.indices.contains(displayedDestination),
              displayedOrigin != displayedDestination else {
            return false
        }
        var proposed = entries
        let moving = proposed[visible[displayedOrigin]]
        var reorderedSlice = visible.map { proposed[$0] }
        reorderedSlice.remove(at: displayedOrigin)
        reorderedSlice.insert(moving, at: displayedDestination)
        for (slot, sourceIndex) in visible.enumerated() {
            proposed[sourceIndex] = reorderedSlice[slot]
        }
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
        let current = entries[index]
        if visible {
            var proposed = entries
            proposed[index].setDetailsVisible(true)
            let previousOverride = sessionVisibility[current]
            let previousDate = revealedAtDates[current]
            sessionVisibility[current] = true
            revealedAtDates[current] = Date()
            guard commit(proposed) else {
                sessionVisibility[current] = previousOverride
                revealedAtDates[current] = previousDate
                return false
            }
            return true
        }
        var proposed = entries
        proposed[index].setDetailsVisible(false)
        sessionVisibility[current] = false
        revealedAtDates.removeValue(forKey: current)
        if commit(proposed) { return true }
        entries[index].setDetailsVisible(false)
        return false
    }

    /// Re-masks revealed entries whose configured reveal timeout (`ui_tap_to_reveal_timeout`) elapsed.
    @discardableResult
    public func expireRevealedEntries(now: Date = Date()) -> Bool {
        guard isUnlocked, tapToRevealTimeoutSeconds > 0, !entries.isEmpty else { return false }
        let timeout = TimeInterval(tapToRevealTimeoutSeconds)
        var changed = false
        for index in entries.indices {
            let entry = entries[index]
            guard isRevealed(entry) else {
                revealedAtDates.removeValue(forKey: entry)
                continue
            }
            guard let openedAt = revealedAtDates[entry] else {
                revealedAtDates[entry] = now
                continue
            }
            if now.timeIntervalSince(openedAt) >= timeout {
                _ = setDetailsVisible(at: index, false)
                changed = true
            }
        }
        return changed
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
            return ExportedBackup(
                name: BackupFileNames.encrypted(now, appendTimestamp: backupAppendDateTime),
                data: data)
        } catch {
            message = "The backup could not be written."
            return nil
        }
    }

    /// Exact file-copy backup. No alternate backup password or second encryption layer.
    public func exportVaultSnapshot(now: Date = Date()) -> ExportedBackup? {
        guard isUnlocked else { return nil }
        do {
            let suffix = backupAppendDateTime ? "-" + BackupFileNames.timestamp(now) : ""
            return ExportedBackup(name: "OfflineOTP-vault" + suffix + ".offlineotp", data: try vault.snapshot(key: key))
        } catch {
            message = "Full file copies require a self-contained vault. Upgrade a legacy password vault by changing its vault password first."
            return nil
        }
    }

    public func migrateAndExportVaultSnapshot(password: String) -> ExportedBackup? {
        guard isUnlocked else { return nil }
        do {
            try vault.migrateToContainer(password: password)
            return exportVaultSnapshot()
        } catch {
            message = "Migration failed. Check the current vault password and available storage. The original vault has not been intentionally replaced on failure."
            return nil
        }
    }

    /// Previews the entry count of a password-encrypted backup without mutating the vault.
    public func previewEncryptedBackupCount(encrypted data: Data, password: String) -> Int? {
        guard isUnlocked else { return nil }
        do {
            let document = try EncryptedBackup.open(password: password, data: data)
            let decoded = try VaultDocument.decode(document)
            guard !decoded.isEmpty else {
                message = BackupWording.emptyBackup
                return nil
            }
            return decoded.count
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

    /// True when `data` is a LinSCP `LSCPVLT2` encrypted vault requiring a backup password.
    public func isLinScpEncryptedVault(_ data: Data) -> Bool {
        LinScpVault.isEncryptedVault(data)
    }

    /// True when `data` is a LinSCP `sessions.ini` (or bundle) requiring a `sessions.key` secret.
    public func needsLinScpMasterKey(_ data: Data) -> Bool {
        LinScpVault.needsSessionMasterKey(data)
    }

    /// Exports SSH keys and login sessions in LinSCP's `LSCPVLT2` encrypted credential-vault format.
    public func exportLinScpVault(password: String, now: Date = Date(), selected: [Entry]? = nil, plainText: Bool = false) -> ExportedBackup? {
        guard isUnlocked else { return nil }
        guard plainText || (!password.isEmpty && password.count <= InputPolicy.maxVaultPasswordChars) else {
            message = "The backup password is empty or too long."
            return nil
        }
        do {
            let chosen = selected ?? entries
            guard selected == nil || chosen.allSatisfy({ candidate in entries.contains { $0.fields == candidate.fields } }) else { return nil }
            let data: Data
            if plainText { data = try LinScpVault.exportPlain(entries: chosen) }
            else { data = try LinScpVault.sealWithFreshParameters(entries: chosen, password: password) }
            return ExportedBackup(name: LinScpVault.exportFilename(now, plainText: plainText), data: data)
        } catch LinScpVaultError.emptyCredentials {
            message = "There are no keys or login sessions to export."
            return nil
        } catch {
            message = "The LinSCP vault could not be written."
            return nil
        }
    }

    /// Selection is staged read-only in the sheet; merge persists before adoption.
    public func importLinScpSelection(_ selected: [Entry]) -> RestorePlan? {
        guard isUnlocked, !selected.isEmpty, selected.allSatisfy({ $0.kind == .sshKey || $0.kind == .password }) else { return nil }
        do { return install(document: try VaultDocument.encode(selected), replace: false) }
        catch { message = "Invalid LinSCP selection."; return nil }
    }

    /// Imports SSH keys and saved sessions from a LinSCP `LSCPVLT2` vault, `BackupBundle`, or `sessions.ini`.
    public func importLinScp(
        data: Data,
        password: String? = nil,
        masterKeyText: String? = nil,
        replace: Bool = false
    ) -> RestorePlan? {
        do {
            let keyData = masterKeyText?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .data(using: .utf8)
            let imported = try LinScpVault.importData(
                data,
                password: password,
                masterKeyData: (keyData?.isEmpty == false) ? keyData : nil
            )
            let document = try VaultDocument.encode(imported)
            return install(document: document, replace: replace)
        } catch VaultCryptoError.authenticationFailed {
            message = BackupWording.wrongPassword
            return nil
        } catch EncryptedBackupError.invalidPassword {
            message = "The backup password is empty or too long."
            return nil
        } catch LinScpVaultError.missingMasterKey, LinScpVaultError.invalidMasterKey {
            message = "A valid Base64 LinSCP sessions.key is required to decrypt saved passwords."
            return nil
        } catch {
            message = "That file could not be imported as LinSCP credentials."
            return nil
        }
    }

    // MARK: - OpenPGP, Browser Passwords, FairEmail & Thunderbird

    /// Whether this build can actually run OpenPGP. The backup sheet asks before enabling its
    /// buttons, mirroring Android's disabled-buttons-plus-warning behaviour, so a build without the
    /// Rust XCFramework reports that instead of letting the user pick files and then fail.
    public var openPgpAvailability: OpenPgpAvailability {
        .inApp(backendAvailable: openPgp.isAvailable)
    }

    /// Detached encoder: the worker borrows no live entries or mutable presentation state.
    func automaticOpenPgpEncoder() -> (String, [Data]) throws -> Data {
        let port = openPgp
        return { document, keys in try OpenPgpBackupCodec.seal(port: port, plain: document, recipientKeyBlocks: keys) }
    }

    func automaticOpenPgpSignedEncoder() -> (String, [Data], Data, String) throws -> Data {
        let port = openPgp
        return { document, keys, signingKey, passphrase in
            guard let signer = port as? OpenPgpSigningPort else {
                throw OpenPgpError.backendUnavailable(reason: "This engine cannot sign backups.")
            }
            return try OpenPgpBackupCodec.sealSigned(port: signer, plain: document, recipientKeyBlocks: keys,
                signingKeyBlock: signingKey, passphrase: passphrase)
        }
    }

    /// Encrypts the vault document to one or more OpenPGP recipient public keys (`.json.gpg`).
    public func exportOpenPgpBackup(recipientKeyBlocks: [Data], now: Date = Date()) -> ExportedBackup? {
        guard isUnlocked else { return nil }
        do {
            let document = try VaultDocument.encode(entries)
            let data = try OpenPgpBackupCodec.seal(port: openPgp, plain: document, recipientKeyBlocks: recipientKeyBlocks)
            return ExportedBackup(
                name: BackupFileNames.openPgp(now, appendTimestamp: backupAppendDateTime),
                data: data)
        } catch let error as OpenPgpError {
            message = error.errorDescription ?? "The OpenPGP backup could not be created."
            return nil
        } catch {
            message = "The OpenPGP backup could not be created."
            return nil
        }
    }

    /// Previews the entry count of an OpenPGP `.json.gpg` backup without mutating the vault.
    public func previewOpenPgpBackupCount(
        message cipherData: Data,
        secretKeyBlock: Data,
        passphrase: String?
    ) -> Int? {
        guard isUnlocked else { return nil }
        do {
            let document = try OpenPgpBackupCodec.open(
                port: openPgp,
                message: cipherData,
                secretKeyBlock: secretKeyBlock,
                passphrase: passphrase?.isEmpty == true ? nil : passphrase
            )
            let decoded = try VaultDocument.decode(document)
            guard !decoded.isEmpty else {
                message = BackupWording.emptyBackup
                return nil
            }
            return decoded.count
        } catch let error as OpenPgpError {
            message = error.errorDescription ?? BackupWording.unreadableBackup
            return nil
        } catch {
            message = BackupWording.unreadableBackup
            return nil
        }
    }

    /// Decrypts and merges/replaces an OpenPGP `.json.gpg` backup.
    public func restoreOpenPgpBackup(
        message cipherData: Data,
        secretKeyBlock: Data,
        passphrase: String?,
        replace: Bool = false
    ) -> RestorePlan? {
        do {
            let document = try OpenPgpBackupCodec.open(
                port: openPgp,
                message: cipherData,
                secretKeyBlock: secretKeyBlock,
                passphrase: passphrase?.isEmpty == true ? nil : passphrase
            )
            return install(document: document, replace: replace)
        } catch let error as OpenPgpError {
            message = error.errorDescription ?? BackupWording.unreadableBackup
            return nil
        } catch {
            message = BackupWording.unreadableBackup
            return nil
        }
    }

    /// Previews how many entries a browser password CSV or archive file would produce without installing them.
    public func previewBrowserPasswordsCount(
        filename: String,
        data: Data,
        mode: PasswordImport.Mode
    ) -> Int? {
        guard isUnlocked else { return nil }
        do {
            let parsed = try PasswordImport.entries(filename: filename, data: data, mode: mode)
            guard !parsed.isEmpty else {
                message = BackupWording.emptyBackup
                return nil
            }
            return parsed.count
        } catch {
            message = mode == .individual
                ? "That file is not a recognized browser password CSV."
                : "That password file could not be archived."
            return nil
        }
    }

    /// Imports browser password CSV rows (Chrome, Firefox, Safari) or archives an arbitrary password file.
    public func importBrowserPasswords(
        filename: String,
        data: Data,
        mode: PasswordImport.Mode,
        replace: Bool = false,
        now: Date = Date()
    ) -> RestorePlan? {
        do {
            var imported = try PasswordImport.entries(filename: filename, data: data, mode: mode)
            let stamp = EntryUsage.nowMillis(now)
            for idx in imported.indices {
                EntryUsage.markImported(&imported[idx], now: stamp)
            }
            let document = try VaultDocument.encode(imported)
            let plan = install(document: document, replace: replace)
            if plan != nil { category = .passwords }
            return plan
        } catch {
            message = mode == .individual
                ? "That file is not a recognized browser password CSV."
                : "That password file could not be archived."
            return nil
        }
    }

    /// Summary of exportable browser-CSV dialect groups, foreign passwords, and raw archives.
    public struct PasswordExportPlan {
        public struct Group {
            public let dialect: PasswordCsvExport.Dialect
            public let indices: [Int]
        }
        public let groups: [Group]
        public let foreignPasswords: Int
        public let archives: Int
    }

    /// Groups vault password entries by browser dialect (`CHROME`, `FIREFOX`, `SAFARI`), matching
    /// Desktop's `DesktopPasswordExport` and `:ui-foundation`'s `PasswordExportDialog`.
    public func passwordExportPlan() -> PasswordExportPlan {
        guard isUnlocked else {
            return PasswordExportPlan(groups: [], foreignPasswords: 0, archives: 0)
        }
        var buckets: [PasswordCsvExport.Dialect: [Int]] = [:]
        var foreign = 0
        var archives = 0
        for (idx, entry) in entries.enumerated() {
            if entry.kind == .passwordArchive {
                archives += 1
            } else if entry.kind == .password {
                switch PasswordCsvExport.support(entry) {
                case .exportable(let dialect):
                    buckets[dialect, default: []].append(idx)
                case .foreign:
                    foreign += 1
                case .notPassword:
                    break
                }
            }
        }
        let ordered = PasswordCsvExport.Dialect.allCases.compactMap { dialect -> PasswordExportPlan.Group? in
            guard let indices = buckets[dialect], !indices.isEmpty else { return nil }
            return PasswordExportPlan.Group(dialect: dialect, indices: indices)
        }
        return PasswordExportPlan(groups: ordered, foreignPasswords: foreign, archives: archives)
    }

    /// Exports one dialect group of browser password entries as a CSV file.
    public func exportBrowserPasswordsCsvGroup(
        indices: [Int],
        dialect: PasswordCsvExport.Dialect
    ) -> ExportedBackup? {
        guard isUnlocked else { return nil }
        let selected = indices.compactMap { entries.indices.contains($0) ? entries[$0] : nil }
        guard !selected.isEmpty else {
            message = "No browser passwords in that group are available."
            return nil
        }
        do {
            let data = try PasswordCsvExport.exportCsv(entries: selected, dialect: dialect)
            return ExportedBackup(name: "\(dialect.browserName.lowercased())-passwords.csv", data: data)
        } catch {
            message = "The browser password CSV could not be written."
            return nil
        }
    }

    /// Exports all revealed browser-imported `PASSWORD` entries as a CSV file, matching Android's
    /// `+ → Export passwords…` and the desktop client's `Export passwords as browser CSV…`.
    public func exportBrowserPasswordsCsv() -> ExportedBackup? {
        guard isUnlocked else { return nil }
        let revealed = entries.filter { isRevealed($0) }
        let exportable = revealed.compactMap { entry -> (Entry, PasswordCsvExport.Dialect)? in
            if case .exportable(let dialect) = PasswordCsvExport.support(entry) {
                return (entry, dialect)
            }
            return nil
        }
        guard !exportable.isEmpty else {
            message = "Reveal at least one browser-imported password entry to export."
            return nil
        }
        let chosen = PasswordCsvExport.agreedDialect(exportable.map(\.0))
        do {
            let data = try PasswordCsvExport.exportCsv(entries: exportable.map(\.0), dialect: chosen)
            return ExportedBackup(name: "passwords.csv", data: data)
        } catch {
            message = "The browser password CSV could not be written."
            return nil
        }
    }

    /// Exports the exact unparsed bytes of a stored `PASSWORD_ARCHIVE` entry under a safe fixed name.
    public func exportPasswordArchive(at index: Int) -> ExportedBackup? {
        guard isUnlocked, entries.indices.contains(index) else { return nil }
        let entry = entries[index]
        guard entry.kind == .passwordArchive, let bytes = entry.passwordArchiveBytes else {
            message = "That entry does not contain an exportable password archive."
            return nil
        }
        return ExportedBackup(name: "password-archive.bin", data: bytes)
    }

    /// Adds a manually entered website password entry using the shared Chrome-compatible column schema.
    @discardableResult
    public func addPasswordEntry(
        issuer: String = "",
        url: String = "",
        username: String = "",
        password: String,
        note: String = "",
        tags: [String] = [],
        thumbnail: String = "",
        now: Date = Date()
    ) -> Bool {
        do {
            let entry = try PasswordCsvExport.create(
                issuer: issuer,
                url: url,
                username: username,
                password: password,
                note: note,
                tags: tags,
                thumbnail: thumbnail,
                now: EntryUsage.nowMillis(now)
            )
            return add(entry)
        } catch {
            message = "The password entry fields are incomplete or invalid."
            return false
        }
    }

    /// Adds a manually entered FairEmail-compatible email account to the vault.
    @discardableResult
    public func addEmailAccount(
        _ account: EmailMaterial.Account,
        tags: [String] = [],
        thumbnail: String = "",
        now: Date = Date()
    ) -> Bool {
        do {
            var entry = try Entry.email(account: account, tags: tags, now: EntryUsage.nowMillis(now))
            if !thumbnail.isEmpty {
                guard ThumbnailCatalog.names.contains(thumbnail) else {
                    message = "The email account settings are incomplete or invalid."
                    return false
                }
                entry.setThumbnail(thumbnail)
            }
            return add(entry)
        } catch {
            message = "The email account settings are incomplete or invalid."
            return false
        }
    }

    /// True when `data` is an encrypted FairEmail `.efb` backup requiring a password.
    public func needsFairEmailPassword(_ data: Data) -> Bool {
        EmailMaterial.needsPassword(data)
    }

    /// Imports FairEmail `.json`, gzipped JSON, or `.efb` (v0 & v1) encrypted backups.
    public func importFairEmailBackup(
        data: Data,
        password: String? = nil,
        replace: Bool = false,
        now: Date = Date()
    ) -> RestorePlan? {
        do {
            let accounts = try EmailMaterial.importBackup(data, password: password)
            let stamp = EntryUsage.nowMillis(now)
            let imported = try accounts.map { try Entry.email(account: $0, now: stamp) }
            let document = try VaultDocument.encode(imported)
            let plan = install(document: document, replace: replace)
            if plan != nil { category = .emails }
            return plan
        } catch EmailCredentialError.passwordRequired {
            message = "Enter the password this FairEmail backup was encrypted with."
            return nil
        } catch EmailCredentialError.decryptionFailed {
            message = BackupWording.wrongPassword
            return nil
        } catch {
            message = "That file could not be read as a FairEmail backup."
            return nil
        }
    }

    /// Previews the account count of a FairEmail backup without mutating the vault.
    public func previewFairEmailBackupCount(data: Data, password: String? = nil) -> Int? {
        guard isUnlocked else { return nil }
        do {
            let accounts = try EmailMaterial.importBackup(data, password: password)
            guard !accounts.isEmpty else {
                message = BackupWording.emptyBackup
                return nil
            }
            return accounts.count
        } catch EmailCredentialError.passwordRequired {
            message = "Enter the password this FairEmail backup was encrypted with."
            return nil
        } catch EmailCredentialError.decryptionFailed {
            message = BackupWording.wrongPassword
            return nil
        } catch {
            message = "That file could not be read as a FairEmail backup."
            return nil
        }
    }

    /// Exports all or selected `EMAIL` entries in FairEmail's unencrypted JSON backup format.
    public func exportFairEmailBackup(now: Date = Date(), selected: [Entry]? = nil) -> ExportedBackup? {
        guard isUnlocked else { return nil }
        do {
            let source = selected ?? entries
            let accounts = try source.filter { $0.kind == .email }.map { try $0.emailAccount() }
            guard !accounts.isEmpty else {
                message = "There are no email accounts in this vault to export."
                return nil
            }
            let data = try EmailMaterial.exportBackup(accounts)
            return ExportedBackup(name: BackupFileNames.fairEmail(now), data: data)
        } catch {
            message = "The FairEmail backup could not be written."
            return nil
        }
    }

    /// Previews how many email credentials a Thunderbird CSV file contains without installing them.
    public func previewThunderbirdBackupCount(data: Data) -> Int? {
        guard isUnlocked else { return nil }
        do {
            let parsed = try ThunderbirdImport.entries(from: data)
            guard !parsed.isEmpty else {
                message = BackupWording.emptyBackup
                return nil
            }
            return parsed.count
        } catch {
            message = "That file is not a valid Thunderbird CSV export."
            return nil
        }
    }

    /// Imports Thunderbird CSV logins into `EMAIL` vault entries.
    public func importThunderbirdBackup(
        data: Data,
        replace: Bool = false,
        now: Date = Date()
    ) -> RestorePlan? {
        do {
            var imported = try ThunderbirdImport.entries(from: data)
            let stamp = EntryUsage.nowMillis(now)
            for idx in imported.indices {
                EntryUsage.markImported(&imported[idx], now: stamp)
            }
            let document = try VaultDocument.encode(imported)
            let plan = install(document: document, replace: replace)
            if plan != nil { category = .emails }
            return plan
        } catch {
            message = "That file is not a valid Thunderbird CSV export."
            return nil
        }
    }

    /// Exports all or selected `EMAIL` (and legacy mail-scheme `PASSWORD`) entries as a Thunderbird CSV file.
    public func exportThunderbirdBackup(now: Date = Date(), selected: [Entry]? = nil) -> ExportedBackup? {
        guard isUnlocked else { return nil }
        do {
            let data = try ThunderbirdImport.exportCsv(entries: selected ?? entries)
            return ExportedBackup(name: BackupFileNames.thunderbird(now), data: data)
        } catch EmailCredentialError.noExportableAccounts {
            message = "There are no email credentials in this vault to export."
            return nil
        } catch {
            message = "The Thunderbird CSV export could not be written."
            return nil
        }
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
    private var hardwareRecordURL: URL { directory.appendingPathComponent("hardware-unlock.b64") }
    var hasHardwareUnlockRecord: Bool { FileManager.default.fileExists(atPath: hardwareRecordURL.path) }
    func hardwareUnlockEnvelope() throws -> Data? {
        guard hasHardwareUnlockRecord else { return nil }
        let values = try hardwareRecordURL.resourceValues(forKeys: [.isSymbolicLinkKey, .isRegularFileKey])
        guard values.isSymbolicLink != true, values.isRegularFile == true else { throw VaultCryptoError.invalidParameters }
        let handle = try FileHandle(forReadingFrom: hardwareRecordURL)
        defer { try? handle.close() }
        let encoded = try handle.read(upToCount: 2049) ?? Data()
        guard encoded.count <= 2048, let text = String(data: encoded, encoding: .ascii),
              let envelope = Data(base64Encoded: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              HardwareUnlockEnvelope.request(envelope) != nil else { throw VaultCryptoError.invalidParameters }
        return envelope
    }
    func hardwareEnrollmentKey() throws -> Data {
        guard isUnlocked, !key.isEmpty else { throw VaultCryptoError.invalidKey }
        return Data(key)
    }
    func installHardwareUnlockEnvelope(_ envelope: Data) throws {
        guard isUnlocked, HardwareUnlockEnvelope.request(envelope) != nil else { throw VaultCryptoError.invalidParameters }
        let encoded = Data(envelope.base64EncodedString().utf8)
        guard encoded.count <= 2048 else { throw VaultCryptoError.invalidParameters }
        try FileByteStore(url: hardwareRecordURL).writeAtomic(encoded)
        try FileManager.default.setAttributes([.posixPermissions: 0o600, .protectionKey: FileProtectionType.complete], ofItemAtPath: hardwareRecordURL.path)
    }
    func removeHardwareUnlockEnvelope() throws {
        guard isUnlocked else { throw VaultCryptoError.invalidKey }
        if hasHardwareUnlockRecord { try FileManager.default.removeItem(at: hardwareRecordURL) }
    }
    func unlockWithHardwareKey(_ candidate: Data) -> Bool {
        guard !isUnlocked, hasVault else { return false }
        do {
            let unlocked = try vault.openWithKey(Array(candidate))
            key = unlocked.key; entries = unlocked.entries; isUnlocked = true; beginSession(); message = nil
            return true
        } catch { message = "The hardware key did not open this vault. Use the vault password and re-enroll if its key changed."; return false }
    }

    var hasAutomaticBackupConfiguration: Bool { AutomaticBackupCredentialStore(directory: directory).exists }
    func loadAutomaticBackupConfiguration() throws -> AutomaticBackupConfiguration? {
        guard isUnlocked, !key.isEmpty else { throw VaultCryptoError.invalidKey }
        return try AutomaticBackupCredentialStore(directory: directory).load(key: key)
    }
    func saveAutomaticBackupConfiguration(_ configuration: AutomaticBackupConfiguration) throws {
        guard isUnlocked, !key.isEmpty else { throw VaultCryptoError.invalidKey }
        try AutomaticBackupCredentialStore(directory: directory).save(configuration, key: key)
    }
    func forgetAutomaticBackupConfiguration() throws {
        guard isUnlocked else { throw VaultCryptoError.invalidKey }
        try AutomaticBackupCredentialStore(directory: directory).forget()
    }

    public var folderPath: String { directory.path }
}
