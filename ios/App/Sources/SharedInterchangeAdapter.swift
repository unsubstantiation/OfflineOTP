import Foundation
import OfflineOTPKit
import OfflineOTPPresentation

struct SharedInterchangeAdapter: InterchangeAdapter {
    private let codec = InterchangeCodec()
    func browserDialect(_ columns: [String]) -> String? { BrowserCsvPolicy().dialect(columns: columns) }
    func browserHeader(_ dialect: String) -> [String] { BrowserCsvPolicy().header(dialect: dialect) }
    func browserCommonEdit(_ columns: [String]) -> Bool { BrowserCsvPolicy().commonEdit(columns: columns) }
    func migrationBatch(generation: Int) -> MigrationBatchAuthority { SharedMigrationBatchAuthority(generation: generation) }
    private func bytes(_ data: Data) -> KotlinByteArray {
        let out = KotlinByteArray(size: Int32(data.count))
        for (i, byte) in data.enumerated() { out.set(index: Int32(i), value: Int8(bitPattern: byte)) }
        return out
    }
    private func array(_ data: KotlinByteArray) -> [UInt8] {
        (0..<Int(data.size)).map { UInt8(bitPattern: data.get(index: Int32($0))) }
    }
    private func clear(_ data: KotlinByteArray) {
        for i in 0..<data.size { data.set(index: i, value: 0) }
    }
    private func adopt(_ value: PortableOtp) throws -> OtpUri {
        guard let kind = EntryKind(rawValue: value.kind.name),
              let algorithm = OtpHashAlgorithm(rawValue: value.algorithm.name) else { throw OtpParseError.invalidParameters }
        let seed = value.copySecret(); defer { clear(seed) }
        return OtpUri(kind: kind, secret: array(seed), period: Int(value.period), digits: Int(value.digits),
                      counter: value.counter, issuer: value.issuer, label: value.label, algorithm: algorithm, tags: value.tags)
    }
    func otpUri(_ entry: Entry) -> String? {
        guard let kind = entry.kind, kind.isOtp, let secret = entry.secretBytes else { return nil }
        let seed = bytes(Data(secret)); defer { clear(seed) }
        return ProvisioningCodec.shared.encode(kind: kind.rawValue, seed: seed, period: Int32(entry.period), digits: Int32(entry.digits),
            counter: entry.counter, issuer: entry.issuer, label: entry.label, algorithm: entry.algorithm.rawValue, tags: entry.tags)
    }
    func otp(_ input: String) throws -> OtpUri {
        let result = codec.otp(input: input)
        guard let value = result.value else { throw OtpParseError(rawValue: result.error ?? "INVALID_URI") ?? .invalidUri }
        defer { value.destroy() }
        return try adopt(value)
    }
    func migration(_ input: String) throws -> MigrationPayload {
        let result = codec.migration(input: input)
        guard let value = result.value else { throw MigrationError(rawValue: result.error ?? "INVALID_DATA") ?? .invalidData }
        let accounts = value.takeAccounts()
        defer { accounts.forEach { $0.destroy() }; value.destroy() }
        let adopted = try accounts.map { source -> MigrationAccount in
            let account = try adopt(source)
            return MigrationAccount(kind: account.kind, secret: account.secret, digits: account.digits,
                                    algorithm: account.algorithm, counter: account.counter, issuer: account.issuer, label: account.label)
        }
        return MigrationPayload(version: Int(value.version), batchSize: Int(value.batchSize), batchIndex: Int(value.batchIndex),
                                batchId: Int(value.batchId), accounts: adopted)
    }
    func csv(_ data: Data, thunderbird: Bool) throws -> InterchangeCsvTable {
        guard data.count <= 4 * 1024 * 1024 else { throw PasswordCredentialError.tooLarge }
        let input = bytes(data); defer { clear(input) }
        let result = codec.csv(bytes: input, thunderbird: thunderbird)
        guard result.error == nil else {
            if thunderbird { throw EmailCredentialError.invalidThunderbirdCsv }
            throw PasswordCredentialError.invalidCsv
        }
        return InterchangeCsvTable(columns: result.columns, rows: result.rows.map { $0.fields })
    }
    func exportCsv(columns: [String], rows: [[String]]) throws -> Data {
        guard let encoded = codec.exportCsv(columns: columns, rows: rows.map { CsvRecord(fields: $0) }) else {
            throw PasswordCredentialError.tooLarge
        }
        defer { clear(encoded) }
        return Data(array(encoded))
    }
    func preferences(_ data: Data, platform: String) throws -> Data {
        guard data.count <= PreferencesExport.maxBytes, let text = String(data: data, encoding: .utf8) else {
            throw PreferencesExport.PreferencesError.invalidEncoding
        }
        let rules = PreferencesExport.catalog.map { PreferenceRule(key: $0.key, kind: $0.kind.rawValue) }
        let result = PreferencesInterchangeCodec().decode(text: text, platform: platform, rules: rules,
                                                        deviceBound: PreferencesExport.neverExported.map { $0.key })
        if result.error == "PLATFORM" { throw PreferencesExport.PreferencesError.wrongPlatform(source: result.source, target: platform) }
        guard let json = result.json, result.error == nil else { throw PreferencesExport.PreferencesError.invalidDocument }
        return Data(json.utf8)
    }

    func linScp(_ text: String, bundle: Bool) throws -> String {
        guard let json = CredentialDocumentCodec().linScp(text: text, bundle: bundle) else { throw LinScpVaultError.invalidFormat }
        return json
    }

    func linScpIni(_ text: String) throws -> [InterchangeIniSection] {
        guard let sections = LinScpSessionCodec().parse(text: text) else { throw LinScpVaultError.invalidFormat }
        return sections.map { InterchangeIniSection(name: $0.name, fields: $0.fields) }
    }

}

private final class SharedMigrationBatchAuthority: MigrationBatchAuthority {
    private let policy: MigrationBatchPolicy
    init(generation: Int) { policy = MigrationBatchPolicy(generation: Int64(generation)) }
    var received: Int { Int(policy.received) }
    var expected: Int { Int(policy.expected) }
    var isOpen: Bool { !policy.closed }
    func add(_ payload: MigrationPayload, generation: Int, sessionReady: Bool) -> MigrationBatchStatus {
        guard let version = Int32(exactly: payload.version), let size = Int32(exactly: payload.batchSize),
              let index = Int32(exactly: payload.batchIndex), let id = Int32(exactly: payload.batchId),
              let count = Int32(exactly: payload.count) else { policy.cancel(); return .tooMany }
        let result = policy.add(version: version, size: size, index: index,
                                id: id, count: count, currentGeneration: Int64(generation), sessionReady: sessionReady)
        switch result {
        case .added: return .added
        case .complete: return .complete
        case .duplicate: return .duplicate
        case .differentExport: return .differentExport
        case .tooMany: return .tooMany
        default: return .stale
        }
    }
    func take(generation: Int, sessionReady: Bool) -> Bool { policy.take(currentGeneration: Int64(generation), sessionReady: sessionReady) }
    func cancel() { policy.cancel() }
}
