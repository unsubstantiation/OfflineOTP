import Foundation

/// RFC 4648 Base32, the encoding `:core` stores every entry seed in (Apache Commons Codec's
/// `Base32` on the JVM side).
///
/// Reading is strict: uppercase `A-Z` and `2-7`, optional trailing `=`, and the decoded bytes must
/// re-encode to exactly the text that was read (with or without padding). That canonical check is
/// what `EntryJson.decodeBase32Secret` does, and it rejects a seed whose trailing bits are not zero
/// instead of silently accepting two different spellings of the same bytes.
public enum Base32 {
    private static let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")

    public static func encode(_ bytes: [UInt8]) -> String {
        var out = ""
        out.reserveCapacity((bytes.count * 8 + 4) / 5)
        var buffer: UInt32 = 0
        var bits = 0
        for byte in bytes {
            buffer = (buffer << 8) | UInt32(byte)
            bits += 8
            while bits >= 5 {
                bits -= 5
                out.append(alphabet[Int((buffer >> UInt32(bits)) & 0x1F)])
            }
        }
        if bits > 0 {
            out.append(alphabet[Int((buffer << UInt32(5 - bits)) & 0x1F)])
        }
        while out.count % 8 != 0 { out.append("=") }
        return out
    }

    /// Decodes canonical Base32, or returns nil for anything else.
    public static func decode(_ text: String) -> [UInt8]? {
        let normalized = text.uppercased()
        guard !normalized.isEmpty else { return [] }
        var body = normalized
        var padding = 0
        while body.hasSuffix("=") {
            body.removeLast()
            padding += 1
        }
        guard padding <= 6, !body.isEmpty else { return nil }
        guard body.allSatisfy({ alphabet.contains($0) }) else { return nil }

        var bytes: [UInt8] = []
        bytes.reserveCapacity(body.count * 5 / 8)
        var buffer: UInt32 = 0
        var bits = 0
        for character in body {
            guard let value = alphabet.firstIndex(of: character) else { return nil }
            buffer = (buffer << 5) | UInt32(value)
            bits += 5
            if bits >= 8 {
                bits -= 8
                bytes.append(UInt8((buffer >> UInt32(bits)) & 0xFF))
            }
        }
        // The canonical round-trip check, as in `decodeBase32Secret`.
        let canonical = encode(bytes)
        guard normalized == canonical || normalized == canonical.replacingOccurrences(of: "=", with: "") else {
            return nil
        }
        return bytes
    }
}
