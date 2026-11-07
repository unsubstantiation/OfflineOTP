import Foundation

/// Turns browser-imported `PASSWORD` entries back into a browser CSV, and applies an edited field
/// back onto a stored entry.
///
/// Mirrors `PasswordCsvExport.kt` in `:core`, so the three clients agree on which entries may leave
/// as a browser CSV and what an edit may change. The browser import keeps every source column
/// precisely so an entry can be written back out in the file's own dialect; anything whose columns
/// no browser writes (a LinSCP session, a legacy Thunderbird row) is refused rather than flattened.
public enum PasswordCsvExport {

    /// The browser whose header a stored column set belongs to.
    public enum Dialect: String, CaseIterable {
        case chrome = "CHROME"
        case firefox = "FIREFOX"
        case safari = "SAFARI"

        public var browserName: String {
            switch self {
            case .chrome: return "Chrome"
            case .firefox: return "Firefox"
            case .safari: return "Safari"
            }
        }
    }

    /// Whether an entry can be written as a browser CSV at all.
    public enum Support: Equatable {
        case exportable(Dialect)
        case foreign
        case notPassword
    }

    /// The editable fields, in the vocabulary every browser shares.
    public static let fieldURL = "url"
    public static let fieldUsername = "username"
    public static let fieldPassword = "password"

    public static func support(_ entry: Entry) -> Support {
        guard entry.kind == .password else { return .notPassword }
        guard let fields = try? entry.passwordFields() else { return .foreign }
        return support(fields)
    }

    public static func support(_ fields: PasswordMaterial.Fields) -> Support {
        let columns = fields.items.map { $0.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
        guard !columns.contains(""), Set(columns).count == columns.count else { return .foreign }
        guard columns.contains(fieldURL), columns.contains(fieldUsername), columns.contains(fieldPassword) else {
            return .foreign
        }
        let names = Set(columns)
        if names.contains("title") { return .exportable(.safari) }
        if names.contains("name") { return .exportable(.chrome) }
        if names.contains("httprealm") || names.contains("formactionorigin") || names.contains("guid") {
            return .exportable(.firefox)
        }
        return .foreign
    }

    /// A complete header for `dialect`, used when the selected entries do not share one header.
    public static func canonicalHeader(_ dialect: Dialect) -> [String] {
        switch dialect {
        case .chrome:
            return ["name", "url", "username", "password", "note"]
        case .firefox:
            return ["url", "username", "password", "httpRealm", "formActionOrigin", "guid",
                    "timeCreated", "timeLastUsed", "timePasswordChanged"]
        case .safari:
            return ["Title", "URL", "Username", "Password", "Notes", "OTPAuth"]
        }
    }

    /// The dialect the whole selection agrees on, else `fallback` — the rule the desktop client uses
    /// so one selection always produces one file.
    public static func agreedDialect(_ entries: [Entry], fallback: Dialect = .firefox) -> Dialect {
        let dialects = entries.compactMap { entry -> Dialect? in
            if case let .exportable(dialect) = support(entry) { return dialect }
            return nil
        }
        guard let only = dialects.first, dialects.allSatisfy({ $0 == only }) else { return fallback }
        return only
    }

    /// Writes the entries as one CSV in `dialect`'s header. Every entry must report
    /// `.exportable`; otherwise this throws rather than writing a file that drops columns.
    public static func exportCsv(entries: [Entry], dialect: Dialect) throws -> Data {
        guard !entries.isEmpty else { throw PasswordCsvExportError.nothingToExport }
        var decoded: [PasswordMaterial.Fields] = []
        for entry in entries {
            guard case .exportable = support(entry), let fields = try? entry.passwordFields() else {
                throw PasswordCsvExportError.notABrowserEntry
            }
            decoded.append(fields)
        }
        let columns = header(for: decoded, dialect: dialect)
        let rows = decoded.map { fields in columns.map { column($0, fields) } }
        return EmailMaterial.exportCsv(columns: columns, rows: rows)
    }

    private static func header(for decoded: [PasswordMaterial.Fields], dialect: Dialect) -> [String] {
        guard let first = decoded.first else { return canonicalHeader(dialect) }
        let firstColumns = first.items.map { $0.name }
        let normalized = firstColumns.map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
        let same = decoded.allSatisfy { fields in
            let names = fields.items.map { $0.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
            return names == normalized
        }
        // An unchanged, agreeing header is the file dialect the user actually imported; keep it.
        if same, support(first) == .exportable(dialect) { return firstColumns }
        return canonicalHeader(dialect)
    }

    /// One cell: the entry's own value when it has that column, else a value mapped from the same
    /// meaning in another dialect. Absent stays empty rather than being invented.
    private static func column(_ column: String, _ fields: PasswordMaterial.Fields) -> String {
        let direct = fields.value(column)
        if !direct.isEmpty { return direct }
        switch column.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "name", "title":
            let name = fields.value("name")
            return name.isEmpty ? fields.value("title") : name
        case "note", "notes":
            let note = fields.value("note")
            return note.isEmpty ? fields.value("notes") : note
        case "url":
            let origin = fields.value("origin")
            return origin.isEmpty ? fields.value("login_uri") : origin
        case "username", "user":
            let user = fields.value("user")
            return user.isEmpty ? fields.value("login_username") : user
        case "password", "pass":
            let password = fields.value("login_password")
            return password.isEmpty ? fields.value("pass") : password
        default:
            return ""
        }
    }

    /// Applies an edited URL, username or password, leaving every other column untouched, and
    /// returns the replacement entry. `nil` keeps the stored value.
    ///
    /// An empty password is refused: the vault cannot tell a deliberate empty password from a
    /// mistake, and an empty cell exports a login that cannot work anywhere.
    public static func withEditedFields(
        _ entry: Entry,
        url: String? = nil,
        username: String? = nil,
        password: String? = nil
    ) throws -> Entry {
        guard entry.kind == .password else { throw PasswordCsvExportError.notAPasswordEntry }
        let fields = try entry.passwordFields()
        var pairs: [PasswordMaterial.Field] = []
        pairs.reserveCapacity(fields.items.count)
        for field in fields.items {
            let key = field.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            var value = field.value
            switch key {
            case "url", "origin", "login_uri", "hostname", "host":
                if let url { value = url }
            case "username", "user", "login_username":
                if let username { value = username }
            case "password", "pass", "login_password":
                if let password { value = password }
            default:
                break
            }
            pairs.append(PasswordMaterial.Field(name: field.name, value: value))
        }
        func firstValue(_ keys: [String]) -> String {
            for key in keys {
                if let match = pairs.first(where: {
                    $0.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == key
                }) { return match.value }
            }
            return ""
        }
        guard !firstValue([fieldPassword, "pass"]).isEmpty else {
            throw PasswordCsvExportError.emptyPassword
        }
        let editedURL = firstValue([fieldURL])
        let name = firstValue(["name"])
        let title = firstValue(["title"])
        let issuer = !name.isEmpty ? name : (!title.isEmpty ? title : editedURL)
        let user = firstValue([fieldUsername])
        let replacement = try Entry.passwordFields(
            label: user.isEmpty ? "Password" : user,
            issuer: issuer,
            fields: pairs,
            tags: entry.tags
        )
        return replacement
    }
}

/// Why a password could not be written out or edited. Messages never contain field values.
public enum PasswordCsvExportError: Error, Equatable, CustomStringConvertible {
    case nothingToExport
    case notABrowserEntry
    case notAPasswordEntry
    case emptyPassword

    public var description: String {
        switch self {
        case .nothingToExport: return "no entries were selected"
        case .notABrowserEntry: return "an entry has no browser CSV form"
        case .notAPasswordEntry: return "the entry is not a password"
        case .emptyPassword: return "a password cannot be empty"
        }
    }
}
