import Foundation

/// Portable export/import of this client's preferences, the Swift mirror of `PreferencesExport.kt`.
///
/// The rules are the Kotlin object's rules, because a preferences file must mean the same thing on
/// every client that reads it:
///
/// - A value is written with an explicit type, never as a bare string, so an importer can never
///   flip a checkbox because `"1"` happened to look true.
/// - A document is addressed to *one* client (`platform`). iOS keeps only app settings beside its
///   vault — the inactivity delay and whether quick unlock is enrolled — so a file is small, and an
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
        Setting(key: inactivityLockKey, kind: .integer),
    ]

    /// Mirrors `VaultStore.inactivitySettingKey`.
    public static let inactivityLockKey = "ui_inactivity_lock_seconds"
    /// Mirrors `VaultStore.biometricSettingKey`; device-bound, listed so the reason is visible.
    public static let quickUnlockKey = "biometric_unlock"

    /// Keys that exist on this client but cannot leave it, with the reason.
    public static let neverExported: [(key: String, reason: String)] = [
        (quickUnlockKey, "quick-unlock enrolment is a Keychain item for this device's biometry"),
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

    public static func export(_ port: SettingsPort, platform: String = platformIos) throws -> Export {
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
        return Export(bytes: data, keysWritten: written, skipped: skipped, includesSecrets: false)
    }

    /// Applies a document previously written by `export(_:platform:)`.
    ///
    /// Named `apply` rather than `import` to match the Kotlin object's method without shadowing a
    /// declaration keyword; the operation is the same, and a caller reads it as "apply this file".
    public static func apply(_ bytes: Data, into port: SettingsPort,
                             platform: String = platformIos) throws -> Import {
        guard !bytes.isEmpty, bytes.count <= maxBytes else { throw PreferencesError.invalidSize }
        guard let parsed = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any] else {
            throw PreferencesError.invalidDocument
        }
        guard parsed["format"] as? String == format else { throw PreferencesError.invalidDocument }
        guard (parsed["version"] as? NSNumber)?.intValue == version else {
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
        for (entry, value) in accepted { port.write(entry.key, value) }
        return Import(applied: accepted.count, unknownKeys: unknown.sorted(),
                      refusedKeys: refused.sorted(), includesSecrets: false)
    }

    private static func jsonValue(_ entry: Setting, _ value: Value) throws -> Any {
        switch entry.kind {
        case .integer:
            guard case .integer(let number) = value else { throw PreferencesError.typeMismatch(entry.key) }
            return number
        case .string:
            guard case .text(let text) = value else { throw PreferencesError.typeMismatch(entry.key) }
            guard text.count <= maxStringChars else { throw PreferencesError.typeMismatch(entry.key) }
            return text
        case .boolean:
            guard case .boolean(let flag) = value else { throw PreferencesError.typeMismatch(entry.key) }
            return flag
        case .stringSet:
            guard case .textSet(let values) = value else { throw PreferencesError.typeMismatch(entry.key) }
            guard values.count <= maxSetValues else { throw PreferencesError.typeMismatch(entry.key) }
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
            return .integer(number.intValue)
        case .string:
            guard let text = raw as? String else { throw PreferencesError.typeMismatch(entry.key) }
            guard text.count <= maxStringChars else { throw PreferencesError.typeMismatch(entry.key) }
            return .text(text)
        case .boolean:
            guard let flag = raw as? NSNumber else { throw PreferencesError.typeMismatch(entry.key) }
            return .boolean(flag.boolValue)
        case .stringSet:
            guard let values = raw as? [String] else { throw PreferencesError.typeMismatch(entry.key) }
            guard values.count <= maxSetValues else { throw PreferencesError.typeMismatch(entry.key) }
            return .textSet(values)
        case .secret, .deviceBound:
            throw PreferencesError.typeMismatch(entry.key)
        }
    }
}
