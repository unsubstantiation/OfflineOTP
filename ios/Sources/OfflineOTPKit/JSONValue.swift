import Foundation

/// A decoded JSON value.
///
/// The vault document is untrusted input that must be written back without losing anything, so the
/// model is deliberately generic: an entry is a bag of named values, not a fixed struct. A field this
/// client does not understand (an entry kind it cannot display, a key a newer build added) survives a
/// load/save cycle because it is still in the bag when the document is written again.
public enum JSONValue: Equatable, Hashable {
    case string(String)
    case int(Int64)
    case double(Double)
    case bool(Bool)
    case null
    case array([JSONValue])
    case object([String: JSONValue])

    /// A decoded value as `String`, or nil for any other type.
    public var stringValue: String? {
        if case .string(let value) = self { return value }
        return nil
    }

    /// A decoded value as an integer. Only a JSON integer qualifies: a float is not coerced, because
    /// the JVM reader rejects one too (`INTEGER_TEXT` never matches `6.0`).
    public var intValue: Int64? {
        if case .int(let value) = self { return value }
        return nil
    }

    public var boolValue: Bool? {
        if case .bool(let value) = self { return value }
        return nil
    }

    public var arrayValue: [JSONValue]? {
        if case .array(let value) = self { return value }
        return nil
    }

    public var objectValue: [String: JSONValue]? {
        if case .object(let value) = self { return value }
        return nil
    }

    /// The compact rendering used by the vault document. String quoting reproduces Android's
    /// `org.json` (`JSONObject.quote`) exactly, including its single oddity: a forward slash is
    /// escaped only when it follows `<`, which is what keeps a vault written here byte-identical to
    /// one written by the phone.
    public func compact() -> String {
        switch self {
        case .string(let value):
            return JSONValue.quote(value)
        case .int(let value):
            return String(value)
        case .double(let value):
            // Doubles are never written by any client; one can only arrive as a preserved field.
            if value.rounded() == value, abs(value) < 1e15 {
                return String(Int64(value))
            }
            return String(value)
        case .bool(let value):
            return value ? "true" : "false"
        case .null:
            return "null"
        case .array(let values):
            return "[" + values.map { $0.compact() }.joined(separator: ",") + "]"
        case .object(let fields):
            let names = fields.keys.sorted()
            let body = names.map { JSONValue.quote($0) + ":" + fields[$0]!.compact() }
            return "{" + body.joined(separator: ",") + "}"
        }
    }

    /// Android `org.json` quoting rules, character for character.
    public static func quote(_ value: String) -> String {
        var out = "\""
        var previous: Character = "\u{0}"
        for character in value {
            switch character {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\t": out += "\\t"
            case "\u{08}": out += "\\b"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\u{0C}": out += "\\f"
            case "/":
                // org.json escapes a slash only to keep "</script>" out of embedded HTML.
                out += previous == "<" ? "\\/" : "/"
            default:
                if character.asciiValue.map({ $0 <= 0x1F }) ?? false {
                    out += String(format: "\\u%04x", character.asciiValue ?? 0)
                } else {
                    out.append(character)
                }
            }
            previous = character
        }
        return out + "\""
    }
}

/// One named field of an entry, kept in a list so the stored order can be reproduced.
public struct JSONField: Equatable {
    public let name: String
    public let value: JSONValue

    public init(_ name: String, _ value: JSONValue) {
        self.name = name
        self.value = value
    }
}

extension JSONValue {
    /// Decodes JSON text through Foundation and converts it into the typed model above.
    ///
    /// `JSONSerialization` is used instead of `JSONDecoder` because the document may contain numbers
    /// this client has no property for, and because fractions have to stay distinguishable from
    /// integers — the JVM reader rejects `"digits": 6.0` and this reader has to agree with it.
    public static func decode(_ text: String) throws -> JSONValue {
        guard let data = text.data(using: .utf8) else {
            throw JSONDecodeError.invalidUTF8
        }
        return try decode(data)
    }

    public static func decode(_ data: Data) throws -> JSONValue {
        let parsed: Any
        do {
            parsed = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        } catch {
            throw JSONDecodeError.malformed
        }
        return try convert(parsed, depth: 0)
    }

    private static func convert(_ value: Any, depth: Int) throws -> JSONValue {
        if depth > InputPolicy.maxJSONDepth { throw JSONDecodeError.tooDeep }
        if value is NSNull { return .null }
        if let text = value as? String { return .string(text) }
        if let number = value as? NSNumber {
            let type = String(cString: number.objCType)
            switch type {
            case "c", "B":
                // JSON true/false arrive as CFBoolean, whose objCType is "c".
                return .bool(number.boolValue)
            case "d", "f":
                return .double(number.doubleValue)
            default:
                return .int(number.int64Value)
            }
        }
        if let array = value as? [Any] {
            return .array(try array.map { try convert($0, depth: depth + 1) })
        }
        if let object = value as? [String: Any] {
            var fields: [String: JSONValue] = [:]
            for (name, element) in object {
                fields[name] = try convert(element, depth: depth + 1)
            }
            return .object(fields)
        }
        throw JSONDecodeError.unsupportedValue
    }
}

public enum JSONDecodeError: Error, Equatable {
    case invalidUTF8
    case malformed
    case tooDeep
    case unsupportedValue
}
