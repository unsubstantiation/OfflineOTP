import Foundation

/// Portable export/import of this client's preferences, the Swift mirror of `PreferencesExport.kt`.
///
/// The rules are the Kotlin object's rules, because a preferences file must mean the same thing on
/// every client that reads it:
///
/// - A value is written with an explicit type, never as a bare string, so an importer can never
///   flip a checkbox because `"1"` happened to look true.
/// - A document is addressed to *one* client (`platform`). iOS keeps only app settings beside its
///   vault — sort order and inactivity delay are portable, quick unlock is device-bound — so an
///   import from Android or the desktop is refused with a reason rather than half-applied from the
///   handful of keys that happen to collide.
/// - The vault's own material (`auth_salt`, `auth_credentials`, the KDF parameters) lives in the
///   same store but belongs to the vault, not to the app: it is never exported and never named in a
///   file, because a shareable file should not advertise how a particular vault is protected.
/// - A document is validated completely before anything is written, so a truncated or foreign file
///   changes nothing.
public enum PreferencesExport {

    public static let format = "offlineotp.preferences"
    public static let version = 1

    public static let platformAndroid = "android"
    public static let platformDesktop = "desktop"
    public static let platformIos = "ios"

    public static let maxBytes = 512 * 1024
    public static let maxKeys = 512
    public static let maxStringChars = 64 * 1024
    public static let maxSetValues = 256

    public enum Kind: String {
        case boolean = "BOOLEAN"
        case integer = "INTEGER"
        case string = "STRING"
        case stringSet = "STRING_SET"
        case secret = "SECRET"
        case deviceBound = "DEVICE_BOUND"
    }

    public struct Setting: Equatable {
        public let key: String
        public let kind: Kind

        public init(key: String, kind: Kind) {
            self.key = key
            self.kind = kind
        }
    }

    /// The iOS client's portable settings, and the whole of them.
    ///
    /// Everything else in `vault.plist` either belongs to the vault (`auth_*`) or is bound to this
    /// device — the quick-unlock enrolment is a Keychain item for this device's biometry.
    public static let catalog: [Setting] = [
        Setting(key: sortModeKey, kind: .string),
        Setting(key: inactivityLockKey, kind: .integer),
        Setting(key: cardLayoutKey, kind: .string),
        Setting(key: labelDisplayKey, kind: .string),
        Setting(key: labelSizeSpKey, kind: .integer),
        Setting(key: tagFunctionalityKey, kind: .string),
        Setting(key: hideIssuerKey, kind: .boolean),
        Setting(key: showPrevTokenKey, kind: .boolean),
        Setting(key: showIndividualTimeoutsKey, kind: .boolean),
        Setting(key: hideGlobalTimeoutKey, kind: .boolean),
        Setting(key: showThumbnailsKey, kind: .boolean),
        Setting(key: thumbnailSizeDpKey, kind: .integer),
        Setting(key: highlightTokenKey, kind: .boolean),
        Setting(key: tapSingleKey, kind: .string),
        Setting(key: tapDoubleKey, kind: .string),
        Setting(key: tapToRevealTimeoutKey, kind: .integer),
        Setting(key: focusSearchOnStartKey, kind: .boolean),
        Setting(key: relockBackgroundKey, kind: .boolean),
        Setting(key: backupAppendDateTimeKey, kind: .boolean),
        Setting(key: splitGroupSizeKey, kind: .integer),
        Setting(key: searchIncludesKey, kind: .stringSet),
        Setting(key: selectedTagsKey, kind: .stringSet),
        Setting(key: untaggedSelectedKey, kind: .boolean),
    ]

    /// Mirrors `SortMode.settingsKey` and the desktop client's `PreferencesExport.UI_SORT_MODE`.
    public static let sortModeKey = "ui_sort_mode"
    /// Mirrors `VaultStore.inactivitySettingKey`.
    public static let inactivityLockKey = "ui_inactivity_lock_seconds"
    /// Mirrors `VaultStore.cardLayoutSettingKey` and `PreferencesExport.UI_CARD_LAYOUT`.
    public static let cardLayoutKey = "ui_card_layout"
    /// Mirrors `VaultStore.labelDisplaySettingKey` and `PreferencesExport.UI_LABEL_DISPLAY`.
    public static let labelDisplayKey = "ui_label_display"
    /// Mirrors `VaultStore.labelSizeSpSettingKey` and `PreferencesExport.UI_LABEL_SIZE_SP`.
    public static let labelSizeSpKey = "ui_label_size_sp"
    /// Mirrors `VaultStore.tagFunctionalitySettingKey` and `PreferencesExport.UI_TAG_FUNCTIONALITY`.
    public static let tagFunctionalityKey = "ui_tag_functionality"
    /// Mirrors `VaultStore.hideIssuerSettingKey`.
    public static let hideIssuerKey = "ui_hide_issuer"
    /// Mirrors `VaultStore.showPrevTokenSettingKey` and `PreferencesExport.UI_SHOW_PREV_TOKEN`.
    public static let showPrevTokenKey = "ui_show_prev_token"
    /// Mirrors `VaultStore.showIndividualTimeoutsSettingKey` and `PreferencesExport.UI_SHOW_INDIVIDUAL_TIMEOUTS`.
    public static let showIndividualTimeoutsKey = "ui_show_individual_timeouts"
    /// Mirrors `VaultStore.hideGlobalTimeoutSettingKey` and `PreferencesExport.UI_HIDE_GLOBAL_TIMEOUT`.
    public static let hideGlobalTimeoutKey = "ui_hide_global_timeout"
    /// Mirrors `VaultStore.showThumbnailsSettingKey` and `PreferencesExport.UI_SHOW_THUMBNAILS`.
    public static let showThumbnailsKey = "ui_show_thumbnails"
    /// Mirrors `VaultStore.thumbnailSizeDpSettingKey` and `PreferencesExport.UI_THUMBNAIL_SIZE_DP`.
    public static let thumbnailSizeDpKey = "ui_thumbnail_size_dp"
    /// Mirrors `VaultStore.highlightTokenSettingKey` and `PreferencesExport.UI_HIGHLIGHT_TOKEN`.
    public static let highlightTokenKey = "ui_highlight_token"
    /// Mirrors `VaultStore.tapSingleSettingKey` and `PreferencesExport.UI_TAP_SINGLE`.
    public static let tapSingleKey = "ui_tap_single"
    /// Mirrors `VaultStore.tapDoubleSettingKey` and `PreferencesExport.UI_TAP_DOUBLE`.
    public static let tapDoubleKey = "ui_tap_double"
    /// Mirrors `VaultStore.tapToRevealTimeoutSettingKey` and `PreferencesExport.UI_TAP_TO_REVEAL_TIMEOUT`.
    public static let tapToRevealTimeoutKey = "ui_tap_to_reveal_timeout"
    /// Mirrors `VaultStore.focusSearchOnStartSettingKey` and `PreferencesExport.UI_FOCUS_SEARCH_ON_START`.
    public static let focusSearchOnStartKey = "ui_focus_search_on_start"
    /// Mirrors `VaultStore.relockBackgroundSettingKey` and `PreferencesExport.UI_RELOCK_BACKGROUND`.
    public static let relockBackgroundKey = "ui_relock_background"
    /// Mirrors `VaultStore.backupAppendDateTimeSettingKey` and `PreferencesExport.UI_BACKUP_APPEND_DATE_TIME`.
    public static let backupAppendDateTimeKey = "ui_backup_append_date_time"
    /// Mirrors `VaultStore.splitGroupSizeSettingKey` and `PreferencesExport.UI_SPLIT_GROUP_SIZE`.
    public static let splitGroupSizeKey = "ui_split_group_size"
    /// Mirrors `VaultStore.searchIncludesSettingKey` and `PreferencesExport.UI_SEARCH_INCLUDES`.
    public static let searchIncludesKey = "ui_search_includes"
    /// Mirrors `VaultStore.selectedTagsSettingKey` and `PreferencesExport.UI_SELECTED_TAGS`.
    public static let selectedTagsKey = "ui_selected_tags"
    /// Mirrors `VaultStore.untaggedSelectedSettingKey` and `PreferencesExport.UI_UNTAGGED_SELECTED`.
    public static let untaggedSelectedKey = "ui_untagged_selected"
    /// Mirrors `VaultStore.biometricSettingKey`; device-bound, listed so the reason is visible.
    public static let quickUnlockKey = "biometric_unlock"
    /// Mirrors `VaultStore.usedTokensShownSettingKey`; one-install dialog acknowledgement.
    public static let usedTokensShownKey = "ui_used_tokens_shown"

    /// Keys that exist on this client but cannot leave it, with the reason.
    public static let neverExported: [(key: String, reason: String)] = [
        (quickUnlockKey, "quick-unlock enrolment is a Keychain item for this device's biometry"),
        (usedTokensShownKey, "a one-install dialog acknowledgement"),
        (VaultSettingsKeys.authMethod, "the vault's unlock method belongs to the vault"),
        (VaultSettingsKeys.encryption, "the vault's encryption mode belongs to the vault"),
        (VaultSettingsKeys.authKdf, "the vault's KDF belongs to the vault"),
        (VaultSettingsKeys.authCredentials, "the vault's verifier belongs to the vault"),
        (VaultSettingsKeys.authSalt, "the vault's salt belongs to the vault"),
        (VaultSettingsKeys.authIterations, "the vault's KDF parameters belong to the vault"),
        (VaultSettingsKeys.authMemoryKiB, "the vault's KDF parameters belong to the vault"),
        (VaultSettingsKeys.authParallelism, "the vault's KDF parameters belong to the vault"),
    ]

    public static func setting(_ key: String) -> Setting? {
        catalog.first { $0.key == key }
    }

    public static func isDeviceBound(_ key: String) -> Bool {
        neverExported.contains { $0.key == key }
    }

    /// One typed preference value. `customStringConvertible` never prints a value.
    public enum Value: Equatable, CustomStringConvertible {
        case boolean(Bool)
        case integer(Int)
        case text(String)
        case textSet([String])

        public var description: String {
            switch self {
            case .boolean(let value): return "boolean(\(value))"
            case .integer(let value): return "integer(\(value))"
            case .text(let value): return "text(\(value.count) chars)"
            case .textSet(let values): return "textSet(\(values.count) values)"
            }
        }
    }

    /// The platform side of the exchange: a flat key/value store, plus how to store the two kinds.
    public protocol SettingsPort {
        func storedKeys() -> Set<String>
        func read(_ key: String) -> Value?
        func write(_ key: String, _ value: Value)
    }

    /// Native persistent stores commit a validated import in one operation and report IO failure.
    public protocol AtomicSettingsPort: SettingsPort {
        func writeBatch(_ values: [String: Value]) throws
    }

    public struct Export {
        public let bytes: Data
        public let keysWritten: Int
        public let skipped: [String]
        /// Always false on this client: it stores no backup password to carry.
        public let includesSecrets: Bool
    }

    public struct Import {
        public let applied: Int
        public let unknownKeys: [String]
        public let refusedKeys: [String]
        public let includesSecrets: Bool
    }

    public enum PreferencesError: Error, Equatable, CustomStringConvertible {
        case invalidSize
        case invalidEncoding
        case invalidDocument
        case unsupportedVersion
        case missingSettings
        case tooManyKeys
        case typeMismatch(String)
        case wrongPlatform(source: String, target: String)

        public var description: String {
            switch self {
            case .invalidSize: return "the file is empty or too large"
            case .invalidEncoding: return "the file is not UTF-8"
            case .invalidDocument: return "the file is not a preferences document"
            case .unsupportedVersion: return "the file was written by an unsupported version"
            case .missingSettings: return "the file has no settings"
            case .tooManyKeys: return "the file has too many settings"
            case .typeMismatch(let key): return "\(key) has the wrong type"
            case .wrongPlatform(let source, let target):
                return "preferences for \(source) cannot be applied to \(target)"
            }
        }
    }

    public static func export(_ port: SettingsPort, platform: String = platformIos, interchange: InterchangeAdapter? = nil) throws -> Export {
        var settings: [String: Any] = [:]
        var written = 0
        for entry in catalog {
            guard let value = port.read(entry.key) else { continue }
            settings[entry.key] = try jsonValue(entry, value)
            written += 1
        }
        // Keys this build cannot carry are named only when they are not device-bound: naming
        // "biometric_unlock" would disclose which security feature this device has configured.
        let known = Set(catalog.map { $0.key })
        let skipped = port.storedKeys()
            .filter { !known.contains($0) && !isDeviceBound($0) }
            .sorted()
        var document: [String: Any] = [
            "format": format,
            "version": version,
            "platform": platform,
            "includesSecrets": false,
            "settings": settings,
        ]
        if !skipped.isEmpty { document["skipped"] = skipped }
        let data = try JSONSerialization.data(withJSONObject: document, options: [.sortedKeys])
        guard data.count <= maxBytes else { throw PreferencesError.invalidSize }
        let checked = try interchange?.preferences(data, platform: platform) ?? data
        return Export(bytes: checked, keysWritten: written, skipped: skipped, includesSecrets: false)
    }

    /// Validates a preferences document without writing to any store, returning the import summary.
    public static func inspect(_ bytes: Data,
                               platform: String = platformIos, interchange: InterchangeAdapter? = nil) throws -> Import {
        let checked = try interchange?.preferences(bytes, platform: platform) ?? bytes
        let (_, summary) = try parseDocument(checked, platform: platform)
        return summary
    }

    /// Applies a document previously written by `export(_:platform:)`.
    ///
    /// Named `apply` rather than `import` to match the Kotlin object's method without shadowing a
    /// declaration keyword; the operation is the same, and a caller reads it as "apply this file".
    public static func apply(_ bytes: Data, into port: SettingsPort,
                             platform: String = platformIos, interchange: InterchangeAdapter? = nil) throws -> Import {
        let checked = try interchange?.preferences(bytes, platform: platform) ?? bytes
        let (accepted, summary) = try parseDocument(checked, platform: platform)
        if let atomic = port as? AtomicSettingsPort {
            try atomic.writeBatch(Dictionary(uniqueKeysWithValues: accepted.map { ($0.0.key, $0.1) }))
        } else {
            for (entry, value) in accepted { port.write(entry.key, value) }
        }
        return summary
    }

    private static func parseDocument(
        _ bytes: Data,
        platform: String
    ) throws -> ([(Setting, Value)], Import) {
        guard !bytes.isEmpty, bytes.count <= maxBytes else { throw PreferencesError.invalidSize }
        guard String(data: bytes, encoding: .utf8) != nil else {
            throw PreferencesError.invalidEncoding
        }
        guard let parsed = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any] else {
            throw PreferencesError.invalidDocument
        }
        guard parsed["format"] as? String == format else { throw PreferencesError.invalidDocument }
        guard let versionNumber = parsed["version"] as? NSNumber,
              !["c", "B", "d", "f"].contains(String(cString: versionNumber.objCType)),
              Int32(versionNumber.stringValue) == Int32(version) else {
            throw PreferencesError.unsupportedVersion
        }
        let source = parsed["platform"] as? String ?? ""
        guard source == platform else {
            throw PreferencesError.wrongPlatform(source: source, target: platform)
        }
        guard let settings = parsed["settings"] as? [String: Any] else {
            throw PreferencesError.missingSettings
        }
        guard settings.count <= maxKeys else { throw PreferencesError.tooManyKeys }

        // Parse everything first; only a document that fully validates reaches the store.
        var accepted: [(Setting, Value)] = []
        var unknown: [String] = []
        var refused: [String] = []
        for key in settings.keys.sorted() {
            if isDeviceBound(key) { refused.append(key); continue }
            guard let entry = setting(key) else { unknown.append(key); continue }
            accepted.append((entry, try value(entry, settings[key])))
        }
        return (accepted, Import(applied: accepted.count, unknownKeys: unknown.sorted(),
                                 refusedKeys: refused.sorted(), includesSecrets: false))
    }

    private static func jsonValue(_ entry: Setting, _ value: Value) throws -> Any {
        switch entry.kind {
        case .integer:
            guard case .integer(let number) = value, Int32(exactly: number) != nil else {
                throw PreferencesError.typeMismatch(entry.key)
            }
            return number
        case .string:
            guard case .text(let text) = value else { throw PreferencesError.typeMismatch(entry.key) }
            guard text.utf16.count <= maxStringChars else { throw PreferencesError.typeMismatch(entry.key) }
            return text
        case .boolean:
            guard case .boolean(let flag) = value else { throw PreferencesError.typeMismatch(entry.key) }
            return flag
        case .stringSet:
            guard case .textSet(let values) = value else { throw PreferencesError.typeMismatch(entry.key) }
            guard values.count <= maxSetValues, values.allSatisfy({ $0.utf16.count <= maxStringChars }) else { throw PreferencesError.typeMismatch(entry.key) }
            return values
        case .secret, .deviceBound:
            throw PreferencesError.typeMismatch(entry.key)
        }
    }

    private static func value(_ entry: Setting, _ raw: Any?) throws -> Value {
        guard let raw else { throw PreferencesError.typeMismatch(entry.key) }
        switch entry.kind {
        case .integer:
            guard let number = raw as? NSNumber else { throw PreferencesError.typeMismatch(entry.key) }
            let type = String(cString: number.objCType)
            guard type != "c", type != "B", type != "d", type != "f",
                  let exact = Int32(number.stringValue) else {
                throw PreferencesError.typeMismatch(entry.key)
            }
            return .integer(Int(exact))
        case .string:
            guard let text = raw as? String else { throw PreferencesError.typeMismatch(entry.key) }
            guard text.utf16.count <= maxStringChars else { throw PreferencesError.typeMismatch(entry.key) }
            return .text(text)
        case .boolean:
            guard let flag = raw as? NSNumber else { throw PreferencesError.typeMismatch(entry.key) }
            let type = String(cString: flag.objCType)
            guard type == "c" || type == "B" else { throw PreferencesError.typeMismatch(entry.key) }
            return .boolean(flag.boolValue)
        case .stringSet:
            guard let values = raw as? [String] else { throw PreferencesError.typeMismatch(entry.key) }
            guard values.count <= maxSetValues, values.allSatisfy({ $0.utf16.count <= maxStringChars }) else { throw PreferencesError.typeMismatch(entry.key) }
            return .textSet(values)
        case .secret, .deviceBound:
            throw PreferencesError.typeMismatch(entry.key)
        }
    }
}
