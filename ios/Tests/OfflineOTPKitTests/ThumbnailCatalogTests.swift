import XCTest
@testable import OfflineOTPKit

/// The persisted logo name an entry carries — the port of `ThumbnailCatalog` in `:core`.
///
/// The iOS client draws no logos, so the only thing at stake is the shared data: the *name* is
/// written into the vault, read back by the other two clients, and turned into artwork there. A name
/// that does not match what Android would have written would silently replace a brand mark with the
/// generic tile the moment an entry crossed over.
final class ThumbnailCatalogTests: XCTestCase {
    func testTheDefaultIsFirstAndNamedAsTheJvmNamesIt() {
        XCTAssertEqual(ThumbnailCatalog.defaultName, "Default")
        XCTAssertEqual(ThumbnailCatalog.names.first, "Default")
        XCTAssertGreaterThan(ThumbnailCatalog.names.count, 100)
        // A name may only appear once: `resolveName` returns the first match in declaration order.
        XCTAssertEqual(Set(ThumbnailCatalog.names).count, ThumbnailCatalog.names.count)
    }

    func testAnIssuerMatchesItsNameExactly() {
        XCTAssertEqual(ThumbnailCatalog.resolveName("GitHub"), "GitHub")
        XCTAssertEqual(ThumbnailCatalog.resolveName("github"), "GitHub")
        XCTAssertEqual(ThumbnailCatalog.resolveName("  GitHub  "), "GitHub")
    }

    func testPunctuationAndSpacingDoNotBreakTheMatch() {
        // The spellings issuers actually use, against the names the catalog stores.
        XCTAssertEqual(ThumbnailCatalog.resolveName("Login.gov"), "LoginGov")
        XCTAssertEqual(ThumbnailCatalog.resolveName("Battle.net"), "BattleNet")
        XCTAssertEqual(ThumbnailCatalog.resolveName("GOV.UK"), "GovUK")
        // An underscore in the stored name is punctuation like any other.
        XCTAssertEqual(ThumbnailCatalog.resolveName("Charles Schwab"), "Charles_Schwab")
    }

    func testTheAliasTableCoversBrandsWithNoSharedSpelling() {
        XCTAssertEqual(ThumbnailCatalog.resolveName("1password"), "OnePassword")
        XCTAssertEqual(ThumbnailCatalog.resolveName("AWS"), "AmazonWebServices")
        XCTAssertEqual(ThumbnailCatalog.resolveName("Azure"), "Microsoft")
        XCTAssertEqual(ThumbnailCatalog.resolveName("tuta"), "Tutanota")
    }

    func testAWholeWordInALongerIssuerMatches() {
        // "GitHub Enterprise" is still a GitHub account.
        XCTAssertEqual(ThumbnailCatalog.resolveName("GitHub Enterprise"), "GitHub")
        XCTAssertEqual(ThumbnailCatalog.resolveName("my Box account"), "Box")
    }

    func testAnUnknownOrEmptyIssuerFallsBackToTheDefault() {
        XCTAssertEqual(ThumbnailCatalog.resolveName(""), "Default")
        XCTAssertEqual(ThumbnailCatalog.resolveName("Example"), "Default")
        XCTAssertEqual(ThumbnailCatalog.resolveName("   "), "Default")
        XCTAssertEqual(ThumbnailCatalog.resolveName("Unboxed"), "Default")
        // A name only matches as a whole word: "Boxing" is not "Box", but "Box drive" is.
        XCTAssertEqual(ThumbnailCatalog.resolveName("Boxing club"), "Default")
        XCTAssertEqual(ThumbnailCatalog.resolveName("Box drive"), "Box")
    }

    func testAStoredNameIsNormalisedTheWayTheJvmNormalisesIt() {
        XCTAssertEqual(ThumbnailCatalog.normalizeName("GitHub"), "GitHub")
        XCTAssertEqual(ThumbnailCatalog.normalizeName("github"), "GitHub")
        // An unknown name, an empty one and a missing one all mean the default.
        XCTAssertEqual(ThumbnailCatalog.normalizeName("NotAThumbnail"), "Default")
        XCTAssertEqual(ThumbnailCatalog.normalizeName(""), "Default")
        XCTAssertEqual(ThumbnailCatalog.normalizeName(nil), "Default")
    }

    func testAFoldedNameNeverCollidesWithAnotherName() {
        // `exactName` matches on the folded spelling, so two names folding to the same string would
        // make the result depend on declaration order in a way nobody could predict.
        var seen: [String: String] = [:]
        for name in ThumbnailCatalog.names {
            let folded = ThumbnailCatalog.fold(name)
            XCTAssertNil(seen[folded], "\(name) and \(seen[folded] ?? "") fold to the same name")
            seen[folded] = name
        }
    }
}
