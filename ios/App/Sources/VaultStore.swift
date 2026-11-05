import Combine
import Foundation
import OfflineOTPKit

/// The app's view of one vault: locked or unlocked, plus the messages a person can act on.
///
/// This is the only place in the app that touches the vault files. It is a plain `ObservableObject`
/// driven from the main actor by SwiftUI actions, and it is used from the app's unit tests the same
/// way, so the wiring — where the files live, what a wrong password says, what a save does — is
/// covered without a UI.
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
    /// A sentence for the lock screen: a wrong password, a missing vault, an unwritable folder.
    @Published public private(set) var message: String?

    private let vault: PasswordVault
    private let directory: URL
    private var key: [UInt8] = []

    /// `directory` is injectable so the app tests can use a temporary folder instead of Documents.
    public init(directory: URL = VaultStore.defaultDirectory()) {
        self.directory = directory
        let vaultFile = FileByteStore(url: directory.appendingPathComponent(VaultStore.vaultFilename))
        let backupFile = FileByteStore(
            url: directory.appendingPathComponent(VaultStore.backupFilename))
        let settings = PropertyListSettings(
            url: directory.appendingPathComponent(VaultStore.settingsFilename))
        self.vault = PasswordVault(vaultFile: vaultFile, backupFile: backupFile, settings: settings)
        self.hasVault = vault.isInitialized
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
        } catch VaultWriteError.alreadyExists {
            hasVault = true
            message = "A vault already exists in this folder."
        } catch {
            message = "The vault could not be created."
        }
    }

    /// Forgets the key and the entries; the files stay where they are.
    public func lock() {
        if isUnlocked, !key.isEmpty {
            save()
        }
        key = []
        entries = []
        isUnlocked = false
        message = nil
    }

    // MARK: - Entries

    /// The code to show for an entry right now, or nil when it has none (a note, an SSH key).
    public func code(for entry: Entry, at date: Date) -> String? {
        do {
            return try entry.code(unixTime: Int64(date.timeIntervalSince1970))
        } catch {
            return nil
        }
    }

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

    /// Adds an entry at the end, where a new entry goes on the other clients too.
    public func add(_ entry: Entry) {
        guard isUnlocked else { return }
        entries.append(entry)
        save()
    }

    /// Removes one entry by position, leaving the rest in their stored order.
    public func remove(at index: Int) {
        guard isUnlocked, entries.indices.contains(index) else { return }
        entries.remove(at: index)
        save()
    }

    /// Replaces one entry, keeping the stored order — an edit must not reorder the vault.
    public func update(at index: Int, with entry: Entry) {
        guard entries.indices.contains(index) else { return }
        entries[index] = entry
        save()
    }

    /// The folder this store reads and writes, for the diagnostics line on the lock screen.
    public var folderPath: String { directory.path }
}
