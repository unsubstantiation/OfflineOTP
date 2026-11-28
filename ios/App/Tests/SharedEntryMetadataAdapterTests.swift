import Foundation
import XCTest
import OfflineOTPKit
import OfflineOTPPresentation
@testable import OfflineOTP

final class SharedEntryMetadataAdapterTests: XCTestCase {
    private struct Corpus: Decodable { let cases: [Row] }
    private struct Row: Decodable { let name: String; let document: String }

    func testSharedKotlinMetadataRoundTripPreservesEveryOriginalField() throws {
        let fixture = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "portable-entry-metadata", withExtension: "json"))
        let corpus = try JSONDecoder().decode(Corpus.self, from: Data(contentsOf: fixture))
        for row in corpus.cases {
            let entry = try XCTUnwrap(VaultDocument.decode(row.document).first)
            let model = SharedEntryMetadataAdapter.snapshot(entry)
            XCTAssertNil(model.validationError(), row.name)
            let patch = try JSONValue.decode(model.toJson())
            XCTAssertEqual(entry.lastUsed, patch.objectValue?["last_used"]?.intValue)
            XCTAssertEqual(entry.usedFrequency, patch.objectValue?["used_frequency"]?.intValue)
            let restored = try SharedEntryMetadataAdapter.roundTrip(entry)
            XCTAssertEqual(entry.fields, restored.fields, row.name)
            let reloaded = try XCTUnwrap(VaultDocument.decode(VaultDocument.encode([restored])).first)
            XCTAssertEqual(entry.fields, reloaded.fields, row.name)
        }
    }

    func testKotlinBoundsUseUtf16AndDoNotExposeMetadataInDescription() {
        let model = EntryMetadata(issuer: String(repeating: "😀", count: 513), label: "private-label",
            hiddenLabel: "private-hidden", tags: [], lastUsed: 0, usedFrequency: 0, detailsVisible: false)
        XCTAssertNotNil(model.validationError())
        XCTAssertFalse(model.description.contains("private-label"))
        XCTAssertFalse(model.description.contains("private-hidden"))
    }
}
