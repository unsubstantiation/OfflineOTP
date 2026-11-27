import Foundation
import XCTest
@testable import OfflineOTPKit

final class EntryExtensionContractTests: XCTestCase {
    func testSharedOpaqueMetadataSurvivesTypedEditAndReload() throws {
        let source = try GoldenCorpus.golden("entry-extension-fields.json")
        var entries = try VaultDocument.decode(source)
        XCTAssertEqual(1, entries.count)
        let expected = entries[0].fields
        entries[0].set("label", .string("edited label"))
        let reloaded = try VaultDocument.decode(VaultDocument.encode(entries))
        var edited = expected
        edited["label"] = .string("edited label")
        XCTAssertEqual(edited, reloaded[0].fields)
        XCTAssertEqual(.null, reloaded[0].fields["extension_null"])
    }
    func testManualDraftReplacementKeepsExtensionsAndNewTypedFieldsWin() throws {
        let source = try XCTUnwrap(VaultDocument.decode(GoldenCorpus.golden("entry-extension-fields.json")).first)
        let draft = try EntryDraft.create(kind: .totp, issuer: "Changed issuer", label: "Changed label",
            secret: "JBSWY3DPEHPK3PXP", digits: "6", timing: "30", algorithm: .sha1, newEntry: false)
        let replacement = EntryDraft.preservingMetadata(of: source, in: draft)
        XCTAssertEqual("Changed label", replacement.label)
        XCTAssertEqual(30, replacement.period)
        XCTAssertEqual(6, replacement.digits)
        for name in ["extension_object", "extension_list", "extension_null"] {
            XCTAssertEqual(source.fields[name], replacement.fields[name])
        }
        let reopened = try XCTUnwrap(VaultDocument.decode(VaultDocument.encode([replacement])).first)
        XCTAssertEqual(replacement.fields, reopened.fields)
    }

}
