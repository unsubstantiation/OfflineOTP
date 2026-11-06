import Foundation
import CryptoKit
import CommonCrypto
import zlib

/// FairEmail account transport metadata (`EMAIL` vault entries), `.json` / gzip / `.efb` (v0 & v1)
/// backup import & export, and Thunderbird CSV bidirectional interchange.
///
/// Mirrors `EmailMaterial.kt`, `ThunderbirdCsv.kt`, and `ThunderbirdImport.kt` in `:core`.
public enum EmailMaterial {
    public static let maxBytes = SecretLimit.email
    public static let maxCredentialChars = 16 * 1024
    public static let maxImportBytes = 8 * 1024 * 1024
    public static let secretMask = "********"

    public struct Account: Equatable, CustomStringConvertible {
        public let account: [String: JSONValue]
        public let identities: [[String: JSONValue]]

        public init(account: [String: JSONValue], identities: [[String: JSONValue]]) {
            self.account = account
            self.identities = identities
        }

        public var identity: [String: JSONValue] { identities[0] }

        public var name: String {
            let raw = account["name"]?.stringValue ?? ""
            return raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? address : raw
        }

        public var address: String {
            identities.first?["email"]?.stringValue ?? ""
        }

        public var description: String {
            "EmailAccount(redacted)"
        }
    }

    public struct Detail: Equatable {
        public let label: String
        public let value: String
        public let secret: Bool

        public init(label: String, value: String, secret: Bool = false) {
            self.label = label
            self.value = value
            self.secret = secret
        }
    }

    public static func details(account: Account, revealSecrets: Bool) -> [Detail] {
        var rows: [Detail] = []
        rows.append(Detail(label: "Account", value: account.account["name"]?.stringValue ?? ""))
        rows.append(Detail(label: "Address", value: account.address))
        let proto = (intField(account.account, "protocol") == 1) ? "POP" : "IMAP"
        rows.append(contentsOf: connectionRows(title: "Incoming (\(proto))", json: account.account, revealSecrets: revealSecrets))
        for (index, identity) in account.identities.enumerated() {
            let suffix = index == 0 ? "" : " #\(index + 1)"
            rows.append(contentsOf: connectionRows(title: "Outgoing (SMTP)\(suffix)", json: identity, revealSecrets: revealSecrets))
        }
        return rows
    }

    private static func connectionRows(title: String, json: [String: JSONValue], revealSecrets: Bool) -> [Detail] {
        let password = json["password"]?.stringValue ?? ""
        let shown: String
        if password.isEmpty {
            shown = ""
        } else if revealSecrets {
            shown = password
        } else {
            shown = secretMask
        }
        let encMode = intField(json, "encryption") ?? 0
        return [
            Detail(label: "\(title) server", value: json["host"]?.stringValue ?? ""),
            Detail(label: "\(title) port", value: String(intField(json, "port") ?? 0)),
            Detail(label: "\(title) encryption", value: encryptionName(encMode)),
            Detail(label: "\(title) authentication", value: String(intField(json, "auth_type") ?? 0)),
            Detail(label: "\(title) user name", value: json["user"]?.stringValue ?? ""),
            Detail(label: "\(title) password", value: shown, secret: true),
        ]
    }

    private static func encryptionName(_ value: Int) -> String {
        switch value {
        case 0: return "SSL"
        case 1: return "STARTTLS"
        case 2: return "None"
        default: return "Unknown (\(value))"
        }
    }

    public static func create(
        name: String,
        address: String,
        incomingHost: String,
        incomingPort: Int,
        incomingEncryption: Int,
        incomingProtocol: Int,
        incomingUser: String,
        incomingPassword: String,
        incomingAuth: Int = 1,
        outgoingHost: String,
        outgoingPort: Int,
        outgoingEncryption: Int,
        outgoingUser: String,
        outgoingPassword: String,
        outgoingAuth: Int = 1
    ) throws -> Account {
        let cleanName = try cleanText(name)
        let cleanAddr = try cleanText(address)
        let cleanInHost = try cleanText(incomingHost)
        let cleanInUser = try cleanText(incomingUser)
        let cleanOutHost = try cleanText(outgoingHost)
        let cleanOutUser = try cleanText(outgoingUser)

        let account: [String: JSONValue] = [
            "id": .int(1),
            "uuid": .string(UUID().uuidString.lowercased()),
            "order": .int(0),
            "protocol": .int(Int64(incomingProtocol)),
            "host": .string(cleanInHost),
            "encryption": .int(Int64(incomingEncryption)),
            "insecure": .bool(false),
            "port": .int(Int64(incomingPort)),
            "auth_type": .int(Int64(incomingAuth)),
            "user": .string(cleanInUser),
            "password": .string(incomingPassword),
            "name": .string(cleanName),
            "synchronize": .bool(true),
            "primary": .bool(true),
            "poll_interval": .int(15),
            "ondemand": .bool(false),
            "poll_exempted": .bool(false),
            "notify": .bool(true),
            "browse": .bool(true),
            "leave_on_server": .bool(true),
            "client_delete": .bool(false),
            "leave_deleted": .bool(true),
            "leave_on_device": .bool(true),
            "auto_seen": .bool(true),
            "keep_alive_noop": .bool(false),
            "partial_fetch": .bool(true),
            "raw_fetch": .bool(false),
            "ignore_size": .bool(false),
            "use_date": .bool(false),
            "use_received": .bool(false),
            "unicode": .bool(false),
            "folders": .array([]),
        ]
        let identity: [String: JSONValue] = [
            "id": .int(1),
            "uuid": .string(UUID().uuidString.lowercased()),
            "name": .string(cleanName),
            "email": .string(cleanAddr),
            "display": .string(""),
            "host": .string(cleanOutHost),
            "encryption": .int(Int64(outgoingEncryption)),
            "insecure": .bool(false),
            "port": .int(Int64(outgoingPort)),
            "auth_type": .int(Int64(outgoingAuth)),
            "user": .string(cleanOutUser),
            "password": .string(outgoingPassword),
            "login": .bool(true),
            "use_ip": .bool(true),
            "synchronize": .bool(true),
            "primary": .bool(true),
            "self": .bool(true),
            "sender_extra": .bool(false),
            "sender_extra_name": .bool(false),
            "reply_extra_name": .bool(false),
            "unicode": .bool(false),
            "octetmime": .bool(false),
            "sign_default": .bool(false),
            "encrypt_default": .bool(false),
            "sensitivity": .int(0),
        ]
        try validatePair(account: account, identity: identity)
        return Account(account: account, identities: [identity])
    }

    public static func encode(account: [String: JSONValue], identities: [[String: JSONValue]]) throws -> [UInt8] {
        guard (1...128).contains(identities.count) else {
            throw EmailCredentialError.invalidAccount
        }
        for identity in identities {
            try validatePair(account: account, identity: identity)
        }
        let envelope: JSONValue = .object([
            "fairmail_account": .object(account),
            "fairmail_identities": .array(identities.map { .object($0) }),
        ])
        let bytes = Array(envelope.compact().utf8)
        guard bytes.count <= maxBytes else {
            throw EmailCredentialError.tooLarge
        }
        return bytes
    }

    public static func decode(_ bytes: [UInt8]) throws -> Account {
        guard !bytes.isEmpty, bytes.count <= maxBytes,
              let text = String(bytes: bytes, encoding: .utf8),
              !text.contains("\u{0000}") else {
            throw EmailCredentialError.invalidAccount
        }
        try InputPolicy.validateJSONDepth(text)
        guard let envelope = (try JSONValue.decode(text)).objectValue,
              let account = envelope["fairmail_account"]?.objectValue else {
            throw EmailCredentialError.invalidAccount
        }
        let identities: [[String: JSONValue]]
        if let stored = envelope["fairmail_identities"]?.arrayValue {
            identities = try stored.map {
                guard let obj = $0.objectValue else { throw EmailCredentialError.invalidAccount }
                return obj
            }
        } else if let single = envelope["fairmail_identity"]?.objectValue {
            identities = [single]
        } else {
            throw EmailCredentialError.invalidAccount
        }
        guard (1...128).contains(identities.count) else {
            throw EmailCredentialError.invalidAccount
        }
        for identity in identities {
            try validatePair(account: account, identity: identity)
        }
        return Account(account: account, identities: identities)
    }

    /// True when `data` is an encrypted FairEmail `.efb` backup requiring a password.
    public static func needsPassword(_ data: Data) -> Bool {
        guard !data.isEmpty, data.count <= maxImportBytes else { return false }
        let bytes = [UInt8](data)
        if bytes.count >= 2 && bytes[0] == 0x1f && bytes[1] == 0x8b { return false }
        return !isPlainBackup(bytes)
    }

    /// Imports FairEmail plain JSON, gzipped JSON, or encrypted `.efb` (v0 & v1) backups.
    public static func importBackup(_ data: Data, password: String? = nil) throws -> [Account] {
        guard !data.isEmpty, data.count <= maxImportBytes else {
            throw EmailCredentialError.tooLarge
        }
        let input = [UInt8](data)
        var plain: [UInt8]
        if input.count >= 2 && input[0] == 0x1f && input[1] == 0x8b {
            plain = try gunzip(input)
        } else if isPlainBackup(input) {
            plain = input
        } else {
            let decrypted = try decryptFairEmailBackup(input, password: password)
            if decrypted.count >= 2 && decrypted[0] == 0x1f && decrypted[1] == 0x8b {
                plain = try gunzip(decrypted)
            } else {
                plain = decrypted
            }
        }
        defer {
            for i in 0..<plain.count { plain[i] = 0 }
        }
        guard let jsonText = String(bytes: plain, encoding: .utf8), !jsonText.contains("\u{0000}") else {
            throw EmailCredentialError.invalidBackup
        }
        try InputPolicy.validateJSONDepth(jsonText)
        guard let root = (try JSONValue.decode(jsonText)).objectValue else {
            throw EmailCredentialError.invalidBackup
        }
        let rows: [JSONValue]
        if let arr = root["accounts"]?.arrayValue {
            rows = arr
        } else {
            rows = [.object(root)]
        }
        guard !rows.isEmpty, rows.count <= InputPolicy.maxEntries else {
            throw EmailCredentialError.invalidBackup
        }
        var result: [Account] = []
        result.reserveCapacity(rows.count)
        for rowVal in rows {
            guard let account = rowVal.objectValue,
                  let rawIds = account["identities"]?.arrayValue,
                  (1...128).contains(rawIds.count) else {
                throw EmailCredentialError.invalidBackup
            }
            let identityList: [[String: JSONValue]] = try rawIds.map {
                guard let obj = $0.objectValue else { throw EmailCredentialError.invalidBackup }
                return obj
            }
            for identity in identityList {
                try validatePair(account: account, identity: identity)
            }
            let primaryIndex = identityList.firstIndex { $0["primary"]?.boolValue == true } ?? 0
            var ordered = [identityList[primaryIndex]]
            for (i, item) in identityList.enumerated() where i != primaryIndex {
                ordered.append(item)
            }
            result.append(Account(account: account, identities: ordered))
        }
        return result
    }

    /// Exports `accounts` as FairEmail's unencrypted JSON restore format.
    public static func exportBackup(_ accounts: [Account]) throws -> Data {
        guard !accounts.isEmpty, accounts.count <= InputPolicy.maxEntries else {
            throw EmailCredentialError.invalidBackup
        }
        var rows: [JSONValue] = []
        rows.reserveCapacity(accounts.count)
        for value in accounts {
            guard (1...128).contains(value.identities.count) else {
                throw EmailCredentialError.invalidAccount
            }
            for identity in value.identities {
                try validatePair(account: value.account, identity: identity)
            }
            var acct = value.account
            if (acct["name"]?.stringValue ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                acct["name"] = .string(value.address)
            }
            acct["folders"] = .array([])
            acct["identities"] = .array(value.identities.map { .object($0) })
            acct["contacts"] = .array([])
            rows.append(.object(acct))
        }
        let doc: JSONValue = .object([
            "version": .int(1),
            "accounts": .array(rows),
            "answers": .array([]),
            "searches": .array([]),
            "certificates": .array([]),
            "settings": .array([]),
            "channels": .array([]),
        ])
        let out = Data(doc.compact().utf8)
        guard out.count <= maxImportBytes else { throw EmailCredentialError.tooLarge }
        return out
    }

    // MARK: - Thunderbird CSV interchange

    public static func importThunderbirdCsv(_ data: Data) throws -> [Account] {
        guard !data.isEmpty, data.count <= maxImportBytes else {
            throw EmailCredentialError.tooLarge
        }
        let parsed = try ThunderbirdCsv.parse(data)
        guard !parsed.rows.isEmpty else { throw EmailCredentialError.invalidThunderbirdCsv }

        let lowered = parsed.columns.map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
        guard let hostIdx = lowered.firstIndex(where: { ["hostname", "host", "url", "login_uri", "origin"].contains($0) }),
              let userIdx = lowered.firstIndex(where: { ["username", "login_username", "user"].contains($0) }),
              let passIdx = lowered.firstIndex(where: { ["password", "login_password", "pass"].contains($0) }) else {
            throw EmailCredentialError.invalidThunderbirdCsv
        }

        struct RowInfo {
            let index: Int
            let hostname: String
            let username: String
            let password: String
            let parsed: ParsedThunderbirdHost
            let scheme: String
        }

        var rows: [RowInfo] = []
        rows.reserveCapacity(parsed.rows.count)
        for (idx, row) in parsed.rows.enumerated() {
            let hostname = row.value(at: hostIdx).trimmingCharacters(in: .whitespacesAndNewlines)
            let username = row.value(at: userIdx).trimmingCharacters(in: .whitespacesAndNewlines)
            let password = row.value(at: passIdx)
            guard !hostname.isEmpty, !username.isEmpty,
                  hostname.count <= maxCredentialChars,
                  username.count <= maxCredentialChars,
                  password.count <= maxCredentialChars else {
                throw EmailCredentialError.invalidThunderbirdCsv
            }
            let p = parseThunderbirdHostname(hostname)
            let scheme = !p.scheme.isEmpty ? p.scheme : (hostname.lowercased().contains("smtp") ? "smtp" : "imap")
            rows.append(RowInfo(index: idx, hostname: hostname, username: username, password: password, parsed: p, scheme: scheme))
        }
        guard rows.count <= InputPolicy.maxEntries else {
            throw EmailCredentialError.tooLarge
        }

        var groupOrder: [String] = []
        var grouped: [String: [RowInfo]] = [:]
        for row in rows {
            let key = row.username.lowercased()
            if grouped[key] == nil {
                groupOrder.append(key)
                grouped[key] = []
            }
            grouped[key]?.append(row)
        }

        var result: [Account] = []
        result.reserveCapacity(groupOrder.count)
        for key in groupOrder {
            guard let groupRows = grouped[key], !groupRows.isEmpty else { continue }
            let incomingCandidates = groupRows.filter {
                $0.scheme == "imap" || $0.scheme == "pop" || $0.scheme == "pop3" ||
                    $0.scheme == "mailbox" || $0.parsed.proto == 1
            }
            let incoming = incomingCandidates.first ?? groupRows[0]
            let outgoingRows = groupRows.filter {
                $0.index != incoming.index && ($0.scheme == "smtp" || $0.parsed.host != incoming.parsed.host || groupRows.count > 1)
            }
            let effectiveOutgoing = outgoingRows.isEmpty ? [incoming] : outgoingRows
            let address = incoming.username.contains("@") ? incoming.username : "\(incoming.username)@\(incoming.parsed.host)"
            let prefix = incoming.username.split(separator: "@", maxSplits: 1).first.map(String.init) ?? ""
            let name = prefix.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? address : prefix
            let firstOut = effectiveOutgoing[0]

            let baseAccount = try create(
                name: name,
                address: address,
                incomingHost: incoming.parsed.host,
                incomingPort: incoming.parsed.port,
                incomingEncryption: incoming.parsed.encryption,
                incomingProtocol: incoming.parsed.proto,
                incomingUser: incoming.username,
                incomingPassword: incoming.password,
                incomingAuth: 1,
                outgoingHost: firstOut.parsed.host,
                outgoingPort: firstOut.parsed.port,
                outgoingEncryption: firstOut.parsed.encryption,
                outgoingUser: firstOut.username,
                outgoingPassword: firstOut.password,
                outgoingAuth: 1
            )

            if effectiveOutgoing.count > 1 {
                var extraIdentities: [[String: JSONValue]] = []
                for extra in effectiveOutgoing.dropFirst() {
                    let idObj: [String: JSONValue] = [
                        "id": .int(Int64(baseAccount.identities.count + extraIdentities.count + 1)),
                        "uuid": .string(UUID().uuidString.lowercased()),
                        "name": .string(try cleanText(name)),
                        "email": .string(try cleanText(address)),
                        "display": .string(""),
                        "host": .string(try cleanText(extra.parsed.host)),
                        "encryption": .int(Int64(extra.parsed.encryption)),
                        "insecure": .bool(false),
                        "port": .int(Int64(extra.parsed.port)),
                        "auth_type": .int(1),
                        "user": .string(try cleanText(extra.username)),
                        "password": .string(extra.password),
                        "login": .bool(true),
                        "use_ip": .bool(true),
                        "synchronize": .bool(true),
                        "primary": .bool(false),
                        "self": .bool(true),
                        "sender_extra": .bool(false),
                        "sender_extra_name": .bool(false),
                        "reply_extra_name": .bool(false),
                        "unicode": .bool(false),
                        "octetmime": .bool(false),
                        "sign_default": .bool(false),
                        "encrypt_default": .bool(false),
                        "sensitivity": .int(0),
                    ]
                    try validatePair(account: baseAccount.account, identity: idObj)
                    extraIdentities.append(idObj)
                }
                result.append(Account(account: baseAccount.account, identities: baseAccount.identities + extraIdentities))
            } else {
                result.append(baseAccount)
            }
        }
        return result
    }

    public static func exportThunderbirdCsv(_ accounts: [Account]) throws -> Data {
        guard !accounts.isEmpty, accounts.count <= InputPolicy.maxEntries else {
            throw EmailCredentialError.invalidThunderbirdCsv
        }
        var rows: [[String]] = []
        rows.reserveCapacity(accounts.count * 2)
        for acct in accounts {
            let incomingHost = acct.account["host"]?.stringValue ?? ""
            let incomingPort = intField(acct.account, "port") ?? 993
            let incomingScheme = (intField(acct.account, "protocol") == 1) ? "pop" : "imap"
            let incomingUser = acct.account["user"]?.stringValue ?? ""
            let incomingPass = acct.account["password"]?.stringValue ?? ""
            let incomingUrl = "\(incomingScheme)://\(incomingHost):\(incomingPort)"
            rows.append([
                incomingUrl, incomingUser, incomingPass,
                "Incoming (\(incomingScheme.uppercased()))", "", "{\(UUID().uuidString.lowercased())}",
                "0", "0", "0",
            ])

            for identity in acct.identities {
                let outHost = identity["host"]?.stringValue ?? ""
                let outPort = intField(identity, "port") ?? 465
                let outUser = identity["user"]?.stringValue ?? ""
                let outPass = identity["password"]?.stringValue ?? ""
                let outUrl = "smtp://\(outHost):\(outPort)"
                rows.append([
                    outUrl, outUser, outPass,
                    "Outgoing (SMTP)", "", "{\(UUID().uuidString.lowercased())}",
                    "0", "0", "0",
                ])
            }
        }
        return ThunderbirdCsv.exportCsv(columns: ThunderbirdCsv.exportColumns, rows: rows)
    }

    // MARK: - Validation & Crypto Helpers

    private struct ParsedThunderbirdHost {
        let host: String
        let port: Int
        let encryption: Int
        let proto: Int
        let scheme: String
    }

    private static func parseThunderbirdHostname(_ raw: String) -> ParsedThunderbirdHost {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return ParsedThunderbirdHost(host: "localhost", port: 993, encryption: 0, proto: 0, scheme: "imap")
        }
        let scheme: String
        let remainder: String
        if let sep = trimmed.range(of: "://") {
            scheme = String(trimmed[..<sep.lowerBound]).lowercased()
            remainder = String(trimmed[sep.upperBound...])
        } else {
            let low = trimmed.lowercased()
            if low.contains("pop") { scheme = "pop" }
            else if low.contains("smtp") { scheme = "smtp" }
            else { scheme = "imap" }
            remainder = trimmed
        }
        if scheme == "mailbox" {
            let afterAt = remainder.components(separatedBy: "@").last?.trimmingCharacters(in: .whitespacesAndNewlines) ?? remainder
            let clean = afterAt.isEmpty ? "localhost" : String(afterAt.prefix(255))
            return ParsedThunderbirdHost(host: clean, port: 993, encryption: 0, proto: 0, scheme: "imap")
        }
        var hostPort = remainder.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? remainder
        hostPort = hostPort.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? hostPort
        if let atIdx = hostPort.lastIndex(of: "@") {
            hostPort = String(hostPort[hostPort.index(after: atIdx)...])
        }
        hostPort = hostPort.trimmingCharacters(in: .whitespacesAndNewlines)
        var host = hostPort
        var portStr: String? = nil
        if let colon = hostPort.firstIndex(of: ":") {
            host = String(hostPort[..<colon]).trimmingCharacters(in: .whitespacesAndNewlines)
            portStr = String(hostPort[hostPort.index(after: colon)...]).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if host.isEmpty { host = "mail.example.com" }
        let defaultPort: Int
        switch scheme {
        case "pop", "pop3": defaultPort = 995
        case "smtp": defaultPort = 465
        default: defaultPort = 993
        }
        let port = portStr.flatMap(Int.init) ?? defaultPort
        let encryption = [143, 110, 587, 25].contains(port) ? 1 : 0
        let proto = (scheme.contains("pop") || port == 110 || port == 995) ? 1 : 0
        return ParsedThunderbirdHost(host: host, port: port, encryption: encryption, proto: proto, scheme: scheme)
    }

    private static func validatePair(account: [String: JSONValue], identity: [String: JSONValue]) throws {
        let proto: Int
        if let p = intField(account, "protocol") {
            proto = p
        } else if let pop = account["pop"]?.boolValue {
            proto = pop ? 1 : 0
        } else {
            proto = -1
        }
        guard (0...1).contains(proto) else { throw EmailCredentialError.invalidAccount }
        try validateConnection(account)
        try validateConnection(identity)
        let email = identity["email"]?.stringValue ?? ""
        let name = account["name"]?.stringValue ?? ""
        guard email.contains("@"),
              !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
              !email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw EmailCredentialError.invalidAccount
        }
    }

    private static func validateConnection(_ json: [String: JSONValue]) throws {
        let host = json["host"]?.stringValue ?? ""
        guard !host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw EmailCredentialError.invalidAccount
        }
        let port = intField(json, "port") ?? 0
        guard (1...65_535).contains(port) else {
            throw EmailCredentialError.invalidAccount
        }
        let encMode: Int
        if let e = intField(json, "encryption") {
            encMode = e
        } else if let starttls = json["starttls"]?.boolValue {
            encMode = starttls ? 1 : 0
        } else {
            encMode = -1
        }
        guard (0...2).contains(encMode) else { throw EmailCredentialError.invalidAccount }
        guard (intField(json, "auth_type") ?? -1) >= 0 else {
            throw EmailCredentialError.invalidAccount
        }
        guard let user = json["user"]?.stringValue,
              let password = json["password"]?.stringValue,
              user.count <= maxCredentialChars,
              password.count <= maxCredentialChars else {
            throw EmailCredentialError.invalidAccount
        }
    }

    private static func intField(_ json: [String: JSONValue], _ key: String) -> Int? {
        json[key]?.intValue.map(Int.init)
    }

    private static func cleanText(_ raw: String) throws -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        try InputPolicy.validateText(trimmed, limit: InputPolicy.maxTextChars)
        return trimmed
    }

    private static func isPlainBackup(_ bytes: [UInt8]) -> Bool {
        guard let text = String(bytes: bytes, encoding: .utf8), !text.contains("\u{0000}") else {
            return false
        }
        guard (try? InputPolicy.validateJSONDepth(text)) != nil,
              let root = (try? JSONValue.decode(text))?.objectValue else {
            return false
        }
        return root["accounts"] != nil || root["identities"] != nil
    }

    private static func decryptFairEmailBackup(_ input: [UInt8], password: String?) throws -> [UInt8] {
        guard let password, !password.isEmpty else {
            throw EmailCredentialError.passwordRequired
        }
        var offset = 0
        var version = 0
        let magicBytes = Array("___FairEmail___".utf8)
        if input.count >= 32 && Array(input[0..<15]) == magicBytes {
            version = Int(input[15])
            guard version == 1 else { throw EmailCredentialError.invalidBackup }
            offset = 16
        }
        let ivLen = (version == 0) ? 16 : 12
        guard input.count >= offset + 16 + ivLen + 16 else {
            throw EmailCredentialError.invalidBackup
        }
        let salt = Array(input[offset..<(offset + 16)])
        let iv = Array(input[(offset + 16)..<(offset + 16 + ivLen)])
        let ciphertext = Array(input[(offset + 16 + ivLen)..<input.count])

        var key = try deriveFairEmailKey(
            password: password,
            salt: salt,
            iterations: version == 0 ? 65_536 : 120_000,
            useSha512: version == 1
        )
        defer {
            for i in 0..<key.count { key[i] = 0 }
        }

        if version == 0 {
            return try aesCbcDecrypt(ciphertext: ciphertext, key: key, iv: iv)
        } else {
            guard ciphertext.count >= 16 else { throw EmailCredentialError.decryptionFailed }
            let nonce = try AES.GCM.Nonce(data: Data(iv))
            let body = Data(ciphertext[0..<(ciphertext.count - 16)])
            let tag = Data(ciphertext[(ciphertext.count - 16)..<ciphertext.count])
            let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: body, tag: tag)
            do {
                let opened = try AES.GCM.open(box, using: SymmetricKey(data: Data(key)))
                guard opened.count <= maxImportBytes else { throw EmailCredentialError.tooLarge }
                return [UInt8](opened)
            } catch let err as EmailCredentialError {
                throw err
            } catch {
                throw EmailCredentialError.decryptionFailed
            }
        }
    }

    private static func deriveFairEmailKey(
        password: String,
        salt: [UInt8],
        iterations: UInt32,
        useSha512: Bool
    ) throws -> [UInt8] {
        var derived = [UInt8](repeating: 0, count: 32)
        let utf8 = Array(password.utf8)
        let prf = useSha512 ? CCPBKDFAlgorithm(kCCPRFHmacAlgSHA512) : CCPBKDFAlgorithm(kCCPRFHmacAlgSHA1)
        let status = derived.withUnsafeMutableBytes { outBuf in
            salt.withUnsafeBytes { saltBuf in
                utf8.withUnsafeBufferPointer { passBuf in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        passBuf.baseAddress?.withMemoryRebound(to: Int8.self, capacity: utf8.count) { $0 },
                        utf8.count,
                        saltBuf.bindMemory(to: UInt8.self).baseAddress,
                        salt.count,
                        prf,
                        iterations,
                        outBuf.bindMemory(to: UInt8.self).baseAddress,
                        32
                    )
                }
            }
        }
        guard status == kCCSuccess else { throw EmailCredentialError.decryptionFailed }
        return derived
    }

    private static func aesCbcDecrypt(ciphertext: [UInt8], key: [UInt8], iv: [UInt8]) throws -> [UInt8] {
        var out = [UInt8](repeating: 0, count: ciphertext.count + kCCBlockSizeAES128)
        var outMoved: Int = 0
        let status = out.withUnsafeMutableBytes { outBuf in
            ciphertext.withUnsafeBytes { inBuf in
                key.withUnsafeBytes { keyBuf in
                    iv.withUnsafeBytes { ivBuf in
                        CCCrypt(
                            CCOperation(kCCDecrypt),
                            CCAlgorithm(kCCAlgorithmAES),
                            CCOptions(kCCOptionPKCS7Padding),
                            keyBuf.baseAddress,
                            key.count,
                            ivBuf.baseAddress,
                            inBuf.baseAddress,
                            ciphertext.count,
                            outBuf.baseAddress,
                            out.count,
                            &outMoved
                        )
                    }
                }
            }
        }
        guard status == kCCSuccess, outMoved <= maxImportBytes else {
            throw EmailCredentialError.decryptionFailed
        }
        return Array(out.prefix(outMoved))
    }

    private static func gunzip(_ input: [UInt8]) throws -> [UInt8] {
        var stream = z_stream()
        let initStatus = inflateInit2_(&stream, MAX_WBITS + 16, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size))
        guard initStatus == Z_OK else { throw EmailCredentialError.invalidBackup }
        defer { inflateEnd(&stream) }

        var output: [UInt8] = []
        var chunk = [UInt8](repeating: 0, count: 16 * 1024)
        return try input.withUnsafeBufferPointer { inPtr in
            stream.next_in = UnsafeMutablePointer<Bytef>(mutating: inPtr.baseAddress)
            stream.avail_in = uInt(inPtr.count)

            while true {
                let status = chunk.withUnsafeMutableBufferPointer { outPtr -> Int32 in
                    stream.next_out = outPtr.baseAddress
                    stream.avail_out = uInt(outPtr.count)
                    return inflate(&stream, Z_NO_FLUSH)
                }
                let produced = chunk.count - Int(stream.avail_out)
                if produced > 0 {
                    guard output.count + produced <= maxImportBytes else {
                        throw EmailCredentialError.tooLarge
                    }
                    output.append(contentsOf: chunk[0..<produced])
                }
                if status == Z_STREAM_END {
                    break
                }
                guard status == Z_OK else {
                    throw EmailCredentialError.invalidBackup
                }
                if stream.avail_in == 0 && produced == 0 {
                    throw EmailCredentialError.invalidBackup
                }
            }
            return output
        }
    }
}

/// Thunderbird CSV parser & RFC 4180 writer matching `ThunderbirdCsv.kt` in `:core`.
public enum ThunderbirdCsv {
    public static let exportColumns = [
        "url", "username", "password", "httpRealm", "formActionOrigin",
        "guid", "timeCreated", "timeLastUsed", "timePasswordChanged",
    ]

    public struct Table: Equatable {
        public let columns: [String]
        public let rows: [BrowserPasswordCsv.Row]
    }

    public static func parse(_ data: Data) throws -> Table {
        guard !data.isEmpty, data.count <= EmailMaterial.maxImportBytes,
              let rawText = String(data: data, encoding: .utf8),
              !rawText.contains("\u{0000}") else {
            throw EmailCredentialError.invalidThunderbirdCsv
        }
        let text = rawText.hasPrefix("\u{FEFF}") ? String(rawText.dropFirst()) : rawText
        let records = try BrowserPasswordCsv.parseRfc4180(
            text,
            maxColumns: PasswordMaterial.maxColumns,
            maxRows: InputPolicy.maxEntries + 1
        )
        guard records.count >= 2 else { throw EmailCredentialError.invalidThunderbirdCsv }
        let header = records[0]
        var rows: [BrowserPasswordCsv.Row] = []
        rows.reserveCapacity(records.count - 1)
        for rec in records.dropFirst() {
            if rec.count == 1 && rec[0].trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                continue
            }
            rows.append(BrowserPasswordCsv.Row(values: rec))
        }
        guard !rows.isEmpty else { throw EmailCredentialError.invalidThunderbirdCsv }
        return Table(columns: header, rows: rows)
    }

    public static func exportCsv(columns: [String], rows: [[String]]) -> Data {
        var lines: [String] = []
        lines.reserveCapacity(rows.count + 1)
        lines.append(columns.map(escapeCsv).joined(separator: ","))
        for row in rows {
            lines.append(row.map(escapeCsv).joined(separator: ","))
        }
        return Data((lines.joined(separator: "\r\n") + "\r\n").utf8)
    }

    private static func escapeCsv(_ value: String) -> String {
        if value.contains(",") || value.contains("\"") || value.contains("\r") || value.contains("\n") {
            let escaped = value.replacingOccurrences(of: "\"", with: "\"\"")
            return "\"\(escaped)\""
        }
        return value
    }
}

/// High-level Thunderbird vault entry import & export matching `ThunderbirdImport.kt` in `:core`
/// (including legacy `PASSWORD` entries with mail schemes!).
public enum ThunderbirdImport {
    public static func entries(from data: Data) throws -> [Entry] {
        let accounts = try EmailMaterial.importThunderbirdCsv(data)
        return try accounts.map { try Entry.email(account: $0) }
    }

    public static func exportCsv(entries: [Entry]) throws -> Data {
        var accounts: [EmailMaterial.Account] = []
        for entry in entries {
            if entry.kind == .email, let bytes = entry.secretBytes {
                accounts.append(try EmailMaterial.decode(bytes))
            } else if entry.kind == .password, let fields = try? entry.passwordFields() {
                let rawUrl = fields.value("url").isEmpty ? fields.value("hostname") : fields.value("url")
                let low = rawUrl.lowercased()
                if low.hasPrefix("imap://") || low.hasPrefix("pop://") || low.hasPrefix("pop3://") ||
                    low.hasPrefix("smtp://") || low.hasPrefix("mailbox://") {
                    let singleCsv = ThunderbirdCsv.exportCsv(
                        columns: ["url", "username", "password"],
                        rows: [[rawUrl, fields.value("username"), fields.value("password")]]
                    )
                    accounts.append(contentsOf: try EmailMaterial.importThunderbirdCsv(singleCsv))
                }
            }
        }
        guard !accounts.isEmpty else {
            throw EmailCredentialError.noExportableAccounts
        }
        return try EmailMaterial.exportThunderbirdCsv(accounts)
    }
}

public enum EmailCredentialError: Error, Equatable {
    case invalidAccount
    case invalidBackup
    case passwordRequired
    case decryptionFailed
    case invalidThunderbirdCsv
    case noExportableAccounts
    case tooLarge
}
