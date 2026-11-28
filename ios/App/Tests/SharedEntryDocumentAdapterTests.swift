import Foundation
import XCTest
import OfflineOTPKit
import OfflineOTPPresentation
@testable import OfflineOTP

final class SharedEntryDocumentAdapterTests: XCTestCase {
    private struct Corpus: Decodable { let valid: [Valid]; let invalid: [Invalid] }
    private struct Valid: Decodable { let kind: String; let document: String }
    private struct Invalid: Decodable { let name: String; let document: String }
    private func corpus() throws -> Corpus {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "portable-entry-documents", withExtension: "json"))
        return try JSONDecoder().decode(Corpus.self, from: Data(contentsOf: url))
    }

    func testEveryKindUsesTheExportedKotlinDocumentAndPreservesExtensionsAfterEdit() throws {
        let adapter = SharedEntryDocumentAdapter()
        let rows = try corpus().valid
        XCTAssertEqual(rows.count, 9)
        for row in rows {
            let shared = try XCTUnwrap(EntryDocumentCodec().decode(text: row.document).document)
            XCTAssertEqual(shared.json(), row.document)
            shared.destroy()
            var entries = try adapter.decode(row.document)
            let original = entries[0].fields
            XCTAssertEqual(entries[0].kind?.rawValue, row.kind)
            entries[0].set("label", .string("edited " + row.kind))
            let reloaded = try adapter.decode(adapter.encode(entries))
            var expected = original
            expected["label"] = .string("edited " + row.kind)
            XCTAssertEqual(reloaded[0].fields, expected, row.kind)
        }
    }

    func testInvalidDocumentsFailThroughBothSharedAndProductionAdapters() throws {
        let adapter = SharedEntryDocumentAdapter()
        for row in try corpus().invalid {
            XCTAssertNil(EntryDocumentCodec().roundTrip(text: row.document), row.name)
            XCTAssertThrowsError(try adapter.decode(row.document), row.name)
        }
        let invalidMaterial = #"[{"type":"PASSWORD","secret":"JBSWY3DPEHPK3PXP","label":"synthetic"}]"#
        XCTAssertNotNil(EntryDocumentCodec().roundTrip(text: invalidMaterial))
        XCTAssertThrowsError(try adapter.decode(invalidMaterial))
    }

    func testFutureNumbersSurviveNativeMetadataEditsWithoutNSNumberConversion() throws {
        let source = #"[{"secret":"JBSWY3DPEHPK3PXP","label":"synthetic","future":{"huge":123456789012345678901234567890,"decimal":1.2300e-15,"wide":1e9999}}]"#
        let adapter = SharedEntryDocumentAdapter()
        var entry = try XCTUnwrap(adapter.decode(source).first)
        XCTAssertNoThrow(try EncryptedBackup.openSelected(password: "", data: Data(source.utf8),
            format: "andotp", andOtpVariant: "plain", documents: adapter))
        let original = entry.validatedExtensionFields
        entry.set("label", .string("edited"))
        let edited = try SharedEntryMetadataAdapter.roundTrip(entry)
        XCTAssertEqual(edited.validatedExtensionFields, original)
        let output = try adapter.encode([edited])
        for token in ["123456789012345678901234567890", "1.2300e-15", "1e9999"] {
            XCTAssertTrue(output.contains(token))
        }
        let reopened = try XCTUnwrap(adapter.decode(output).first)
        XCTAssertEqual(reopened.validatedExtensionFields, original)
    }

    func testPasswordAndEmailMaterialEditorsPreserveOpaqueFutureFields() throws {
        let adapter = SharedEntryDocumentAdapter()
        for row in try corpus().valid where row.kind == "PASSWORD" || row.kind == "EMAIL" {
            let source = String(row.document.dropLast(2)) + #","future":{"wide":1e9999}}]"#
            let entry = try XCTUnwrap(adapter.decode(source).first)
            let replacement: Entry
            if row.kind == "PASSWORD" {
                replacement = try PasswordCsvExport.withEditedFields(entry, password: "changed synthetic")
            } else {
                replacement = try EmailPasswordInput(incoming: "changed synthetic", outgoing: [""]).build(existing: entry)
            }
            XCTAssertEqual(replacement.validatedExtensionFields, entry.validatedExtensionFields)
            XCTAssertTrue(try adapter.encode([replacement]).contains("1e9999"))
        }
    }

    func testProductionVaultStorePersistsAndReopensAllKindsWithSharedDocumentAdapter() throws {
        let folder = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = VaultStore(directory: folder)
        store.unlock(password: "synthetic recovery", confirmation: "synthetic recovery", creating: true)
        XCTAssertTrue(store.isUnlocked)
        for row in try corpus().valid {
            XCTAssertNotNil(store.previewEncryptedBackupCount(encrypted: Data(row.document.utf8), password: "", format: "andotp", andOtpVariant: "plain"), row.kind)
            XCTAssertNotNil(store.restore(encrypted: Data(row.document.utf8), password: "", replace: false,
                format: "andotp", andOtpVariant: "plain"), row.kind)
        }
        XCTAssertEqual(store.entries.count, 9)
        let expected = store.entries.map(\.fields)
        let reopened = VaultStore(directory: folder)
        reopened.unlock(password: "synthetic recovery")
        XCTAssertTrue(reopened.isUnlocked)
        XCTAssertEqual(reopened.entries.map(\.fields), expected)
    }
}
