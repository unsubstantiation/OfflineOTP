import Foundation

/// Binary `PWD1` framing (`0x50574431`), RFC 4180 browser CSV parser (Chrome, Firefox, Safari),
/// and individual/archive `PASSWORD` / `PASSWORD_ARCHIVE` import helpers.
///
/// Mirrors `PasswordMaterial.kt`, `BrowserPasswordCsv.kt`, and `PasswordImport.kt` in `:core`.
public enum PasswordMaterial {
    public static let magic: UInt32 = 0x5057_4431 // "PWD1"
    public static let maxBytes = SecretLimit.password
    public static let maxColumns = 64

    public struct Field: Equatable {
        public let name: String
        public let value: String

        public init(name: String, value: String) {
            self.name = name
            self.value = value
        }
    }

    public struct Fields: Equatable {
        public let items: [Field]

        public init(items: [Field]) {
            self.items = items
        }

        public func value(_ name: String) -> String {
            let needle = name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            return items.first {
                $0.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == needle
            }?.value ?? ""
        }
    }

    public static func encode(columns: [String], row: BrowserPasswordCsv.Row) throws -> [UInt8] {
        guard !columns.isEmpty, columns.count <= maxColumns else {
            throw PasswordCredentialError.invalidFraming
        }
        var pairs: [Field] = []
        pairs.reserveCapacity(columns.count)
        for (idx, col) in columns.enumerated() {
            pairs.append(Field(name: col, value: row.value(at: idx)))
        }
        return try encodeFields(pairs)
    }

    public static func encodeFields(_ fields: [Field]) throws -> [UInt8] {
        guard !fields.isEmpty, fields.count <= maxColumns else {
            throw PasswordCredentialError.invalidFraming
        }
        var out: [UInt8] = []
        appendUInt32BE(&out, magic)
        appendUInt32BE(&out, UInt32(fields.count))
        for field in fields {
            guard !field.name.contains("\u{0000}"), !field.value.contains("\u{0000}") else {
                throw PasswordCredentialError.invalidFraming
            }
            let keyBytes = Array(field.name.utf8)
            let valBytes = Array(field.value.utf8)
            guard out.count + 8 + keyBytes.count + valBytes.count <= maxBytes else {
                throw PasswordCredentialError.tooLarge
            }
            appendUInt32BE(&out, UInt32(keyBytes.count))
            out.append(contentsOf: keyBytes)
            appendUInt32BE(&out, UInt32(valBytes.count))
            out.append(contentsOf: valBytes)
        }
        return out
    }

    public static func decode(_ encoded: [UInt8]) throws -> Fields {
        guard encoded.count >= 8, encoded.count <= maxBytes,
              readUInt32BE(encoded, offset: 0) == magic else {
            throw PasswordCredentialError.invalidFraming
        }
        let count = Int(readUInt32BE(encoded, offset: 4))
        guard (1...maxColumns).contains(count) else {
            throw PasswordCredentialError.invalidFraming
        }
        var offset = 8
        var items: [Field] = []
        items.reserveCapacity(count)
        for _ in 0..<count {
            guard offset + 4 <= encoded.count else { throw PasswordCredentialError.invalidFraming }
            let keyLen = Int(readUInt32BE(encoded, offset: offset))
            offset += 4
            guard keyLen >= 0, offset + keyLen + 4 <= encoded.count else {
                throw PasswordCredentialError.invalidFraming
            }
            guard let key = String(bytes: encoded[offset..<(offset + keyLen)], encoding: .utf8),
                  !key.contains("\u{0000}") else {
                throw PasswordCredentialError.invalidFraming
            }
            offset += keyLen

            let valLen = Int(readUInt32BE(encoded, offset: offset))
            offset += 4
            guard valLen >= 0, offset + valLen <= encoded.count else {
                throw PasswordCredentialError.invalidFraming
            }
            guard let val = String(bytes: encoded[offset..<(offset + valLen)], encoding: .utf8),
                  !val.contains("\u{0000}") else {
                throw PasswordCredentialError.invalidFraming
            }
            offset += valLen
            items.append(Field(name: key, value: val))
        }
        guard offset == encoded.count else { throw PasswordCredentialError.invalidFraming }
        return Fields(items: items)
    }

    private static func appendUInt32BE(_ buffer: inout [UInt8], _ value: UInt32) {
        buffer.append(UInt8((value >> 24) & 0xff))
        buffer.append(UInt8((value >> 16) & 0xff))
        buffer.append(UInt8((value >> 8) & 0xff))
        buffer.append(UInt8(value & 0xff))
    }

    private static func readUInt32BE(_ buffer: [UInt8], offset: Int) -> UInt32 {
        (UInt32(buffer[offset]) << 24)
            | (UInt32(buffer[offset + 1]) << 16)
            | (UInt32(buffer[offset + 2]) << 8)
            | UInt32(buffer[offset + 3])
    }
}

/// RFC 4180 CSV parser for Chrome, Firefox, and Safari password exports.
public enum BrowserPasswordCsv {
    public struct Row: Equatable {
        public let values: [String]

        public init(values: [String]) {
            self.values = values
        }

        public func value(at index: Int) -> String {
            (0..<values.count).contains(index) ? values[index] : ""
        }
    }

    public struct Table: Equatable {
        public let columns: [String]
        public let rows: [Row]
    }

    public static func parse(_ data: Data) throws -> Table {
        guard !data.isEmpty, data.count <= PasswordMaterial.maxBytes else {
            throw PasswordCredentialError.tooLarge
        }
        guard let rawText = String(data: data, encoding: .utf8), !rawText.contains("\u{0000}") else {
            throw PasswordCredentialError.invalidCsv
        }
        let text = rawText.hasPrefix("\u{FEFF}") ? String(rawText.dropFirst()) : rawText
        let records = try parseRfc4180(text, maxColumns: PasswordMaterial.maxColumns, maxRows: InputPolicy.maxEntries + 1)
        guard records.count >= 2 else { throw PasswordCredentialError.invalidCsv }

        let header = records[0]
        guard !header.isEmpty, header.count <= PasswordMaterial.maxColumns else {
            throw PasswordCredentialError.invalidCsv
        }
        let lowered = header.map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
        guard lowered.contains("password"),
              lowered.contains("username") || lowered.contains("url") || lowered.contains("name") || lowered.contains("title") else {
            throw PasswordCredentialError.invalidCsv
        }

        var rows: [Row] = []
        rows.reserveCapacity(records.count - 1)
        for record in records.dropFirst() {
            if record.count == 1 && record[0].trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                continue
            }
            guard record.count <= PasswordMaterial.maxColumns else {
                throw PasswordCredentialError.invalidCsv
            }
            rows.append(Row(values: record))
        }
        guard !rows.isEmpty, rows.count <= InputPolicy.maxEntries else {
            throw PasswordCredentialError.invalidCsv
        }
        return Table(columns: header, rows: rows)
    }

    static func parseRfc4180(_ text: String, maxColumns: Int, maxRows: Int) throws -> [[String]] {
        var records: [[String]] = []
        var currentRow: [String] = []
        var currentField = ""
        var inQuotes = false
        let chars = Array(text)
        var i = 0

        while i < chars.count {
            let ch = chars[i]
            if inQuotes {
                if ch == "\"" {
                    if i + 1 < chars.count && chars[i + 1] == "\"" {
                        currentField.append("\"")
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                        i += 1
                        continue
                    }
                } else {
                    currentField.append(ch)
                    i += 1
                }
            } else {
                if ch == "\"" {
                    guard currentField.isEmpty else { throw PasswordCredentialError.invalidCsv }
                    inQuotes = true
                    i += 1
                } else if ch == "," {
                    currentRow.append(currentField)
                    currentField = ""
                    guard currentRow.count <= maxColumns else { throw PasswordCredentialError.invalidCsv }
                    i += 1
                } else if ch == "\r" || ch == "\n" {
                    if ch == "\r" && i + 1 < chars.count && chars[i + 1] == "\n" {
                        i += 1
                    }
                    currentRow.append(currentField)
                    currentField = ""
                    if !(currentRow.count == 1 && currentRow[0].isEmpty && records.isEmpty) {
                        records.append(currentRow)
                        guard records.count <= maxRows else { throw PasswordCredentialError.invalidCsv }
                    }
                    currentRow = []
                    i += 1
                } else {
                    currentField.append(ch)
                    i += 1
                }
            }
        }
        if inQuotes { throw PasswordCredentialError.invalidCsv }
        if !currentField.isEmpty || !currentRow.isEmpty {
            currentRow.append(currentField)
            guard currentRow.count <= maxColumns else { throw PasswordCredentialError.invalidCsv }
            records.append(currentRow)
            guard records.count <= maxRows else { throw PasswordCredentialError.invalidCsv }
        }
        return records
    }
}

/// High-level password import builder matching `PasswordImport.kt` in `:core`.
public enum PasswordImport {
    public enum Mode: Equatable {
        case individual
        case archive
    }

    public static func entries(filename: String, data: Data, mode: Mode) throws -> [Entry] {
        switch mode {
        case .individual:
            let table = try BrowserPasswordCsv.parse(data)
            return try table.rows.map { row in
                try Entry.password(columns: table.columns, row: row)
            }
        case .archive:
            return [try Entry.passwordArchive(filename: filename, data: data)]
        }
    }
}

public enum PasswordCredentialError: Error, Equatable {
    case invalidFraming
    case tooLarge
    case invalidCsv
}
