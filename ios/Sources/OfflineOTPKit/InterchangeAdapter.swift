import Foundation

/// Production injects the shared codecs. Standalone SwiftPM retains native compatibility oracles;
/// shared failures never fall back to a different parser. Material remains local to the operation.
public struct InterchangeCsvTable: CustomStringConvertible, CustomDebugStringConvertible {
    public var description: String { "InterchangeCsvTable(redacted)" }
    public var debugDescription: String { description }
    public let columns: [String]
    public let rows: [[String]]
    public init(columns: [String], rows: [[String]]) { self.columns = columns; self.rows = rows }
}
public struct InterchangeIniSection: CustomStringConvertible, CustomDebugStringConvertible {
    public var description: String { "InterchangeIniSection(redacted)" }
    public var debugDescription: String { description }
    public let name: String
    public let fields: [String: String]
    public init(name: String, fields: [String: String]) { self.name = name; self.fields = fields }
}
public protocol MigrationBatchAuthority: AnyObject {
    var received: Int { get }
    var expected: Int { get }
    var isOpen: Bool { get }
    func add(_ payload: MigrationPayload, generation: Int, sessionReady: Bool) -> MigrationBatchStatus
    func take(generation: Int, sessionReady: Bool) -> Bool
    func cancel()
}
public protocol InterchangeAdapter {
    func browserDialect(_ columns: [String]) -> String?
    func browserHeader(_ dialect: String) -> [String]
    func browserCommonEdit(_ columns: [String]) -> Bool
    func migrationBatch(generation: Int) -> MigrationBatchAuthority
    func otpUri(_ entry: Entry) -> String?
    func otp(_ input: String) throws -> OtpUri
    func migration(_ input: String) throws -> MigrationPayload
    func csv(_ data: Data, thunderbird: Bool) throws -> InterchangeCsvTable
    func exportCsv(columns: [String], rows: [[String]]) throws -> Data
    func preferences(_ data: Data, platform: String) throws -> Data
    func linScp(_ text: String, bundle: Bool) throws -> String
    func linScpIni(_ text: String) throws -> [InterchangeIniSection]
}
