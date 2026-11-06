import Foundation

/// Length-prefixed binary framing for `SSH_KEY` entry payloads, mirroring
/// `core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/SshKeyMaterial.kt`.
///
/// Canonical wire layout: `[4-byte BE pubLen][pub UTF-8][4-byte BE privLen][priv UTF-8]`.
/// Legacy `"SSH1"`-prefixed payloads (`0x53534831`) are also accepted when decoding.
public enum SshKeyMaterial {
    public static let maxKeyBytes = 32 * 1024
    public static let maxEncodedBytes = 2 * maxKeyBytes + 8
    private static let legacyMagic: UInt32 = 0x5353_4831 // "SSH1"

    public struct Pair: Equatable {
        public let publicKey: String
        public let privateKey: String

        public init(publicKey: String, privateKey: String) {
            self.publicKey = publicKey
            self.privateKey = privateKey
        }
    }

    public static func encode(publicKey: String, privateKey: String) throws -> [UInt8] {
        let pubTrimmed = publicKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let privTrimmed = privateKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !pubTrimmed.isEmpty || !privTrimmed.isEmpty else {
            throw SshMaterialError.emptyKeys
        }
        guard !pubTrimmed.contains("\u{0000}"), !privTrimmed.contains("\u{0000}") else {
            throw SshMaterialError.invalidKeyText
        }
        let pubBytes = Array(pubTrimmed.utf8)
        let privBytes = Array(privTrimmed.utf8)
        guard pubBytes.count <= maxKeyBytes, privBytes.count <= maxKeyBytes else {
            throw SshMaterialError.keyTooLarge
        }
        var out: [UInt8] = []
        out.reserveCapacity(8 + pubBytes.count + privBytes.count)
        appendUInt32BE(&out, UInt32(pubBytes.count))
        out.append(contentsOf: pubBytes)
        appendUInt32BE(&out, UInt32(privBytes.count))
        out.append(contentsOf: privBytes)
        return out
    }

    public static func decode(_ encoded: [UInt8]) throws -> Pair {
        guard encoded.count >= 8, encoded.count <= maxEncodedBytes + 4 else {
            throw SshMaterialError.corruptFraming
        }
        var offset = 0
        if encoded.count >= 12, readUInt32BE(encoded, offset: 0) == legacyMagic {
            offset = 4
        }
        guard offset + 4 <= encoded.count else { throw SshMaterialError.corruptFraming }
        let pubLen = Int(readUInt32BE(encoded, offset: offset))
        offset += 4
        guard pubLen >= 0, pubLen <= maxKeyBytes, offset + pubLen + 4 <= encoded.count else {
            throw SshMaterialError.corruptFraming
        }
        let pubSlice = encoded[offset..<(offset + pubLen)]
        offset += pubLen

        let privLen = Int(readUInt32BE(encoded, offset: offset))
        offset += 4
        guard privLen >= 0, privLen <= maxKeyBytes, offset + privLen == encoded.count else {
            throw SshMaterialError.corruptFraming
        }
        let privSlice = encoded[offset..<(offset + privLen)]

        guard let publicKey = String(bytes: pubSlice, encoding: .utf8),
              let privateKey = String(bytes: privSlice, encoding: .utf8),
              !publicKey.contains("\u{0000}"), !privateKey.contains("\u{0000}") else {
            throw SshMaterialError.invalidKeyText
        }
        guard !publicKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
              !privateKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw SshMaterialError.emptyKeys
        }
        return Pair(publicKey: publicKey, privateKey: privateKey)
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

/// Optional SSH destination metadata (`host`, `port`, `username`, `password`), mirroring
/// `core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/SshConnection.kt`.
public enum SshConnection {
    public static let defaultPort = 22
    public static let minPort = 1
    public static let maxPort = 65_535
    public static let maxHostChars = 255
    public static let maxUsernameChars = 128
    public static let maxPasswordChars = 256

    public struct Details: Equatable, CustomStringConvertible {
        public let host: String
        public let port: Int
        public let username: String
        public let password: String

        fileprivate init(host: String, port: Int, username: String, password: String) {
            self.host = host
            self.port = port
            self.username = username
            self.password = password
        }

        public var isSet: Bool {
            !host.isEmpty || port != 0 || !username.isEmpty || !password.isEmpty
        }

        public var displayPort: Int {
            port == 0 ? SshConnection.defaultPort : port
        }

        /// `user@host:port` for card subtitles (`nil` when `host` is empty).
        public var summary: String? {
            guard !host.isEmpty else { return nil }
            let target = (port == 0 || port == SshConnection.defaultPort) ? host : "\(host):\(port)"
            return username.isEmpty ? target : "\(username)@\(target)"
        }

        public var identityKey: [String] {
            [host, String(port), username]
        }

        public var description: String {
            "SshConnection.Details(redacted)"
        }
    }

    public static func of(
        host: String,
        port: Int,
        username: String,
        password: String
    ) throws -> Details? {
        let trimmedHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedUser = username.trimmingCharacters(in: .whitespacesAndNewlines)
        try validateNoWhitespaceOrControls(trimmedHost, limit: maxHostChars)
        try validateNoWhitespaceOrControls(trimmedUser, limit: maxUsernameChars)
        guard !password.contains("\u{0000}"), password.count <= maxPasswordChars else {
            throw SshMaterialError.invalidConnection
        }
        guard port == 0 || (minPort...maxPort).contains(port) else {
            throw SshMaterialError.invalidPort
        }
        let details = Details(host: trimmedHost, port: port, username: trimmedUser, password: password)
        return details.isSet ? details : nil
    }

    public static func parsePort(_ raw: String) throws -> Int {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return 0 }
        guard trimmed.allSatisfy({ $0.isASCII && $0.isNumber }),
              let parsed = Int(trimmed),
              (minPort...maxPort).contains(parsed) else {
            throw SshMaterialError.invalidPort
        }
        return parsed
    }

    private static func validateNoWhitespaceOrControls(_ text: String, limit: Int) throws {
        guard text.count <= limit else { throw SshMaterialError.invalidConnection }
        for scalar in text.unicodeScalars {
            if scalar.value <= 0x20 || scalar.value == 0x7f {
                throw SshMaterialError.invalidConnection
            }
        }
    }
}

public enum SshMaterialError: Error, Equatable {
    case emptyKeys
    case keyTooLarge
    case invalidKeyText
    case corruptFraming
    case invalidConnection
    case invalidPort
}
