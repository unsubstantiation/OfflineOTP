import Foundation

/// Which tab an entry belongs to.
///
/// The mapping is the one `EntryListPresentation.categoryOf` applies for both existing clients, and
/// the order is the order of Android's tab strip and the desktop tab strip. It lives here, once, for
/// the same reason it lives once in `:core`: a new entry kind that lands in the wrong tab on one
/// client only is exactly the drift this project keeps removing.
public enum EntryCategory: String, CaseIterable, Equatable {
    case otp = "OTP"
    case notes = "NOTES"
    case sshKeys = "SSH_KEYS"
    case passwords = "PASSWORDS"
    case emails = "EMAILS"

    /// The tab that shows `entry`. A kind this build does not know falls under OTP rather than
    /// disappearing: the tab strip must never be a filter that hides vault data.
    public static func of(_ entry: Entry) -> EntryCategory {
        guard let kind = entry.kind else { return .otp }
        switch kind {
        case .email: return .emails
        case .password, .passwordArchive: return .passwords
        case .note: return .notes
        case .sshKey: return .sshKeys
        case .totp, .hotp, .motp, .steam: return .otp
        }
    }

    /// The tab title the other clients show (Android's `tab_*` strings, desktop's `categoryTitle`).
    public var title: String {
        switch self {
        case .otp: return "OTP"
        case .notes: return "Notes"
        case .sshKeys: return "SSH Keys"
        case .passwords: return "Passwords"
        case .emails: return "Email"
        }
    }
}

/// The list order, with the same five choices and the same stored values as `Constants.SortMode`.
///
/// The stored value is the enum name (`"LAST_USED"`), because the desktop client writes
/// `ui_sort_mode` as `SortMode.name` into its client settings file and this client reads and writes
/// the same key in its own settings file.
public enum SortMode: String, CaseIterable, Equatable {
    case unsorted = "UNSORTED"
    case issuer = "ISSUER"
    case label = "LABEL"
    case lastUsed = "LAST_USED"
    case mostUsed = "MOST_USED"

    /// The settings key, matching `VaultController.SORT_MODE_KEY`. Never written into `secrets.dat`.
    public static let settingsKey = "ui_sort_mode"

    public var title: String {
        switch self {
        case .unsorted: return "Unsorted"
        case .issuer: return "Issuer"
        case .label: return "Label"
        case .lastUsed: return "Last used"
        case .mostUsed: return "Most used"
        }
    }

    /// A missing or unknown stored value falls back to the vault order instead of failing a read,
    /// exactly as the desktop client's `savedSortMode` does.
    public static func stored(_ value: String?) -> SortMode {
        guard let value, let mode = SortMode(rawValue: value) else { return .unsorted }
        return mode
    }
}

/// The entry fields a search looks at, spelled as `Constants.SearchIncludes` spells them.
public enum SearchInclude: String, CaseIterable, Equatable {
    case label = "LABEL"
    case issuer = "ISSUER"
    case tags = "TAGS"
}

/// How several selected tags combine, matching `Constants.TagFunctionality`.
///
/// `single` is a UI rule (only one tag can be selected at a time); it matches like `or`, which is
/// what `EntryTagFilter.matches` does in `:core`.
public enum TagFunctionality: String, CaseIterable, Equatable {
    case or = "OR"
    case and = "AND"
    case single = "SINGLE"
}

/// The tag selection rule and the tag order both existing clients use (`EntryTagFilter` in `:core`).
public enum EntryTagFilter {
    /// True when `entry` carries the selection.
    ///
    /// With `and` the entry must carry every selected tag; with `or`/`single` at least one. A tagless
    /// entry matches when `includeUntagged` is on. An empty selection therefore matches nothing in
    /// `or` mode — the caller treats "no tags and untagged off" as "not filtering by tags at all",
    /// the same way the desktop client's `applyTagFilter` documents it.
    public static func matches(_ entry: Entry, tags: [String], includeUntagged: Bool,
                               functionality: TagFunctionality) -> Bool {
        let entryTags = entry.tags
        if entryTags.isEmpty, includeUntagged { return true }
        if functionality == .and { return Set(tags).isSubset(of: Set(entryTags)) }
        return tags.contains { entryTags.contains($0) }
    }

    /// Tag names in the order both clients list them: case-insensitively, then case-sensitively so
    /// `"Apple"` and `"apple"` keep a stable order. Duplicates are dropped.
    public static func sortedNames(_ names: [String]) -> [String] {
        var seen = Set<String>()
        let unique = names.filter { seen.insert($0).inserted }
        return unique.sorted { first, second in
            let folded = first.compare(second, options: [.caseInsensitive], range: nil, locale: nil)
            if folded != .orderedSame { return folded == .orderedAscending }
            return first < second
        }
    }
}

/// Everything that decides which entries a tab shows and in what order.
///
/// One value instead of six parameters threaded through the view, so the store can hand the same
/// query to the projection on every render and a test can build one directly.
public struct EntryListQuery: Equatable {
    /// The selected tab; nil means every category (used by tests, not by the tab strip).
    public var category: EntryCategory?
    public var text: String
    public var includes: Set<SearchInclude>
    public var selectedTags: [String]
    public var includeUntagged: Bool
    public var tagFunctionality: TagFunctionality
    public var sortMode: SortMode

    public init(category: EntryCategory? = nil, text: String = "",
                includes: Set<SearchInclude> = Set(SearchInclude.allCases),
                selectedTags: [String] = [], includeUntagged: Bool = false,
                tagFunctionality: TagFunctionality = .or, sortMode: SortMode = .unsorted) {
        self.category = category
        self.text = text
        self.includes = includes
        self.selectedTags = selectedTags
        self.includeUntagged = includeUntagged
        self.tagFunctionality = tagFunctionality
        self.sortMode = sortMode
    }
}

/// The list projection: category, live search, tag filter and the five orders.
///
/// A pure function over the vault's entries, returning **source indices** rather than copies. That
/// keeps the vault's own order addressable (an edit or a delete needs it) and keeps the projection
/// from owning entries, which is the rule `EntryListPresentation` states on the JVM side: clearing a
/// projection must never destroy vault data.
///
/// Search replaces the tag filter while it has text, exactly as `EntryListPresentation.refreshTags`
/// does, and it covers the whole vault before the tab is applied — the sandbox check
/// *"the adapter keeps a live search across tag and sort refreshes"* pins that rule for both clients.
public enum EntryListProjection {
    /// Source indices of the entries to show, in display order.
    public static func visibleIndices(_ entries: [Entry], query: EntryListQuery) -> [Int] {
        var candidates: [Int]
        if !query.text.isEmpty {
            let text = query.text
            let includes = query.includes
            candidates = entries.indices.filter {
                matchesSearch(entries[$0], text: text, includes: includes)
            }
        } else if !query.selectedTags.isEmpty || query.includeUntagged {
            let tags = query.selectedTags
            let untagged = query.includeUntagged
            let functionality = query.tagFunctionality
            candidates = entries.indices.filter {
                EntryTagFilter.matches(entries[$0], tags: tags, includeUntagged: untagged,
                                       functionality: functionality)
            }
        } else {
            candidates = Array(entries.indices)
        }
        if let category = query.category {
            candidates = candidates.filter { EntryCategory.of(entries[$0]) == category }
        }
        return ordered(candidates, entries: entries, sortMode: query.sortMode)
    }

    /// Whether `entry` answers `text`, over the selected fields.
    ///
    /// Case-insensitive substring, locale-independent — the same match `contains(ignoreCase = true)`
    /// makes on the JVM, and deliberately not `lowercased()`, which made the Android search depend on
    /// the device language (under Turkish, `"I"` folds to `"ı"`).
    public static func matchesSearch(_ entry: Entry, text: String, includes: Set<SearchInclude>) -> Bool {
        guard !text.isEmpty else { return true }
        if includes.contains(.label), contains(entry.label, text) { return true }
        if includes.contains(.issuer), contains(entry.issuer, text) { return true }
        if includes.contains(.tags), entry.tags.contains(where: { contains($0, text) }) { return true }
        return false
    }

    /// Reorders `indices` without dropping any: the five sort modes, stable.
    ///
    /// `unsorted` keeps the vault's order, so choosing it again restores what the file says rather
    /// than freezing the previous order. The comparisons mirror the JVM comparators: `issuer` and
    /// `label` compare at collator *primary* strength (case- and accent-insensitive), `last_used`
    /// and `most_used` are descending. Ties keep the vault order, which is what a stable
    /// `Collections.sort` gives the other clients.
    public static func ordered(_ indices: [Int], entries: [Entry], sortMode: SortMode) -> [Int] {
        guard sortMode != .unsorted else { return indices }
        return indices.sorted { first, second in
            let a = entries[first]
            let b = entries[second]
            switch sortMode {
            case .issuer:
                return compareText(a.issuer, b.issuer, first, second)
            case .label:
                return compareText(a.label, b.label, first, second)
            case .lastUsed:
                if a.lastUsed != b.lastUsed { return a.lastUsed > b.lastUsed }
                return first < second
            case .mostUsed:
                if a.usedFrequency != b.usedFrequency { return a.usedFrequency > b.usedFrequency }
                return first < second
            case .unsorted:
                return first < second
            }
        }
    }

    private static func compareText(_ first: String, _ second: String,
                                    _ firstIndex: Int, _ secondIndex: Int) -> Bool {
        let result = folded(first).compare(folded(second), options: [], range: nil, locale: nil)
        if result != .orderedSame { return result == .orderedAscending }
        return firstIndex < secondIndex
    }

    /// Collator primary strength: base letters only, so `"apple"`, `"Apple"` and `"Ápple"` compare
    /// equal and fall back to the vault order instead of sorting by byte value.
    private static func folded(_ value: String) -> String {
        value.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive],
                      locale: nil)
    }

    private static func contains(_ haystack: String, _ needle: String) -> Bool {
        haystack.range(of: needle, options: [.caseInsensitive], range: nil, locale: nil) != nil
    }
}
