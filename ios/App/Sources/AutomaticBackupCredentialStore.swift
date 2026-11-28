import Foundation
import OfflineOTPKit
import OfflineOTPPresentation

/// Local record only: folder grants and backup password are encrypted together under the vault key.
struct AutomaticBackupConfiguration: Codable, CustomStringConvertible {
    let requiresSigning: Bool?
    let mode: String?
    let recipientKeys: [Data]?
    let version: Int
    let folder: String
    let bookmark: Data?
    let password: String
    let minutes: Int
    let keep: Int
    init(folder: URL, password: String, minutes: Int, keep: Int, recipientKeys: [Data] = [], requiresSigning: Bool = false, vaultCopy: Bool = false) throws {
        let access = folder.startAccessingSecurityScopedResource()
        defer { if access { folder.stopAccessingSecurityScopedResource() } }
        guard !vaultCopy || (password.isEmpty && recipientKeys.isEmpty && !requiresSigning) else { throw VaultCryptoError.invalidParameters }
        self.requiresSigning = requiresSigning
        version = vaultCopy ? 3 : recipientKeys.isEmpty ? 1 : 2
        mode = vaultCopy ? "vault-copy" : recipientKeys.isEmpty ? nil : "openpgp"; self.recipientKeys = recipientKeys.isEmpty ? nil : recipientKeys
        self.folder = folder.path; self.password = password; self.minutes = minutes; self.keep = keep
        bookmark = access ? try folder.bookmarkData(options: [.minimalBookmark], includingResourceValuesForKeys: nil, relativeTo: nil) : nil
    }
    static func validRecipients(_ keys: [Data]) -> Bool {
        guard keys.count <= 64, keys.allSatisfy({ $0.count <= 32768 }) else { return false }
        let arrays = keys.map { data -> KotlinByteArray in
            let bytes = KotlinByteArray(size: Int32(data.count))
            for (i, byte) in data.enumerated() { bytes.set(index: Int32(i), value: Int8(bitPattern: byte)) }
            return bytes
        }
        defer { for bytes in arrays { for i in 0..<bytes.size { bytes.set(index: i, value: 0) } } }
        return BackupAutomationPolicy().validRecipients(keys: arrays)
    }
    var vaultCopy: Bool { version == 3 && mode == "vault-copy" }
    var description: String { "AutomaticBackupConfiguration(redacted)" }
    func destination() throws -> URL {
        var encoded = try JSONEncoder().encode(self)
        defer { encoded.resetBytes(in: 0..<encoded.count) }
        guard let text = String(data: encoded, encoding: .utf8),
              AutomaticBackupConfigurationCodec().canonical(text: text) != nil,
              folder.hasPrefix("/"), folder.utf8.count <= 4096 else { throw VaultCryptoError.invalidParameters }
        guard let bookmark = bookmark else { return URL(fileURLWithPath: folder, isDirectory: true) }
        guard bookmark.count <= 32768 else { throw VaultCryptoError.invalidParameters }
        var stale = false
        let url = try URL(resolvingBookmarkData: bookmark, options: [.withoutUI], relativeTo: nil, bookmarkDataIsStale: &stale)
        guard !stale, url.isFileURL else { throw VaultCryptoError.invalidParameters }
        return url
    }
}

struct AutomaticBackupCredentialStore {
    static let filename = "automatic-backup.credentials"
    let directory: URL
    private var url: URL { directory.appendingPathComponent(Self.filename) }
    var exists: Bool { FileManager.default.fileExists(atPath: url.path) }
    func save(_ config: AutomaticBackupConfiguration, key: [UInt8]) throws {
        _ = try config.destination()
        if exists { guard try url.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink != true else { throw CocoaError(.fileWriteNoPermission) } }
        var document = try JSONEncoder().encode(config)
        defer { document.resetBytes(in: 0..<document.count) }
        var encrypted = try AutomaticBackupCredentialCodec.seal(key: key, plaintext: document)
        defer { encrypted.resetBytes(in: 0..<encrypted.count) }
        try FileByteStore(url: url).writeAtomic(encrypted)
    }
    func load(key: [UInt8]) throws -> AutomaticBackupConfiguration? {
        guard exists else { return nil }
        let values = try url.resourceValues(forKeys: [.isSymbolicLinkKey, .isRegularFileKey])
        guard values.isSymbolicLink != true, values.isRegularFile == true else { throw CocoaError(.fileReadNoPermission) }
        var encrypted = try BoundedFileReader.read(url, maxBytes: AutomaticBackupCredentialCodec.maxPlaintextBytes + 32)
        defer { encrypted.resetBytes(in: 0..<encrypted.count) }
        var document = try AutomaticBackupCredentialCodec.open(key: key, encrypted: encrypted)
        defer { document.resetBytes(in: 0..<document.count) }
        guard let text = String(data: document, encoding: .utf8),
              let canonical = AutomaticBackupConfigurationCodec().canonical(text: text) else { throw VaultCryptoError.invalidParameters }
        let config = try JSONDecoder().decode(AutomaticBackupConfiguration.self, from: Data(canonical.utf8))
        _ = try config.destination()
        return config
    }
    func forget() throws { if exists { try FileManager.default.removeItem(at: url) } }
}
