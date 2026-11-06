import Foundation

/// The input bounds `:core` enforces, restated for Swift.
///
/// Every number here has a counterpart in `core/src/main/kotlin/com/fairuniversal/offlineotp/
/// Utilities/InputPolicy.kt`, `Constants.kt` or `BoundedInputReader.kt`, and
/// `scripts/tests/test_ios_plan.py` fails when the two disagree. A client that accepted what another
/// rejects would either write a vault the other cannot open or silently drop data.
public enum InputPolicy {
    public static let maxEntries = 2000
    public static let maxJSONDepth = 32
    public static let maxTextChars = 1024
    public static let maxTags = 32
    public static let maxTagChars = 128
    public static let maxSecretBytes = 2048
    public static let maxNoteCharacters = 200
    public static let maxPeriodSeconds = 86400
    public static let maxDocumentBytes = 16 * 1024 * 1024
    /// The longest `otpauth://` link accepted before parsing starts (`MAX_URI_CHARS`).
    public static let maxURIChars = 16384
    public static let maxVaultPasswordChars = 1024
    /// The shortest password either existing client accepts when a vault is created
    /// (`Constants.AUTH_MIN_PASSWORD_LENGTH`). Enforced by the UIs, not by the vault engine.
    public static let minVaultPasswordChars = 6

    /// Descriptions of the OTP parameters a document may carry; mirrors `tokenParameters`.
    public static func validateTokenParameters(period: Int, digits: Int, counter: Int64) throws {
        guard period >= 1, period <= maxPeriodSeconds, digits >= 1, digits <= 9,
              counter >= 0, counter != Int64.max else {
            throw InputPolicyError.invalidTokenParameters
        }
    }

    /// Notes are non-empty, valid Unicode and bounded in Unicode code points, not UTF-8 bytes.
    public static func validateNote(_ value: String) throws {
        guard !value.isEmpty, value.unicodeScalars.count <= maxNoteCharacters else {
            throw InputPolicyError.invalidNote
        }
    }

    public static func validateText(_ value: String?, limit: Int) throws {
        guard let value else { return }
        guard value.count <= limit else { throw InputPolicyError.textTooLong }
    }

    /// Scans the raw document before the recursive parser sees it: rejects single quotes, comments
    /// and a depth that would exhaust the stack, exactly as `InputPolicy.jsonDepth` does.
    public static func validateJSONDepth(_ data: String) throws {
        var depth = 0
        var quoted = false
        var index = data.startIndex
        while index < data.endIndex {
            let character = data[index]
            if quoted, character == "\\" {
                // Skip the escaped character; a trailing backslash fails the `quoted` check below.
                index = data.index(index, offsetBy: 2, limitedBy: data.endIndex) ?? data.endIndex
                continue
            }
            if character == "\"" {
                quoted.toggle()
                index = data.index(after: index)
                continue
            }
            if quoted {
                index = data.index(after: index)
                continue
            }
            if character == "'" || character == "/" || character == "#" || character == "\\" {
                throw InputPolicyError.invalidJSONSyntax
            }
            if character == "[" || character == "{" {
                depth += 1
                if depth > maxJSONDepth { throw InputPolicyError.jsonTooDeep }
            } else if character == "]" || character == "}" {
                depth -= 1
                if depth < 0 { throw InputPolicyError.invalidJSONNesting }
            }
            index = data.index(after: index)
        }
        if depth != 0 || quoted { throw InputPolicyError.invalidJSONNesting }
    }
}

public enum InputPolicyError: Error, Equatable {
    case invalidTokenParameters
    case invalidNote
    case textTooLong
    case invalidJSONSyntax
    case jsonTooDeep
    case invalidJSONNesting
}
