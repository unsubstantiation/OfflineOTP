import Foundation

/// Why an `otpauth://` link was refused. The names are the ones `OtpParseError` in `:core` reports,
/// so the same link is rejected for the same reason on all three clients.
public enum OtpParseError: String, Error, Equatable {
    case inputTooLong = "INPUT_TOO_LONG"
    case invalidUri = "INVALID_URI"
    case unsupportedType = "UNSUPPORTED_TYPE"
    case duplicateParameter = "DUPLICATE_PARAMETER"
    case missingSecret = "MISSING_SECRET"
    case invalidSecret = "INVALID_SECRET"
    case missingCounter = "MISSING_COUNTER"
    case invalidNumber = "INVALID_NUMBER"
    case invalidParameters = "INVALID_PARAMETERS"
    case invalidAlgorithm = "INVALID_ALGORITHM"
    case textTooLong = "TEXT_TOO_LONG"
    case tooManyTags = "TOO_MANY_TAGS"

    /// A sentence for the form. It never repeats the user's input back, so a wrong link cannot put a
    /// secret on screen next to an error.
    public var message: String {
        switch self {
        case .inputTooLong, .invalidUri:
            return "That is not a valid otpauth:// link."
        case .unsupportedType:
            return "That link is not for a TOTP, HOTP, Steam or mOTP account."
        case .duplicateParameter:
            return "That link sets the same parameter twice."
        case .missingSecret, .invalidSecret:
            return "That link has no usable secret."
        case .missingCounter:
            return "A counter-based link needs a counter."
        case .invalidNumber, .invalidParameters:
            return "That link has an invalid period, digit count or counter."
        case .invalidAlgorithm:
            return "That link uses an unsupported hash algorithm."
        case .textTooLong, .tooManyTags:
            return "That link carries too much text or too many tags."
        }
    }
}

/// One parsed `otpauth://` link — the port of `OtpUriParser` in `:core`.
///
/// The parser is deliberately strict in the same places the JVM one is: a parameter it does not know
/// is ignored (forward compatibility), a parameter it knows twice is refused, the label loses an
/// issuer prefix, and every bound in `InputPolicy` applies before an entry is built. Percent-decoding
/// is done here rather than through `URLComponents`, because `URLComponents` accepts escapes and
/// encodings the other clients reject.
public struct OtpUri: Equatable {
    public let kind: EntryKind
    /// The seed. For mOTP this is the UTF-8 of the hexadecimal text, as `:core` stores it.
    public let secret: [UInt8]
    public let period: Int
    public let digits: Int
    public let counter: Int64
    public let issuer: String
    public let label: String
    public let algorithm: OtpHashAlgorithm
    public let tags: [String]

    private static let hosts: [String: EntryKind] = [
        "totp": .totp, "hotp": .hotp, "motp": .motp, "steam": .steam,
    ]

    /// Parses a link, or throws `OtpParseError`.
    public static func parse(_ input: String) throws -> OtpUri {
        guard !input.isEmpty else { throw OtpParseError.invalidUri }
        guard input.count <= InputPolicy.maxURIChars else { throw OtpParseError.inputTooLong }

        // A raw space in a label is common in the wild; the JVM encoder escapes them before parsing,
        // so do the same rather than refusing a link that works on the other clients.
        let text = input.replacingOccurrences(of: " ", with: "%20")
        let characters = Array(text)

        guard let separator = find("://", characters, from: 0) else { throw OtpParseError.invalidUri }
        // The scheme is compared as written: `java.net.URI` normalises the *host* but not the scheme,
        // so `OTPAUTH://` is refused by the other clients and is refused here too.
        guard String(characters[0..<separator]) == "otpauth" else { throw OtpParseError.invalidUri }
        var index = separator + 3

        // Nothing may follow a fragment marker, and the authority may not carry user info or a port.
        if characters[(index..<characters.count)].contains("#") { throw OtpParseError.invalidUri }
        var hostEnd = index
        while hostEnd < characters.count, characters[hostEnd] != "/", characters[hostEnd] != "?" {
            hostEnd += 1
        }
        let authority = String(characters[index..<hostEnd])
        let isPlainHost = authority.allSatisfy { character in
            character.isLetter || character.isNumber || character == "." || character == "-"
        }
        guard !authority.isEmpty, isPlainHost else { throw OtpParseError.invalidUri }
        guard let kind = hosts[authority.lowercased()] else {
            throw OtpParseError.unsupportedType
        }
        index = hostEnd

        var rawPath = ""
        if index < characters.count, characters[index] == "/" {
            var pathEnd = index
            while pathEnd < characters.count, characters[pathEnd] != "?" {
                pathEnd += 1
            }
            rawPath = String(characters[index..<pathEnd])
            index = pathEnd
        }

        let hasQuery = index < characters.count && characters[index] == "?"
        let rawQuery = hasQuery ? String(characters[(index + 1)...]) : nil

        var parameters: [String: String] = [:]
        var tags: [String] = []
        if let rawQuery {
            for part in rawQuery.components(separatedBy: "&") {
                if part.isEmpty { throw OtpParseError.invalidUri }
                let equals = part.firstIndex(of: "=")
                let rawName = equals.map { String(part[part.startIndex..<$0]) } ?? part
                let rawValue = equals.map { String(part[part.index(after: $0)...]) } ?? ""
                let name = try decode(rawName, plusAsSpace: true)
                let value = try decode(rawValue, plusAsSpace: true)
                if name.isEmpty { throw OtpParseError.invalidUri }
                if name == "tags" {
                    if tags.count == InputPolicy.maxTags { throw OtpParseError.tooManyTags }
                    try bounded(value, InputPolicy.maxTagChars)
                    tags.append(value)
                } else {
                    if parameters[name] != nil { throw OtpParseError.duplicateParameter }
                    parameters[name] = value
                }
            }
        }

        guard let encodedSecret = parameters["secret"], !encodedSecret.isEmpty else {
            throw OtpParseError.missingSecret
        }
        if kind == .hotp, parameters["counter"] == nil { throw OtpParseError.missingCounter }

        // Every supplied number is validated even when the kind does not use it, as on the JVM.
        let suppliedPeriod = try parameters["period"].map(integer) ?? TokenCalculator.totpDefaultPeriod
        let defaultDigits = kind == .steam ? TokenCalculator.steamDefaultDigits
            : TokenCalculator.totpDefaultDigits
        let digits = try parameters["digits"].map(integer) ?? defaultDigits
        let suppliedCounter = try parameters["counter"].map(integer) ?? 0
        do {
            try InputPolicy.validateTokenParameters(period: suppliedPeriod, digits: digits,
                                                    counter: suppliedCounter)
        } catch {
            throw OtpParseError.invalidParameters
        }
        if kind == .steam, digits != TokenCalculator.steamDefaultDigits {
            throw OtpParseError.invalidParameters
        }
        let period: Int
        switch kind {
        case .totp, .steam: period = suppliedPeriod
        default: period = TokenCalculator.totpDefaultPeriod
        }
        let counter: Int64 = kind == .hotp ? suppliedCounter : 0

        let algorithmName = (parameters["algorithm"] ?? "SHA1").uppercased()
        guard let algorithm = OtpHashAlgorithm(rawValue: algorithmName) else {
            throw OtpParseError.invalidAlgorithm
        }

        let issuer = parameters["issuer"] ?? ""
        try bounded(issuer, InputPolicy.maxTextChars)

        let decodedPath = try decode(rawPath, plusAsSpace: false)
        var fullLabel = decodedPath
        if fullLabel.hasPrefix("/") { fullLabel.removeFirst() }
        if !issuer.isEmpty, fullLabel.hasPrefix("\(issuer):") {
            fullLabel.removeFirst(issuer.count + 1)
        }
        let label = trimControlsAndSpaces(fullLabel)
        try bounded(label, InputPolicy.maxTextChars)

        let secret = try decodeSecret(encodedSecret, kind: kind)
        return OtpUri(kind: kind, secret: secret, period: period, digits: digits, counter: counter,
                      issuer: issuer, label: label, algorithm: algorithm, tags: tags)
    }

    /// The entry this link describes, written in the stored field order and validated by the same
    /// loader that reads a vault. `newEntry` stamps it as freshly used, exactly as an import does on
    /// the other two clients.
    public func entry(newEntry: Bool = true, now: Int64 = EntryUsage.nowMillis()) throws -> Entry {
        var fields: [String: JSONValue] = [
            "secret": .string(Base32.encode(secret)),
            "issuer": .string(issuer),
            "label": .string(label),
            "digits": .int(Int64(digits)),
            "type": .string(kind.rawValue),
            "algorithm": .string(algorithm.rawValue),
            "thumbnail": .string(EntryDraft.defaultThumbnailName),
            "last_used": .int(newEntry ? now : 0),
            "used_frequency": .int(0),
        ]
        switch kind {
        case .totp:
            fields["period"] = .int(Int64(period))
        case .steam:
            if period != TokenCalculator.totpDefaultPeriod {
                fields["period"] = .int(Int64(period))
            }
        case .hotp:
            fields["counter"] = .int(counter)
        default:
            break
        }
        fields["tags"] = .array(tags.map { .string($0) })
        return try Entry.parse(.object(fields))
    }

    /// The `otpauth://` form of a stored entry, or nil when the kind has none — the rule behind
    /// **Show QR code** on both other clients, which hide the action for notes, SSH keys, password
    /// entries and email accounts.
    ///
    /// Field order and encoding are the JVM's: `secret`, `issuer`, then `counter`/`period`/`digits`/
    /// `algorithm` only when they differ from the defaults, then one `tags` pair per tag. Spaces are
    /// `%20`, never `+`, so the same entry produces the same link everywhere.
    public static func uri(for entry: Entry) -> String? {
        guard let kind = entry.kind, kind.isOtp, let rawSecret = entry.secretBytes else { return nil }
        let host: String
        switch kind {
        case .totp: host = "totp"
        case .hotp: host = "hotp"
        case .steam: host = "steam"
        case .motp: host = "motp"
        case .note, .sshKey, .password, .passwordArchive, .email: return nil
        }

        var pairs: [(String, String)] = []
        if kind == .motp {
            pairs.append(("secret", String(bytes: rawSecret, encoding: .utf8) ?? ""))
        } else {
            pairs.append(("secret", Base32.encode(rawSecret)))
        }
        pairs.append(("issuer", entry.issuer))
        if kind == .hotp {
            pairs.append(("counter", String(entry.counter)))
        }
        if kind == .hotp || kind == .totp || kind == .steam,
           entry.period != TokenCalculator.totpDefaultPeriod {
            pairs.append(("period", String(entry.period)))
        }
        if entry.digits != TokenCalculator.totpDefaultDigits {
            pairs.append(("digits", String(entry.digits)))
        }
        if entry.algorithm != .sha1 {
            pairs.append(("algorithm", entry.algorithm.rawValue))
        }

        var out = "otpauth://" + host + "/" + percentEncode(entry.label) + "?"
        out += pairs.map { "\($0.0)=\(percentEncode($0.1))" }.joined(separator: "&")
        for tag in entry.tags {
            out += "&tags=" + percentEncode(tag)
        }
        return out
    }

    // MARK: - Internals

    private static func find(_ needle: String, _ characters: [Character], from start: Int) -> Int? {
        let pattern = Array(needle)
        guard characters.count >= pattern.count else { return nil }
        var index = start
        while index + pattern.count <= characters.count {
            if Array(characters[index..<(index + pattern.count)]) == pattern { return index }
            index += 1
        }
        return nil
    }

    private static func integer(_ text: String) throws -> Int {
        guard !text.isEmpty,
              text.range(of: "^-?[0-9]+$", options: .regularExpression) != nil,
              let value = Int(text) else {
            throw OtpParseError.invalidNumber
        }
        return value
    }

    /// Kotlin's `trim { it <= ' ' }`: leading and trailing characters up to and including space.
    /// It is not `whitespacesAndNewlines`, which would also strip a no-break space the JVM keeps.
    private static func trimControlsAndSpaces(_ text: String) -> String {
        var scalars = Array(text.unicodeScalars)
        while let first = scalars.first, first.value <= 32 { scalars.removeFirst() }
        while let last = scalars.last, last.value <= 32 { scalars.removeLast() }
        var view = String.UnicodeScalarView()
        view.append(contentsOf: scalars)
        return String(view)
    }

    private static func bounded(_ text: String, _ limit: Int) throws {
        guard text.count <= limit else { throw OtpParseError.textTooLong }
    }

    /// mOTP seeds are hexadecimal text; every other kind is canonical Base32.
    ///
    /// An mOTP seed is *stored* as the UTF-8 of the hex text, so its bound is the hex text's length:
    /// `InputPolicy.maxSecretBytes` characters. Applying the byte bound to Base32 text instead would
    /// let a link through that `Entry.parse` then refuses.
    private static func decodeSecret(_ encoded: String, kind: EntryKind) throws -> [UInt8] {
        guard encoded.count <= InputPolicy.maxSecretBytes * 2 else { throw OtpParseError.invalidSecret }
        if kind == .motp {
            guard encoded.count % 2 == 0,
                  encoded.count <= InputPolicy.maxSecretBytes,
                  encoded.range(of: "^[0-9a-fA-F]+$", options: .regularExpression) != nil else {
                throw OtpParseError.invalidSecret
            }
            return Array(encoded.utf8)
        }
        guard !encoded.isEmpty,
              encoded.range(of: "^[A-Za-z2-7]+={0,6}$", options: .regularExpression) != nil,
              let bytes = Base32.decode(encoded), !bytes.isEmpty,
              bytes.count <= InputPolicy.maxSecretBytes else {
            throw OtpParseError.invalidSecret
        }
        return bytes
    }

    /// Decodes exactly once, rejecting a malformed escape or a byte sequence that is not UTF-8 —
    /// the JVM parser's rule, rather than replacement characters that would silently change a label.
    private static func decode(_ value: String, plusAsSpace: Bool) throws -> String {
        let source = Array(value.utf8)
        var bytes: [UInt8] = []
        bytes.reserveCapacity(source.count)
        var index = 0
        while index < source.count {
            let byte = source[index]
            if byte == UInt8(ascii: "%") {
                guard index + 2 < source.count,
                      let high = hex(source[index + 1]), let low = hex(source[index + 2]) else {
                    throw OtpParseError.invalidUri
                }
                bytes.append((high << 4) | low)
                index += 3
                continue
            }
            if byte == UInt8(ascii: "+"), plusAsSpace {
                bytes.append(UInt8(ascii: " "))
                index += 1
                continue
            }
            bytes.append(byte)
            index += 1
        }
        guard let text = String(bytes: bytes, encoding: .utf8) else { throw OtpParseError.invalidUri }
        return text
    }

    private static func hex(_ byte: UInt8) -> UInt8? {
        if byte >= UInt8(ascii: "0"), byte <= UInt8(ascii: "9") {
            return byte - UInt8(ascii: "0")
        }
        if byte >= UInt8(ascii: "a"), byte <= UInt8(ascii: "f") {
            return byte - UInt8(ascii: "a") + 10
        }
        if byte >= UInt8(ascii: "A"), byte <= UInt8(ascii: "F") {
            return byte - UInt8(ascii: "A") + 10
        }
        return nil
    }

    /// RFC 3986 unreserved characters pass through; every other byte (including space and `+`) is
    /// encoded, upper-case, exactly as `Entry.toUriString` does.
    private static func percentEncode(_ value: String) -> String {
        var out = ""
        for byte in Array(value.utf8) {
            // A – Z, a – z, 0 – 9 and `-`, `_`, `.`, `~` are the RFC 3986 unreserved set.
            switch byte {
            case 0x41...0x5A, 0x61...0x7A, 0x30...0x39, 0x2D, 0x5F, 0x2E, 0x7E:
                out.append(Character(UnicodeScalar(byte)))
            default:
                out += String(format: "%%%02X", byte)
            }
        }
        return out
    }
}

/// What a scanned or pasted payload turned out to be — the portable half of QR import.
///
/// Google Authenticator exports a multi-account payload (`otpauth-migration://`) that needs its own
/// protobuf decoder; this client does not implement it, so such a payload is **recognised and
/// reported as unsupported** rather than being fed to the single-account parser and failing with a
/// misleading "not a link". Everything else is parsed by `OtpUri`, which is what the desktop client's
/// `QrImport.classify` does too.
public enum QrPayload: Equatable {
    case otp(OtpUri)
    case migration
    case invalid(String)
    case unrecognized

    public static func classify(_ text: String?) -> QrPayload {
        // Blank is "nothing scanned"; anything else is handed to the parsers exactly as scanned, with
        // no trimming, because the other clients classify the decoded text byte for byte.
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return .unrecognized
        }
        if text.lowercased().hasPrefix("otpauth-migration:") { return .migration }
        do {
            return .otp(try OtpUri.parse(text))
        } catch let error as OtpParseError {
            return .invalid(error.rawValue)
        } catch {
            return .invalid(OtpParseError.invalidUri.rawValue)
        }
    }
}
