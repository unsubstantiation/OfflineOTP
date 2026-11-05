import Foundation

/// An entry kind as stored in the `type` field. The stored spelling is part of the format.
public enum EntryKind: String, CaseIterable, Equatable {
    case totp = "TOTP"
    case hotp = "HOTP"
    case motp = "MOTP"
    case steam = "STEAM"
    case note = "NOTE"
    case sshKey = "SSH_KEY"
    case password = "PASSWORD"
    case passwordArchive = "PASSWORD_ARCHIVE"
    case email = "EMAIL"

    /// Kinds whose seed is an OTP seed this client can generate codes from.
    public var isOtp: Bool {
        switch self {
        case .totp, .hotp, .motp, .steam: return true
        case .note, .sshKey, .password, .passwordArchive, .email: return false
        }
    }
}

/// Per-kind seed limits, mirroring `EntryJson`'s choice of limit: the framed kinds carry much more
/// material than an OTP seed, and applying one limit to all of them would either reject a real SSH
/// key or let an oversized OTP seed through.
public enum SecretLimit {
    public static let otp = InputPolicy.maxSecretBytes
    public static let sshKey = 2 * (32 * 1024) + 8
    public static let password = 4 * 1024 * 1024
    public static let email = 128 * 1024

    public static func forKind(_ kind: EntryKind) -> Int {
        switch kind {
        case .sshKey: return sshKey
        case .password, .passwordArchive: return password
        case .email: return email
        case .totp, .hotp, .motp, .steam, .note: return otp
        }
    }
}

/// One vault entry: a bag of stored fields with typed access to the ones this client understands.
///
/// The bag is the point. A field this build does not know about — a key a newer client wrote, an
/// entry kind it cannot display — stays in `fields` and is written back unchanged, so an iOS save
/// never destroys another client's data. Fields are only added or replaced by an explicit edit.
public struct Entry: Equatable {
    /// Every stored field, keyed by name.
    public private(set) var fields: [String: JSONValue]

    /// The order both existing clients write: `EntryJson.serialize` appends exactly these keys, and
    /// Android's `JSONObject` renders a `LinkedHashMap`, so this order is also the order the phone
    /// puts in the file. Keys outside this list are preserved and appended alphabetically.
    static let canonicalFieldOrder = [
        "secret", "issuer", "label", "digits", "type", "algorithm", "thumbnail",
        "last_used", "used_frequency",
        // One of these, at this position, depending on the kind.
        "period", "counter", "pin",
        "tags", "details_visible",
        "ssh_host", "ssh_port", "ssh_username", "ssh_password",
    ]

    public init(fields: [String: JSONValue]) {
        self.fields = fields
    }

    // MARK: - Typed access

    /// The stored kind. A document without a `type` is a TOTP entry — that is the historical default
    /// both existing clients apply — and nil only marks a `type` this format does not define, which
    /// `parse` refuses anyway.
    public var kind: EntryKind? {
        guard let rawType = fields["type"] else { return .totp }
        return rawType.stringValue.flatMap(EntryKind.init(rawValue:))
    }

    public var secretBase32: String? { fields["secret"]?.stringValue }

    /// The decoded seed. For `NOTE` this is the note text as UTF-8, exactly as `:core` stores it.
    public var secretBytes: [UInt8]? {
        guard let text = secretBase32 else { return nil }
        return Base32.decode(text)
    }

    public var issuer: String { fields["issuer"]?.stringValue ?? "" }
    public var label: String { fields["label"]?.stringValue ?? "" }

    public var digits: Int {
        if let value = fields["digits"]?.intValue { return Int(value) }
        return kind == .steam ? TokenCalculator.steamDefaultDigits : TokenCalculator.totpDefaultDigits
    }

    public var algorithm: OtpHashAlgorithm {
        fields["algorithm"]?.stringValue.flatMap(OtpHashAlgorithm.init(rawValue:)) ?? .sha1
    }

    /// The stored period. mOTP ignores it: it always rolls over every ten seconds.
    public var period: Int {
        if kind == .motp { return TokenCalculator.totpDefaultPeriod }
        if let value = fields["period"]?.intValue { return Int(value) }
        return TokenCalculator.totpDefaultPeriod
    }

    public var counter: Int64 {
        if let value = fields["counter"]?.intValue {
            return value
        }
        return kind == .hotp ? TokenCalculator.hotpInitialCounter : 0
    }

    public var pin: String { fields["pin"]?.stringValue ?? "" }
    public var tags: [String] { fields["tags"]?.arrayValue?.compactMap { $0.stringValue } ?? [] }
    public var lastUsed: Int64 { fields["last_used"]?.intValue ?? 0 }
    public var usedFrequency: Int64 { fields["used_frequency"]?.intValue ?? 0 }
    public var detailsVisible: Bool { fields["details_visible"]?.boolValue == true }

    /// True when the entry carries an SSH connection (`ssh_host` or friends) to show.
    public var sshHost: String? { fields["ssh_host"]?.stringValue }
    public var sshPort: Int? { fields["ssh_port"]?.intValue.map(Int.init) }
    public var sshUsername: String? { fields["ssh_username"]?.stringValue }
    public var sshPassword: String? { fields["ssh_password"]?.stringValue }

    /// Shown instead of a code while an mOTP entry has no PIN before it. Same text and same rule as
    /// `Entry.MOTP_NO_PIN_CODE` in `:core`.
    public static let motpNoPinCode = "PINREQ"

    /// The code for this entry at `unixTime`, using the entry's own parameters.
    ///
    /// A note, an SSH key, a password archive or an email account has no code, and an mOTP entry with
    /// no PIN shows the placeholder — the same three outcomes the other two clients have.
    public func code(unixTime: Int64) throws -> String {
        guard let secret = secretBytes, let entryKind = kind else { throw EntryError.invalidSecret }
        switch entryKind {
        case .totp:
            return try TokenCalculator.totp(secret: secret, period: period, unixTime: unixTime,
                                            digits: digits, algorithm: algorithm)
        case .hotp:
            return try TokenCalculator.hotp(secret: secret, counter: counter, digits: digits,
                                            algorithm: algorithm)
        case .steam:
            return try TokenCalculator.steam(secret: secret, period: period, unixTime: unixTime,
                                             digits: digits, algorithm: algorithm)
        case .motp:
            guard !pin.isEmpty else { return Entry.motpNoPinCode }
            guard let text = String(bytes: secret, encoding: .utf8) else {
                throw EntryError.invalidSecret
            }
            return TokenCalculator.motp(pin: pin, secret: text, unixTime: unixTime)
        case .note, .sshKey, .password, .passwordArchive, .email:
            throw EntryError.notAnOtpEntry
        }
    }

    // MARK: - Serialization

    /// One entry as compact JSON, in the stored field order.
    public func compact() -> String {
        var ordered: [JSONField] = []
        var written = Set<String>()
        for name in Entry.canonicalFieldOrder where fields[name] != nil {
            ordered.append(JSONField(name, fields[name]!))
            written.insert(name)
        }
        // Anything else (a newer field, a kind-specific extra) is kept, in a stable order.
        for name in fields.keys.sorted() where !written.contains(name) {
            ordered.append(JSONField(name, fields[name]!))
        }
        let body = ordered.map { JSONValue.quote($0.name) + ":" + $0.value.compact() }
        return "{" + body.joined(separator: ",") + "}"
    }

    /// Parses one entry object, enforcing the bounds `:core` enforces.
    ///
    /// An entry whose *kind* this client cannot display is still parsed and returned: it must
    /// survive a load/save cycle. An entry that is structurally invalid (no seed, a seed that is not
    /// Base32, a bad digit count) is rejected, because writing it back could corrupt the vault.
    public static func parse(_ value: JSONValue) throws -> Entry {
        guard let object = value.objectValue else { throw EntryError.notAnObject }
        guard let secretText = object["secret"]?.stringValue else { throw EntryError.missingSecret }

        // As on the JVM, a `type` this format does not define is rejected rather than carried around:
        // no other client could open the document either. Kinds that exist but are not displayable
        // here (a password archive, an email account) are parsed and kept untouched.
        let entry = Entry(fields: object)
        guard let kind = entry.kind else { throw EntryError.unsupportedKind }

        // The kind decides the seed limit: an SSH key carries far more material than an OTP seed.
        let limit = SecretLimit.forKind(kind)
        guard secretText.count <= limit * 2,
              let seed = Base32.decode(secretText) else { throw EntryError.invalidSecret }
        guard !seed.isEmpty, seed.count <= limit else { throw EntryError.invalidSecret }

        // Bounds for the fields every kind shares. `InputPolicy` owns the limits; the errors are
        // translated here so a caller of this type only ever has to know one error enum.
        do {
            try InputPolicy.validateText(entry.issuer, limit: InputPolicy.maxTextChars)
            try InputPolicy.validateText(entry.label, limit: InputPolicy.maxTextChars)
            try InputPolicy.validateText(entry.pin, limit: InputPolicy.maxTagChars)
            guard entry.tags.count <= InputPolicy.maxTags else { throw EntryError.tooManyTags }
            for tag in entry.tags {
                try InputPolicy.validateText(tag, limit: InputPolicy.maxTagChars)
            }
            try InputPolicy.validateTokenParameters(period: entry.period, digits: entry.digits,
                                                    counter: entry.counter)
        } catch EntryError.tooManyTags {
            throw EntryError.tooManyTags
        } catch {
            throw EntryError.invalidParameters
        }

        if kind == .hotp, object["counter"] == nil { throw EntryError.missingCounter }
        if kind == .steam, entry.digits != TokenCalculator.steamDefaultDigits {
            throw EntryError.invalidParameters
        }
        if kind == .note {
            guard let bytes = entry.secretBytes,
                  let text = String(bytes: bytes, encoding: .utf8) else {
                throw EntryError.invalidNote
            }
            do {
                try InputPolicy.validateNote(text)
            } catch {
                throw EntryError.invalidNote
            }
        }
        return entry
    }
}

public enum EntryError: Error, Equatable {
    case notAnObject
    case missingSecret
    case invalidSecret
    case unsupportedKind
    case missingCounter
    case invalidParameters
    case invalidNote
    case tooManyTags
    case notAnOtpEntry
}
