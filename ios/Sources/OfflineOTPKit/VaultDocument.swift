import Foundation

/// The whole vault document: a compact JSON array of entries, encoded and decoded all-or-nothing.
///
/// A port of `core/src/main/kotlin/org/shadowice/flocke/andotp/Database/VaultJson.kt`. A malformed
/// document never yields a prefix of the user's entries: either every entry parses or the load fails,
/// because a half-loaded vault that is then saved would delete the rest.
public enum VaultDocument {
    public static func decode(_ text: String) throws -> [Entry] {
        guard text.utf8.count <= InputPolicy.maxDocumentBytes else {
            throw VaultDocumentError.tooLarge
        }
        try InputPolicy.validateJSONDepth(text)
        let value = try JSONValue.decode(text)
        guard let elements = value.arrayValue else { throw VaultDocumentError.notAnArray }
        guard elements.count <= InputPolicy.maxEntries else { throw VaultDocumentError.tooManyEntries }
        return try elements.map { element in
            do {
                return try Entry.parse(element)
            } catch {
                throw VaultDocumentError.invalidEntry
            }
        }
    }

    public static func decode(_ data: Data) throws -> [Entry] {
        guard let text = String(data: data, encoding: .utf8) else {
            throw VaultDocumentError.invalidUTF8
        }
        return try decode(text)
    }

    public static func encode(_ entries: [Entry]) throws -> String {
        guard entries.count <= InputPolicy.maxEntries else { throw VaultDocumentError.tooManyEntries }
        let text = "[" + entries.map { $0.compact() }.joined(separator: ",") + "]"
        guard text.utf8.count <= InputPolicy.maxDocumentBytes else {
            throw VaultDocumentError.tooLarge
        }
        return text
    }
}

public enum VaultDocumentError: Error, Equatable {
    case invalidUTF8
    case notAnArray
    case tooManyEntries
    case tooLarge
    case invalidEntry
}
