import Foundation

/// Default names for exported backup files — the port of `BackupFileNames` in `:core`.
///
/// A new backup is stamped with the moment it was written (`otp_accounts_2026-10-30_12-34-56.json.aes`)
/// instead of a fixed name, so repeated exports never overwrite an earlier backup by accident.
/// Android has always done this and the desktop client uses the same template, so a file written here
/// sorts and reads like one of theirs.
public enum BackupFileNames {
    /// Sortable, locale-independent timestamp. `Locale(identifier: "en_US_POSIX")` keeps the digits
    /// ASCII in every language, which is what the JVM's `Locale.ENGLISH` does.
    public static let timestampPattern = "yyyy-MM-dd_HH-mm-ss"
    public static let encryptedPrefix = "otp_accounts_"
    public static let encryptedSuffix = ".json.aes"
    public static let openPgpSuffix = ".json.gpg"
    public static let fairEmailPrefix = "fairemail_backup_"
    public static let thunderbirdPrefix = "thunderbird_logins_"
    public static let linScpPrefix = "linscp_vault_"

    public static func timestamp(_ date: Date = Date(), timeZone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = timeZone
        formatter.dateFormat = timestampPattern
        return formatter.string(from: date)
    }

    /// Dated encrypted-backup name, e.g. `otp_accounts_2026-10-30_12-34-56.json.aes`.
    public static func encrypted(_ date: Date = Date(), timeZone: TimeZone = .current) -> String {
        encryptedPrefix + timestamp(date, timeZone: timeZone) + encryptedSuffix
    }

    /// Dated OpenPGP backup name, e.g. `otp_accounts_2026-10-30_12-34-56.json.gpg`.
    public static func openPgp(_ date: Date = Date(), timeZone: TimeZone = .current) -> String {
        encryptedPrefix + timestamp(date, timeZone: timeZone) + openPgpSuffix
    }

    /// Dated FairEmail JSON backup name.
    public static func fairEmail(_ date: Date = Date(), timeZone: TimeZone = .current) -> String {
        fairEmailPrefix + timestamp(date, timeZone: timeZone) + ".json"
    }

    /// Dated Thunderbird CSV export name.
    public static func thunderbird(_ date: Date = Date(), timeZone: TimeZone = .current) -> String {
        thunderbirdPrefix + timestamp(date, timeZone: timeZone) + ".csv"
    }

    /// Dated LinSCP `LSCPVLT2` vault export name.
    public static func linScp(_ date: Date = Date(), timeZone: TimeZone = .current) -> String {
        linScpPrefix + timestamp(date, timeZone: timeZone) + encryptedSuffix
    }
}

public enum VaultRestoreMergeError: Error, Equatable {
    /// A backup with no entries is refused: restoring it would either wipe the vault (replace) or do
    /// nothing, and an empty file is far more likely to be a mistake than an intention.
    case emptyImport
    case duplicateEntries
}

/// What a restore produced: the list to save, and how many entries the vault gained.
public struct RestorePlan: Equatable {
    public let entries: [Entry]
    /// Imported entries the vault did not already hold — what a merge reports as "N new entries".
    public let added: Int
}

/// How a restored backup is combined with the entries already in the vault — the port of
/// `VaultRestoreMerge`.
///
/// The imported entries come first, an entry the vault already holds is not duplicated and keeps its
/// counters and metadata, and **replace** ignores the current entries entirely. Android's **Replace
/// existing entries** switch starts off, so merging is the default everywhere.
public enum VaultRestoreMerge {
    public static func plan(imported: [Entry], current: [Entry], replace: Bool) throws -> RestorePlan {
        // Android answers `EMPTY_IMPORT` rather than replacing a vault with an empty file.
        guard !imported.isEmpty else { throw VaultRestoreMergeError.emptyImport }
        // An entry the vault already holds is dropped from the import and the vault's own copy is
        // kept, counters and all; a replace takes the import verbatim and drops the current entries.
        let retained = replace ? Set<Entry>() : Set(current)
        let fresh = imported.filter { !retained.contains($0) }
        let merged = replace ? fresh : fresh + current
        // Two equal entries would be collapsed silently by the list on the next save, so the result
        // itself is checked — the rule `VaultRestoreMerge.plan` enforces on the JVM.
        guard Set(merged).count == merged.count else {
            throw VaultRestoreMergeError.duplicateEntries
        }
        return RestorePlan(entries: merged, added: fresh.count)
    }
}

/// The words a restore uses, in one place so the same question is asked the same way as on the phone
/// and the desktop client, and so a test can pin them.
public enum BackupWording {
    public static let replaceSwitch = "Replace existing entries"
    public static let replaceSwitchDetail = "When off, the backup is merged with the current entries."
    public static let restoreQuestion = "Restore this backup into the current vault?"
    public static let emptyBackup = "The backup contains no entries."
    public static let duplicateBackup =
        "The restored entries would duplicate entries already in the vault."
    public static let wrongPassword = "That password does not open this backup."
    public static let unreadableBackup = "The backup cannot be read."

    /// The result text. A merge reports only what it added, as Android's toast does.
    public static func restoreSummary(_ plan: RestorePlan, replace: Bool) -> String {
        if replace {
            return "Restored " + entries(plan.entries.count)
        }
        if plan.added == 0 {
            return "The backup had no entries the vault does not already hold."
        }
        let noun = plan.added == 1 ? "entry" : "entries"
        return "Merged \(plan.added) new \(noun) into the vault"
    }

    private static func entries(_ count: Int) -> String {
        "\(count) \(count == 1 ? "entry" : "entries")"
    }
}
