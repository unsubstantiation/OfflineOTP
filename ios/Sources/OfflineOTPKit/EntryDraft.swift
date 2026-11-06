import Foundation

/// Why a hand-typed entry was refused. The wording matches the shared builder's messages, so the
/// same value typed into the phone, the desktop form and this one is refused for the same reason,
/// with the same sentence.
public enum EntryDraftError: Error, Equatable {
    case kindNotOfferable
    case invalidSecret
    case digitsNotANumber
    case periodNotANumber
    case counterNotANumber
    case titleRequired

    public var message: String {
        switch self {
        case .kindNotOfferable: return "This kind of entry can only be imported"
        case .invalidSecret: return "Invalid secret"
        case .digitsNotANumber: return "Digits must be a number"
        case .periodNotANumber: return "Period must be a number"
        case .counterNotANumber: return "HOTP counter must be a number"
        case .titleRequired: return "This entry needs a title"
        }
    }
}

/// Turns the values of a manual-entry form into an `Entry` — the port of `EntryDraft` in `:core`.
///
/// The assembly is shared on the JVM because two copies of "which constructor does this kind use,
/// what does a blank field mean, what does an unparsable number do" meant a value one form accepted
/// could be refused by the other. The same reasoning applies here: this is the only place in the
/// iOS client that decides what a form means, and it writes the field set `EntryJson.serialize`
/// writes, so a hand-typed entry is byte-identical to one the phone would have written.
public enum EntryDraft {
    /// The kinds a manual form offers.
    ///
    /// `PASSWORD`, `PASSWORD_ARCHIVE` and `EMAIL` are structured credentials that only an import can
    /// produce, exactly as `EntryDraft.offerableTypes` says. `SSH_KEY` is excluded too, and that one
    /// is an iOS-specific limit: its seed is `SshKeyMaterial`-framed (a public key and a private key
    /// with length prefixes), which this client does not implement — writing an unframed blob would
    /// create an entry the other clients cannot decode. Such entries are still listed, revealed and
    /// preserved untouched.
    public static let offerableKinds: [EntryKind] = [.totp, .hotp, .motp, .steam, .note]

    /// The stored thumbnail name when nothing better is known — `ThumbnailCatalog.DEFAULT_NAME`.
    ///
    /// This client draws no logos, but the *name* is shared data: the other two clients resolve it
    /// to artwork, and a name of `Default` on an entry they created for a known brand would show the
    /// generic mark there. `ThumbnailCatalog.resolveName` therefore picks the same name they would,
    /// so an entry typed or imported here keeps its logo on the phone and the desktop.
    public static var defaultThumbnailName: String { ThumbnailCatalog.defaultName }

    /// A secret is Base32 or a raw mOTP seed: whitespace is never part of it, and a pasted secret is
    /// commonly broken across lines. Notes are free text and keep every character — the same rule as
    /// `EntryDraft.sanitizeSecret`.
    public static func sanitizedSecret(_ kind: EntryKind, _ raw: String) -> String {
        if kind == .note || kind == .sshKey { return raw }
        return raw.replacingOccurrences(of: "\\s+", with: "", options: .regularExpression)
    }

    public static func digits(_ text: String) throws -> Int {
        guard let value = Int(text.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            throw EntryDraftError.digitsNotANumber
        }
        return value
    }

    public static func period(_ text: String) throws -> Int {
        guard let value = Int(text.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            throw EntryDraftError.periodNotANumber
        }
        return value
    }

    public static func counter(_ text: String) throws -> Int64 {
        guard let value = Int64(text.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            throw EntryDraftError.counterNotANumber
        }
        return value
    }

    /// Tags as the forms store them: trimmed, no blanks, no repeats, order kept.
    public static func cleanedTags(_ raw: [String]) -> [String] {
        var seen = Set<String>()
        return raw.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    /// Builds and validates one entry.
    ///
    /// - Parameters:
    ///   - secret: the entered seed. Base32 for TOTP/HOTP/Steam, the raw hex seed for mOTP, the note
    ///     text for a note.
    ///   - timing: the period for TOTP/Steam, the counter for HOTP, ignored for the other kinds.
    ///   - newEntry: stamps the entry as used now, as a new entry is; an edit passes `false` and
    ///     keeps the stamp it already carries (see `preservingMetadata`).
    public static func create(kind: EntryKind, issuer: String, label: String, hiddenLabel: String = "", secret: String,
                              digits digitsText: String, timing timingText: String,
                              algorithm: OtpHashAlgorithm, pin: String = "", tags: [String] = [],
                              newEntry: Bool = false,
                              now: Int64 = EntryUsage.nowMillis()) throws -> Entry {
        guard offerableKinds.contains(kind) else { throw EntryDraftError.kindNotOfferable }

        let cleaned = sanitizedSecret(kind, secret)
        var entryDigits = TokenCalculator.totpDefaultDigits
        var period = TokenCalculator.totpDefaultPeriod
        var counter: Int64 = 0
        var entryPin = ""

        switch kind {
        case .note:
            break
        case .motp:
            // mOTP seeds are raw strings rather than Base32, and the other clients require hex.
            guard !cleaned.isEmpty, cleaned.count % 2 == 0,
                  cleaned.allSatisfy({ $0.isHexDigit }) else {
                throw EntryDraftError.invalidSecret
            }
            entryPin = pin
        case .hotp:
            // Qualified, because the local `counter`/`period` below would shadow the parsers.
            entryDigits = try EntryDraft.digits(digitsText)
            counter = try EntryDraft.counter(timingText)
        case .totp, .steam:
            entryDigits = try EntryDraft.digits(digitsText)
            period = try EntryDraft.period(timingText)
        case .sshKey, .password, .passwordArchive, .email:
            throw EntryDraftError.kindNotOfferable
        }

        let seed: [UInt8]
        switch kind {
        case .totp, .hotp, .steam:
            guard let decoded = Base32.decode(cleaned), !decoded.isEmpty else {
                throw EntryDraftError.invalidSecret
            }
            seed = decoded
        case .motp, .note:
            seed = Array(cleaned.utf8)
        case .sshKey, .password, .passwordArchive, .email:
            throw EntryDraftError.kindNotOfferable
        }
        guard seed.count <= SecretLimit.forKind(kind) else { throw EntryDraftError.invalidSecret }

        // The stored field set, in the order `EntryJson.serialize` writes it. `thumbnail` is always
        // present (the shared default name, since this client has no logo artwork) and
        // `details_visible` is only written when true, so a new entry keeps the legacy bytes the
        // other clients write for the default state.
        var fields: [String: JSONValue] = [
            "secret": .string(Base32.encode(seed)),
            "issuer": .string(issuer),
            "label": .string(label),
            "digits": .int(Int64(entryDigits)),
            "type": .string(kind.rawValue),
            "algorithm": .string(algorithm.rawValue),
            "thumbnail": .string(ThumbnailCatalog.resolveName(issuer)),
            "last_used": .int(newEntry ? now : 0),
            "used_frequency": .int(0),
        ]
        switch kind {
        case .totp:
            fields["period"] = .int(Int64(period))
        case .steam:
            // Only a non-default Steam period is written, so default Steam documents stay
            // byte-identical to the ones the other clients write.
            if period != TokenCalculator.totpDefaultPeriod {
                fields["period"] = .int(Int64(period))
            }
        case .hotp:
            fields["counter"] = .int(counter)
        case .motp:
            if !entryPin.isEmpty { fields["pin"] = .string(entryPin) }
        case .note, .sshKey, .password, .passwordArchive, .email:
            break
        }
        fields["tags"] = .array(cleanedTags(tags).map { .string($0) })
        if !hiddenLabel.isEmpty { fields["hidden_label"] = .string(hiddenLabel) }

        // The document loader is the validator: a draft that would not survive being read back is
        // refused here rather than written into the vault.
        let entry = try Entry.parse(.object(fields))

        // `Entry.validate` requires a title on a note; the loader has no opinion about the label.
        if kind == .note, label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw EntryDraftError.titleRequired
        }
        return entry
    }

    /// An edit keeps the metadata the form does not show — the same three fields
    /// `EntryDialog.preserveEditMetadata` carries over on the desktop.
    public static func preservingMetadata(of existing: Entry, in replacement: Entry) -> Entry {
        var result = replacement
        result.setDetailsVisible(existing.detailsVisible)
        result.setLastUsed(existing.lastUsed)
        result.setUsedFrequency(existing.usedFrequency)
        return result
    }
}
