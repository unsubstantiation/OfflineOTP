import XCTest
@testable import OfflineOTPKit

/// The list projection: tabs, search, the tag rule and the five orders.
///
/// These are the rules the phone and the desktop client share through `:core`, restated for Swift.
/// The cases here are the ones `EntryListPresentationTest`, `EntryListTest` and `EntryTagFilterTest`
/// pin on the JVM side, so a change on either side has to change both.
final class EntryListProjectionTests: XCTestCase {
    private func totp(_ issuer: String, _ label: String, tags: [String] = [],
                      lastUsed: Int64 = 0, usedFrequency: Int64 = 0) throws -> Entry {
        var fields: [String: JSONValue] = [
            "secret": .string("JBSWY3DPEHPK3PXP"),
            "issuer": .string(issuer),
            "label": .string(label),
            "digits": .int(6),
            "type": .string("TOTP"),
            "algorithm": .string("SHA1"),
            "thumbnail": .string(""),
            "last_used": .int(lastUsed),
            "used_frequency": .int(usedFrequency),
            "period": .int(30),
            "tags": .array(tags.map { .string($0) }),
        ]
        if lastUsed != 0 { fields["details_visible"] = .bool(true) }
        return try Entry.parse(.object(fields))
    }

    private func entry(_ type: String, secret: String = "JBSWY3DPEHPK3PXP") throws -> Entry {
        var fields: [String: JSONValue] = [
            "secret": .string(secret),
            "issuer": .string("Issuer"),
            "label": .string("Label"),
            "digits": .int(type == "STEAM" ? 5 : 6),
            "type": .string(type),
            "algorithm": .string("SHA1"),
            "thumbnail": .string(""),
            "last_used": .int(0),
            "used_frequency": .int(0),
            "tags": .array([]),
        ]
        if type == "HOTP" { fields["counter"] = .int(0) }
        return try Entry.parse(.object(fields))
    }

    private func labels(_ entries: [Entry], _ query: EntryListQuery) -> [String] {
        EntryListProjection.visibleIndices(entries, query: query).map { entries[$0].label }
    }

    // MARK: - Tabs

    func testEveryKindLandsInTheTabTheOtherClientsShow() throws {
        let cases: [(String, EntryCategory)] = [
            ("TOTP", .otp), ("HOTP", .otp), ("MOTP", .otp), ("STEAM", .otp),
            ("NOTE", .notes), ("SSH_KEY", .sshKeys), ("PASSWORD", .passwords),
            ("PASSWORD_ARCHIVE", .passwords), ("EMAIL", .emails),
        ]
        for (type, category) in cases {
            let secret = type == "NOTE" ? Base32.encode(Array("note".utf8))
                : type == "EMAIL" || type == "PASSWORD" || type == "PASSWORD_ARCHIVE"
                || type == "SSH_KEY" ? Base32.encode(Array("material".utf8)) : "JBSWY3DPEHPK3PXP"
            let built = try entry(type, secret: secret)
            XCTAssertEqual(EntryCategory.of(built), category, type)
        }
        // The order of the tab strip, and the titles, are the other clients' exactly.
        XCTAssertEqual(EntryCategory.allCases.map(\.title),
                       ["OTP", "Notes", "SSH Keys", "Passwords", "Email"])
        XCTAssertEqual(EntryCategory.allCases.map(\.rawValue),
                       ["OTP", "NOTES", "SSH_KEYS", "PASSWORDS", "EMAILS"])
    }

    func testATabOnlyShowsItsOwnEntriesAndNeverDropsTheOthers() throws {
        let entries = [try totp("Issuer", "totp"), try entry("NOTE", secret: Base32.encode(Array("x".utf8)))]
        XCTAssertEqual(labels(entries, EntryListQuery(category: .otp)), ["totp"])
        XCTAssertEqual(labels(entries, EntryListQuery(category: .notes)), ["Label"])
        XCTAssertEqual(labels(entries, EntryListQuery(category: .emails)), [])
        // Without a category every entry is visible, which is how the tests check nothing is lost.
        XCTAssertEqual(labels(entries, EntryListQuery()), ["totp", "Label"])
    }

    // MARK: - Search

    func testSearchCoversLabelsIssuersAndTagsAndRespectsTheChosenFields() throws {
        let entries = [try totp("Alpha", "login", tags: ["work"]),
                       try totp("Beta", "alice", tags: ["home"])]
        let all: Set<SearchInclude> = Set(SearchInclude.allCases)
        XCTAssertEqual(labels(entries, EntryListQuery(text: "alpha", includes: all)), ["login"])
        XCTAssertEqual(labels(entries, EntryListQuery(text: "ALICE", includes: all)), ["alice"])
        XCTAssertEqual(labels(entries, EntryListQuery(text: "work", includes: all)), ["login"])
        // A field that is not included cannot match, even when its text is there.
        XCTAssertEqual(labels(entries, EntryListQuery(text: "work", includes: [.label])), [])
        XCTAssertEqual(labels(entries, EntryListQuery(text: "beta", includes: [.issuer])), ["alice"])
    }

    func testSearchIsCaseInsensitiveWithoutDependingOnTheDeviceLanguage() throws {
        // The Android search lowercased with the default locale, so under Turkish "I" folded to "ı"
        // and searching "i" missed it. The shared rule is a locale-independent compare.
        let entries = [try totp("Issuer", "TICKET")]
        XCTAssertEqual(labels(entries, EntryListQuery(text: "ticket")), ["TICKET"])
        XCTAssertEqual(labels(entries, EntryListQuery(text: "TiCk")), ["TICKET"])
    }

    func testSearchIgnoresTheTagFilterWhileItHasTextAndTheTabStillApplies() throws {
        // Both clients keep a live search across tag and tab changes; the search covers the whole
        // vault before the tab narrows it, and it does not also apply the tag selection.
        let entries = [try totp("Issuer", "alice-work", tags: ["work"]),
                       try totp("Issuer", "alice-home", tags: ["home"]),
                       try totp("Issuer", "bob", tags: ["work"])]
        var query = EntryListQuery(text: "alice", selectedTags: ["work"])
        XCTAssertEqual(labels(entries, query), ["alice-work", "alice-home"])
        query.category = .otp
        XCTAssertEqual(labels(entries, query), ["alice-work", "alice-home"])
        query.text = ""
        XCTAssertEqual(labels(entries, query), ["alice-work", "bob"])
    }

    // MARK: - Tags

    func testAnEmptyTagSelectionFiltersNothingButTheUntaggedSwitchDoes() throws {
        let entries = [try totp("Issuer", "tagged", tags: ["work"]), try totp("Issuer", "bare")]
        XCTAssertEqual(labels(entries, EntryListQuery()), ["tagged", "bare"])
        XCTAssertEqual(labels(entries, EntryListQuery(includeUntagged: true)), ["bare"])
        XCTAssertEqual(labels(entries, EntryListQuery(selectedTags: ["work"])), ["tagged"])
    }

    func testAllTagsRequiresEveryOneAndAnyTagRequiresOne() throws {
        let entries = [try totp("Issuer", "both", tags: ["work", "urgent"]),
                       try totp("Issuer", "work-only", tags: ["work"]),
                       try totp("Issuer", "bare")]
        XCTAssertEqual(labels(entries, EntryListQuery(selectedTags: ["work", "urgent"],
                                                     tagFunctionality: .and)),
                       ["both"])
        XCTAssertEqual(labels(entries, EntryListQuery(selectedTags: ["work", "urgent"],
                                                     tagFunctionality: .or)),
                       ["both", "work-only"])
        // `single` is a selection rule for the UI; it matches like OR, as `EntryTagFilter` does.
        XCTAssertEqual(labels(entries, EntryListQuery(selectedTags: ["work"],
                                                     tagFunctionality: .single)),
                       ["both", "work-only"])
        // An untagged entry joins an AND selection when the switch is on.
        XCTAssertEqual(labels(entries, EntryListQuery(selectedTags: ["work"],
                                                     includeUntagged: true,
                                                     tagFunctionality: .and)),
                       ["both", "work-only", "bare"])
    }

    func testTagNamesAreOrderedCaseInsensitivelyAndDeduplicated() {
        XCTAssertEqual(EntryTagFilter.sortedNames(["zebra", "Apple", "apple", "Banana"]),
                       ["Apple", "apple", "Banana", "zebra"])
        XCTAssertEqual(EntryTagFilter.sortedNames(["work", "work", "home"]), ["home", "work"])
    }

    // MARK: - Order

    func testTheFiveOrdersMatchTheOtherClients() throws {
        let entries = [
            try totp("Zeta", "b", lastUsed: 100, usedFrequency: 1),
            try totp("alpha", "c", lastUsed: 300, usedFrequency: 3),
            try totp("Beta", "a", lastUsed: 200, usedFrequency: 2),
        ]
        XCTAssertEqual(labels(entries, EntryListQuery(sortMode: .unsorted)), ["b", "c", "a"])
        // Issuer and label compare at collator primary strength: case does not decide the order.
        XCTAssertEqual(labels(entries, EntryListQuery(sortMode: .issuer)), ["c", "a", "b"])
        XCTAssertEqual(labels(entries, EntryListQuery(sortMode: .label)), ["a", "b", "c"])
        XCTAssertEqual(labels(entries, EntryListQuery(sortMode: .lastUsed)), ["c", "a", "b"])
        XCTAssertEqual(labels(entries, EntryListQuery(sortMode: .mostUsed)), ["c", "a", "b"])
        // Choosing the vault order again restores what the file says, not the previous sort.
        XCTAssertEqual(labels(entries, EntryListQuery(sortMode: .unsorted)), ["b", "c", "a"])
    }

    func testTiesKeepTheVaultOrder() throws {
        // Java's `Collections.sort` is stable, so equal keys never shuffle the rows; the Swift sort
        // is not guaranteed stable on its own, which is why the comparison falls through to the index.
        let entries = [try totp("Same", "first"), try totp("Same", "second"),
                       try totp("Same", "third")]
        XCTAssertEqual(labels(entries, EntryListQuery(sortMode: .issuer)),
                       ["first", "second", "third"])
        let usage = [try totp("A", "x", lastUsed: 5), try totp("B", "y", lastUsed: 5)]
        XCTAssertEqual(labels(usage, EntryListQuery(sortMode: .lastUsed)), ["x", "y"])
    }

    func testTheStoredSortValueIsTheNameTheDesktopClientWrites() {
        XCTAssertEqual(SortMode.allCases.map(\.rawValue),
                       ["UNSORTED", "ISSUER", "LABEL", "LAST_USED", "MOST_USED"])
        XCTAssertEqual(SortMode.allCases.map(\.title),
                       ["Unsorted", "Issuer", "Label", "Last used", "Most used"])
        XCTAssertEqual(SortMode.settingsKey, "ui_sort_mode")
        XCTAssertEqual(SortMode.stored("LAST_USED"), .lastUsed)
        // An unreadable preference falls back to the vault order instead of failing the unlock.
        XCTAssertEqual(SortMode.stored("nonsense"), .unsorted)
        XCTAssertEqual(SortMode.stored(nil), .unsorted)
    }

    func testSearchAndOrderComposeWithTheTab() throws {
        var entries: [Entry] = []
        for index in 0..<4 {
            entries.append(try totp("Issuer", "alice-\(index)",
                                    tags: index.isMultiple(of: 2) ? ["even"] : ["odd"],
                                    lastUsed: Int64(index)))
        }
        try entries.append(entry("NOTE", secret: Base32.encode(Array("note".utf8))))
        let query = EntryListQuery(category: .otp, text: "alice", selectedTags: ["even"],
                                   sortMode: .lastUsed)
        XCTAssertEqual(labels(entries, query), ["alice-3", "alice-2", "alice-1", "alice-0"])
    }
}
