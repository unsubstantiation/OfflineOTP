import Foundation
import XCTest

/// Access to the fixed-vector corpus that already pins the on-disk format for the JVM clients.
///
/// The Swift tests read `desktop/src/test/resources/golden/` **in place** instead of keeping copies,
/// so a format change on either side breaks both suites at once and the two implementations cannot
/// drift apart quietly.
enum GoldenCorpus {
    /// The throwaway password every fixture uses. Test data only; it protects nothing.
    static let password = "golden-interop-password"

    /// Fixed parameters of the vault fixture, from `golden/vault.properties` and `golden/README.md`.
    static let vaultSalt: [UInt8] = Array(0x00...0x0F)
    static let vaultIterations = 4096
    static let vaultVerifierBase64URL = "TJU9JVeBdEaFFFYX0KW4XA=="

    /// Fixed parameters of `otp_accounts.json.aes`.
    static let backupIterations = 140_000
    static let backupSalt: [UInt8] = Array(0x20...0x2B)
    static let backupNonce: [UInt8] = Array(0x30...0x3B)

    /// The repository root, found from this file's own path: `<root>/ios/Tests/OfflineOTPKitTests/`.
    static var repositoryRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }

    static var goldenDirectory: URL {
        repositoryRoot.appendingPathComponent("desktop/src/test/resources/golden")
    }

    static func golden(_ name: String) throws -> Data {
        let url = goldenDirectory.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw GoldenCorpusError.missingFixture(url.path)
        }
        return try Data(contentsOf: url)
    }

    static func goldenText(_ name: String) throws -> String {
        guard let text = String(data: try golden(name), encoding: .utf8) else {
            throw GoldenCorpusError.missingFixture(name)
        }
        return text
    }

    static func fixture(_ name: String) throws -> Data {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .appendingPathComponent("Fixtures")
            .appendingPathComponent(name)
        return try Data(contentsOf: url)
    }

    /// A vault backed by the golden `secrets.dat`, with the fixture's own salt/iterations/verifier.
    static func fixtureVault(secrets: Data) -> (PasswordVault, MemoryByteStore) {
        let store = MemoryByteStore(data: secrets)
        let settings = MemorySettings(values: [
            VaultSettingsKeys.authMethod: .string("password"),
            VaultSettingsKeys.encryption: .string("password"),
            VaultSettingsKeys.authSalt: .string(VaultCrypto.base64URLEncode(vaultSalt)),
            VaultSettingsKeys.authIterations: .int(vaultIterations),
            VaultSettingsKeys.authCredentials: .string(vaultVerifierBase64URL),
        ])
        let backup = MemoryByteStore(data: nil)
        return (PasswordVault(vaultFile: store, backupFile: backup, settings: settings), store)
    }
}

enum GoldenCorpusError: Error {
    case missingFixture(String)
}

/// In-memory `AtomicByteStore`, standing in for `secrets.dat` and its `.bck` copy.
final class MemoryByteStore: AtomicByteStore {
    private(set) var data: Data?

    init(data: Data?) {
        self.data = data
    }

    func exists() -> Bool { data != nil }

    func read() throws -> Data {
        guard let data else { throw MemoryByteStoreError.missing }
        return data
    }

    func writeAtomic(_ data: Data) throws {
        self.data = data
    }

    func delete() {
        data = nil
    }
}

enum MemoryByteStoreError: Error {
    case missing
}

/// One settings value, so a test can build a fixture-backed settings store in one expression.
enum SettingsValue {
    case string(String)
    case int(Int)
}

/// In-memory `KeyValueSettings`, standing in for `vault.properties`.
final class MemorySettings: KeyValueSettings {
    private var strings: [String: String] = [:]
    private var integers: [String: Int] = [:]

    init(values: [String: SettingsValue] = [:]) {
        for (key, value) in values {
            switch value {
            case .string(let text): strings[key] = text
            case .int(let number): integers[key] = number
            }
        }
    }

    func string(_ key: String) -> String? { strings[key] }

    func setString(_ key: String, _ value: String) { strings[key] = value }

    func integer(_ key: String) -> Int? { integers[key] }

    func setInteger(_ key: String, _ value: Int) { integers[key] = value }
}
