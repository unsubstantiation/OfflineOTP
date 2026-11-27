import Foundation

/// Enforces the byte limit while reading, including short reads and files growing after selection.
public enum BoundedFileReader {
    public enum Failure: Error, Equatable { case invalidLimit, tooLarge }
    public static func read(_ url: URL, maxBytes: Int = InputPolicy.maxDocumentBytes) throws -> Data {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        return try read(maxBytes: maxBytes) { try handle.read(upToCount: $0) }
    }
    static func read(maxBytes: Int, chunk: (Int) throws -> Data?) throws -> Data {
        guard maxBytes >= 0, maxBytes < Int.max else { throw Failure.invalidLimit }
        var result = Data()
        while true {
            let remaining = maxBytes - result.count
            guard let next = try chunk(min(64 * 1024, remaining + 1)), !next.isEmpty else { return result }
            guard next.count <= remaining else { throw Failure.tooLarge }
            result.append(next)
        }
    }
}
