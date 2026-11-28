import Foundation
import XCTest
@testable import OfflineOTPKit

/// Standalone compatibility oracle. The app's bundled XCTest suite exercises the actual Kotlin
/// adapter; keeping this oracle does not permit production fallback to the old document policy.
final class PortableEntryDocumentOracleTests: XCTestCase {
    private struct Corpus: Decodable { let valid: [Row] }
    private struct Row: Decodable { let kind: String; let document: String }

    func testAllKindMaterialFixturesRemainReadableByTheNativeEngine() throws {
        let corpus = try JSONDecoder().decode(Corpus.self,
            from: GoldenCorpus.golden("portable-entry-documents.json"))
        XCTAssertEqual(corpus.valid.count, 9)
        for row in corpus.valid {
            let entries = try VaultDocument.decode(row.document)
            XCTAssertEqual(entries.first?.kind?.rawValue, row.kind)
            let restored = try VaultDocument.decode(VaultDocument.encode(entries))
            XCTAssertEqual(restored.map(\.fields), entries.map(\.fields), row.kind)
        }
    }

    func testPasswordVaultUsesItsInjectedDocumentAdapterForReadsAndWritesAndFailsClosed() throws {
        final class Recording: EntryDocumentAdapter {
            var reads = 0
            var writes = 0
            var reject = false
            func decode(_ text: String) throws -> [Entry] {
                reads += 1
                if reject { throw VaultDocumentError.invalidEntry }
                return try VaultDocument.decode(text)
            }
            func encode(_ entries: [Entry]) throws -> String {
                writes += 1
                if reject { throw VaultDocumentError.invalidEntry }
                return try VaultDocument.encode(entries)
            }
        }
        let (baseline, file) = GoldenCorpus.fixtureVault(secrets: try GoldenCorpus.golden("secrets.dat"))
        let adapter = Recording()
        let vault = PasswordVault(vaultFile: file, backupFile: MemoryByteStore(data: nil),
            settings: baseline.settings, documents: adapter)
        let opened = try vault.open(password: GoldenCorpus.password)
        XCTAssertEqual(adapter.reads, 1)
        try vault.save(key: opened.key, entries: opened.entries)
        XCTAssertEqual(adapter.writes, 1)
        _ = try vault.open(password: GoldenCorpus.password)
        XCTAssertEqual(adapter.reads, 2)
        let prior = file.data
        adapter.reject = true
        XCTAssertThrowsError(try vault.save(key: opened.key, entries: opened.entries))
        XCTAssertEqual(file.data, prior)
        XCTAssertThrowsError(try vault.open(password: GoldenCorpus.password))
    }
}
