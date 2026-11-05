import Foundation

/// Why a Google Authenticator export was refused. The names are `MigrationError`'s in `:core`, so the
/// same code is rejected for the same reason on every client.
public enum MigrationError: String, Error, Equatable {
    case invalidURI = "INVALID_URI"
    case tooLarge = "TOO_LARGE"
    case invalidData = "INVALID_DATA"
    case unsupported = "UNSUPPORTED"

    /// A sentence for the screen. Like the URI parser's, it never repeats the payload back: a
    /// migration code carries seeds for every account in the export.
    public var message: String {
        // Android shows the "unsupported" sentence for `UNSUPPORTED` and the plain invalid one for
        // every other refusal, oversized payloads included; the ceiling itself is never shown.
        switch self {
        case .unsupported:
            return MigrationWording.unsupported
        case .invalidURI, .tooLarge, .invalidData:
            return MigrationWording.invalid
        }
    }
}

/// One account inside a Google Authenticator export — the metadata `ValidatedOtp` carries on the JVM.
///
/// Seeds are value types here rather than owned buffers. The JVM clients zero a seed as soon as an
/// entry has copied it, because the same array can be freed while the process lives on; Swift arrays
/// are copied on write and released deterministically, so the copy that reaches `Entry` is the only
/// one that outlives the parse.
public struct MigrationAccount: Equatable {
    public let kind: EntryKind
    public let secret: [UInt8]
    public let digits: Int
    public let algorithm: OtpHashAlgorithm
    public let counter: Int64
    public let issuer: String
    public let label: String

    /// The entry this account describes, written and validated exactly as an `otpauth://` import
    /// writes one — same field order, same logo name, same usage stamp.
    public func entry(newEntry: Bool = true, now: Int64 = EntryUsage.nowMillis()) throws -> Entry {
        var fields: [String: JSONValue] = [
            "secret": .string(Base32.encode(secret)),
            "issuer": .string(issuer),
            "label": .string(label),
            "digits": .int(Int64(digits)),
            "type": .string(kind.rawValue),
            "algorithm": .string(algorithm.rawValue),
            "thumbnail": .string(ThumbnailCatalog.resolveName(issuer)),
            "last_used": .int(newEntry ? now : 0),
            "used_frequency": .int(0),
        ]
        switch kind {
        case .totp:
            fields["period"] = .int(Int64(TokenCalculator.totpDefaultPeriod))
        case .hotp:
            fields["counter"] = .int(counter)
        default:
            break
        }
        fields["tags"] = .array([])
        return try Entry.parse(.object(fields))
    }
}

/// One QR code of a Google Authenticator export: the batch it belongs to and the accounts it carries.
public struct MigrationPayload: Equatable {
    public let version: Int
    public let batchSize: Int
    public let batchIndex: Int
    public let batchId: Int
    public let accounts: [MigrationAccount]

    public var count: Int { accounts.count }

    private static let scheme = "otpauth-migration"
    private static let authority = "offline"

    /// True for a payload that claims to be a migration export, whatever it turns out to contain.
    public static func isMigration(_ input: String?) -> Bool {
        guard let input else { return false }
        return input.lowercased().hasPrefix("\(scheme):")
    }

    /// Parses one export QR code, or throws `MigrationError`.
    ///
    /// This is a bounded, non-recursive protobuf reader rather than a generated decoder: the format
    /// is unofficial, so the parser validates every length and every enum against the values the
    /// other clients accept, and refuses an unknown field instead of quietly importing accounts whose
    /// codes would be wrong.
    public static func parse(_ input: String?) throws -> MigrationPayload {
        guard let input, !input.isEmpty else { throw MigrationError.invalidURI }
        guard input.count <= InputPolicy.maxURIChars else { throw MigrationError.tooLarge }

        // The scheme is compared case-insensitively, as `GoogleAuthenticatorMigration.isMigration`
        // does, but the authority is fixed.
        let head = "\(scheme)://"
        guard input.count > head.count, input.lowercased().hasPrefix(head) else {
            throw MigrationError.invalidURI
        }
        let rest = input.dropFirst(head.count)
        if rest.contains("#") { throw MigrationError.invalidURI }

        var authorityEnd = rest.startIndex
        while authorityEnd < rest.endIndex, rest[authorityEnd] != "/", rest[authorityEnd] != "?" {
            authorityEnd = rest.index(after: authorityEnd)
        }
        guard rest[rest.startIndex..<authorityEnd].lowercased() == authority else {
            throw MigrationError.invalidURI
        }
        var cursor = authorityEnd
        var path = ""
        if cursor < rest.endIndex, rest[cursor] == "/" {
            var pathEnd = cursor
            while pathEnd < rest.endIndex, rest[pathEnd] != "?" {
                pathEnd = rest.index(after: pathEnd)
            }
            path = String(rest[cursor..<pathEnd])
            cursor = pathEnd
        }
        guard path.isEmpty || path == "/" else { throw MigrationError.invalidURI }
        guard cursor < rest.endIndex, rest[cursor] == "?" else { throw MigrationError.invalidURI }

        var encoded: String?
        let query = String(rest[rest.index(after: cursor)...])
        for part in query.components(separatedBy: "&") {
            guard let equals = part.firstIndex(of: "="), equals > part.startIndex else {
                throw MigrationError.invalidURI
            }
            guard part[part.startIndex..<equals] == "data" else { continue }
            guard encoded == nil else { throw MigrationError.invalidURI }
            encoded = String(part[part.index(after: equals)...])
        }
        guard let encoded else { throw MigrationError.invalidURI }

        let binary = try decodeBase64(encoded)
        var reader = ProtoReader(bytes: binary, position: 0, end: binary.count)
        return try readPayload(&reader)
    }

    // MARK: - The protobuf fields

    private static func readPayload(_ reader: inout ProtoReader) throws -> MigrationPayload {
        var accounts: [MigrationAccount] = []
        var version = 0
        var batchSize = 0
        var batchIndex = 0
        var batchId = 0
        var seen = Set<Int>()
        while !reader.done {
            let (field, wire) = try reader.tag()
            if field >= 2, field <= 5 {
                guard seen.insert(field).inserted else { throw MigrationError.invalidData }
            }
            switch field {
            case 1:
                try reader.expectWire(wire, 2)
                guard accounts.count < InputPolicy.maxEntries else { throw MigrationError.tooLarge }
                var nested = try reader.message()
                accounts.append(try readAccount(&nested))
            case 2:
                try reader.expectWire(wire, 0)
                version = try reader.nonNegativeInt()
            case 3:
                try reader.expectWire(wire, 0)
                batchSize = try reader.nonNegativeInt()
            case 4:
                try reader.expectWire(wire, 0)
                batchIndex = try reader.nonNegativeInt()
            case 5:
                try reader.expectWire(wire, 0)
                batchId = try reader.signedInt32()
            default:
                throw MigrationError.unsupported
            }
        }
        guard version >= 0, version <= 2 else { throw MigrationError.unsupported }
        // Proto3 omits a field that holds its default value, so an absent batch size means one code.
        if batchSize == 0 { batchSize = 1 }
        guard batchSize <= InputPolicy.maxEntries, batchIndex < batchSize, !accounts.isEmpty else {
            throw MigrationError.invalidData
        }
        return MigrationPayload(version: version, batchSize: batchSize, batchIndex: batchIndex,
                                batchId: batchId, accounts: accounts)
    }

    private static func readAccount(_ reader: inout ProtoReader) throws -> MigrationAccount {
        var secret: [UInt8]?
        var name = ""
        var issuer = ""
        var algorithmCode = 0
        var digitsCode = 0
        var typeCode = 0
        var counter: Int64 = 0
        var seen = Set<Int>()
        while !reader.done {
            let (field, wire) = try reader.tag()
            if field >= 1, field <= 8 {
                guard seen.insert(field).inserted else { throw MigrationError.invalidData }
            }
            switch field {
            case 1:
                try reader.expectWire(wire, 2)
                secret = try reader.bytes(InputPolicy.maxSecretBytes)
            case 2:
                try reader.expectWire(wire, 2)
                name = try reader.text()
            case 3:
                try reader.expectWire(wire, 2)
                issuer = try reader.text()
            case 4:
                try reader.expectWire(wire, 0)
                algorithmCode = try reader.nonNegativeInt()
            case 5:
                try reader.expectWire(wire, 0)
                digitsCode = try reader.nonNegativeInt()
            case 6:
                try reader.expectWire(wire, 0)
                typeCode = try reader.nonNegativeInt()
            case 7:
                try reader.expectWire(wire, 0)
                counter = try reader.varint()
            case 8:
                // An account identifier Google may include; it does not affect the codes.
                try reader.expectWire(wire, 2)
                _ = try reader.text()
            default:
                throw MigrationError.unsupported
            }
        }

        guard let seed = secret, !seed.isEmpty, seed.count <= InputPolicy.maxSecretBytes else {
            throw MigrationError.invalidData
        }
        guard counter >= 0, counter != Int64.max else { throw MigrationError.invalidData }

        let algorithm: OtpHashAlgorithm
        switch algorithmCode {
        case 0, 1: algorithm = .sha1
        case 2: algorithm = .sha256
        case 3: algorithm = .sha512
        default: throw MigrationError.unsupported
        }
        let digits: Int
        switch digitsCode {
        case 0, 1: digits = TokenCalculator.totpDefaultDigits
        case 2: digits = 8
        case 3: digits = 7
        default: throw MigrationError.unsupported
        }
        let kind: EntryKind
        switch typeCode {
        case 0, 2: kind = .totp
        case 1: kind = .hotp
        default: throw MigrationError.unsupported
        }

        // Google stores "Issuer:account" in the name when the export has no issuer field of its own.
        var label = name
        var issuerName = issuer
        if let colon = name.firstIndex(of: ":"), issuerName.isEmpty,
           colon > name.startIndex {
            issuerName = String(name[name.startIndex..<colon])
                .trimmingCharacters(in: .whitespacesAndNewlines)
            label = String(name[name.index(after: colon)...])
                .trimmingCharacters(in: .whitespacesAndNewlines)
        } else if !issuerName.isEmpty, name.hasPrefix("\(issuerName):") {
            label = String(name.dropFirst(issuerName.count + 1))
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }
        guard issuerName.count <= InputPolicy.maxTextChars, label.count <= InputPolicy.maxTextChars
        else { throw MigrationError.tooLarge }

        return MigrationAccount(kind: kind, secret: seed, digits: digits, algorithm: algorithm,
                                counter: kind == .hotp ? counter : 0, issuer: issuerName,
                                label: label)
    }

    /// Base64 with the URL-safe alphabet, and percent escapes for the two characters an unescaped
    /// `+` would lose to form decoding. Anything else is refused rather than skipped.
    private static func decodeBase64(_ input: String) throws -> [UInt8] {
        guard !input.isEmpty else { throw MigrationError.invalidData }
        let source = Array(input.utf8)
        var normalized: [UInt8] = []
        normalized.reserveCapacity(source.count)
        var index = 0
        while index < source.count {
            var byte = source[index]
            index += 1
            if byte == UInt8(ascii: "%") {
                guard index + 1 < source.count,
                      let high = hex(source[index]), let low = hex(source[index + 1]) else {
                    throw MigrationError.invalidURI
                }
                byte = (high << 4) | low
                index += 2
            }
            switch byte {
            case UInt8(ascii: "-"): byte = UInt8(ascii: "+")
            case UInt8(ascii: "_"): byte = UInt8(ascii: "/")
            default: break
            }
            let isLetter = (byte >= UInt8(ascii: "A") && byte <= UInt8(ascii: "Z"))
                || (byte >= UInt8(ascii: "a") && byte <= UInt8(ascii: "z"))
            let isDigit = byte >= UInt8(ascii: "0") && byte <= UInt8(ascii: "9")
            let isPunctuation = byte == UInt8(ascii: "+") || byte == UInt8(ascii: "/")
                || byte == UInt8(ascii: "=")
            guard isLetter || isDigit || isPunctuation else { throw MigrationError.invalidData }
            normalized.append(byte)
        }
        guard let decoded = Data(base64Encoded: Data(normalized)) else {
            throw MigrationError.invalidData
        }
        return [UInt8](decoded)
    }

    private static func hex(_ byte: UInt8) -> UInt8? {
        if byte >= UInt8(ascii: "0"), byte <= UInt8(ascii: "9") { return byte - UInt8(ascii: "0") }
        if byte >= UInt8(ascii: "a"), byte <= UInt8(ascii: "f") { return byte - UInt8(ascii: "a") + 10 }
        if byte >= UInt8(ascii: "A"), byte <= UInt8(ascii: "F") { return byte - UInt8(ascii: "A") + 10 }
        return nil
    }

    /// A fixed-depth wire reader with the same length and varint bounds as the JVM one.
    struct ProtoReader {
        let bytes: [UInt8]
        var position: Int
        let end: Int

        var done: Bool { position == end }

        var remaining: Int { end - position }

        mutating func varint() throws -> Int64 {
            var value: Int64 = 0
            var shift = 0
            while shift <= 63 {
                guard position < end else { throw MigrationError.invalidData }
                let byte = bytes[position]
                position += 1
                if shift == 63, byte > 1 { throw MigrationError.invalidData }
                value |= Int64(byte & 0x7F) << Int64(shift)
                if byte & 0x80 == 0 { return value }
                shift += 7
            }
            throw MigrationError.invalidData
        }

        mutating func tag() throws -> (field: Int, wire: Int) {
            let raw = try varint()
            guard raw > 0, raw <= 0xFFFF_FFFF else { throw MigrationError.invalidData }
            let field = Int(raw >> 3)
            guard field > 0, field <= 0x1FFF_FFFF else { throw MigrationError.invalidData }
            return (field, Int(raw & 7))
        }

        func expectWire(_ actual: Int, _ expected: Int) throws {
            guard actual == expected else { throw MigrationError.invalidData }
        }

        mutating func nonNegativeInt() throws -> Int {
            let value = try varint()
            guard value >= 0, value <= Int64(Int32.max) else { throw MigrationError.invalidData }
            return Int(value)
        }

        mutating func signedInt32() throws -> Int {
            let value = try varint()
            if value >= 0, value <= 0xFFFF_FFFF { return Int(Int32(truncatingIfNeeded: value)) }
            if value < 0, Int64(Int32(truncatingIfNeeded: value)) == value {
                return Int(Int32(truncatingIfNeeded: value))
            }
            throw MigrationError.invalidData
        }

        mutating func range(_ maxLength: Int) throws -> Range<Int> {
            let length = try nonNegativeInt()
            guard length <= maxLength, length <= remaining else { throw MigrationError.invalidData }
            let start = position
            position += length
            return start..<position
        }

        mutating func message() throws -> ProtoReader {
            let part = try range(InputPolicy.maxURIChars)
            return ProtoReader(bytes: bytes, position: part.lowerBound, end: part.upperBound)
        }

        mutating func bytes(_ maxLength: Int) throws -> [UInt8] {
            let part = try range(maxLength)
            return Array(bytes[part])
        }

        mutating func text() throws -> String {
            let part = try range(InputPolicy.maxTextChars * 4)
            guard let value = String(bytes: bytes[part], encoding: .utf8) else {
                throw MigrationError.invalidData
            }
            guard value.count <= InputPolicy.maxTextChars else { throw MigrationError.tooLarge }
            return value
        }
    }
}

/// What happened to one code of a multi-code export.
public enum MigrationBatchStatus: Equatable {
    case added
    case complete
    case duplicate
    case differentExport
    case tooMany
    /// The vault locked, or the sheet closed, while the export was open; nothing was kept.
    case stale
}

/// Holds the codes of one export until every one has been scanned, so a half-scanned export can never
/// be half-imported — the rule `MigrationBatchCollector` enforces on the JVM.
///
/// A collector belongs to one unlocked session: `generation` is the vault's unlock counter, so a code
/// that arrives after the vault locked and was reopened is refused rather than completing an export
/// whose other half was scanned against a vault the user no longer has open.
public struct MigrationBatchCollector {
    public let generation: Int
    private var parts: [Int: MigrationPayload] = [:]
    private var version = -1
    private var batchId = 0
    private var batchSize = 0
    private var total = 0
    private var closed = false

    public init(generation: Int) {
        self.generation = generation
    }

    public var received: Int { parts.count }
    public var expected: Int { batchSize }
    public var isOpen: Bool { !closed }

    /// Adds one code. The payload is consumed either way: a rejection ends the import rather than
    /// leaving a partly-collected export behind.
    public mutating func add(_ payload: MigrationPayload, generation current: Int,
                             sessionReady: Bool) -> MigrationBatchStatus {
        if closed || !sessionReady || current != generation {
            cancel()
            return .stale
        }
        if parts.isEmpty {
            version = payload.version
            batchId = payload.batchId
            batchSize = payload.batchSize
        } else if version != payload.version || batchId != payload.batchId
            || batchSize != payload.batchSize {
            // The export stays open: the user can still scan the code this one was meant to be.
            return .differentExport
        }
        if parts[payload.batchIndex] != nil { return .duplicate }
        if payload.count > InputPolicy.maxEntries - total {
            cancel()
            return .tooMany
        }
        parts[payload.batchIndex] = payload
        total += payload.count
        return parts.count == batchSize ? .complete : .added
    }

    /// Every account, in code order, exactly once — or nil when the export is not complete yet.
    public mutating func takeAccounts(generation current: Int, sessionReady: Bool)
        -> [MigrationAccount]? {
        guard !closed, sessionReady, current == generation, parts.count == batchSize,
              !parts.isEmpty else {
            cancel()
            return nil
        }
        let ordered = parts.keys.sorted().compactMap { parts[$0] }
        cancel()
        return ordered.flatMap { $0.accounts }
    }

    public mutating func cancel() {
        parts.removeAll()
        total = 0
        closed = true
    }
}

/// The sentences the import uses, kept together so the wording can be compared with Android's
/// strings instead of being retyped in a view.
public enum MigrationWording {
    public static let title = "Google Authenticator import"
    public static let duplicateNotice =
        "Only import an export you trust. Accounts with matching OTP settings, names, and issuers "
        + "will be skipped. Other accounts with an existing issuer will be added if you continue."
    public static let invalid =
        "Invalid Google Authenticator export. No accounts from this code were imported."
    public static let unsupported =
        "This export contains an unsupported version, field, account type, algorithm, or digit "
        + "count. No accounts from this code were imported."
    public static let expectedQR = "Choose another QR code from the same Google Authenticator export."
    public static let alreadyScanned = "This export QR code was already scanned."
    public static let wrongBatch = "This code belongs to a different export. Choose a code from the "
        + "original export."
    public static let tooMany = "This export has more accounts than the vault can import."
    public static let unnamed = "Unnamed account"
    public static let saveFailed = "Could not save the imported accounts. Check the vault and try "
        + "again."

    public static func progress(received: Int, expected: Int) -> String {
        "Scanned \(received) of \(expected) QR codes. Scan or choose the next code from the same "
            + "export."
    }

    public static func confirm(count: Int) -> String {
        "Import \(count) accounts from this export?"
    }

    public static func finished(added: Int, skipped: Int) -> String {
        "Imported \(added) accounts; skipped \(skipped) identical accounts."
    }

    public static func more(_ count: Int) -> String {
        "and \(count) more"
    }

    /// The confirmation body: how many accounts, what they are called, and the warning Android shows
    /// before any of them is written. Names are only ever the issuer and the label — a seed is never
    /// rendered — and they are stripped of control and format characters first, so a crafted export
    /// cannot use the preview to rewrite the text around it.
    public static func preview(_ entries: [Entry]) -> String {
        var names: [String] = entries.prefix(previewNames).map { entry in
            let parts = [clean(entry.issuer), clean(entry.label)].filter { !$0.isEmpty }
            return parts.isEmpty ? unnamed : parts.joined(separator: " — ")
        }
        if entries.count > names.count { names.append(more(entries.count - names.count)) }
        return confirm(count: entries.count) + "\n\n" + names.joined(separator: "\n") + "\n\n"
            + duplicateNotice
    }

    /// How many names the confirmation lists before it counts the rest.
    public static let previewNames = 12
    /// The longest name the confirmation shows; Android's `take(100)`.
    public static let previewNameChars = 100

    private static func clean(_ value: String) -> String {
        let kept = value.unicodeScalars.filter { scalar in
            !CharacterSet.controlCharacters.contains(scalar)
                && scalar.properties.generalCategory != .format
        }
        return String(String.UnicodeScalarView(kept)).trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(previewNameChars).description
    }

    /// The sentence a rejected code shows, from the status the collector returned. A stale code is
    /// dismissed without a message, as Android does: the import is over either way.
    public static func message(for status: MigrationBatchStatus) -> String {
        switch status {
        case .duplicate: return alreadyScanned
        case .differentExport: return wrongBatch
        case .tooMany: return tooMany
        case .added, .complete, .stale: return ""
        }
    }
}
