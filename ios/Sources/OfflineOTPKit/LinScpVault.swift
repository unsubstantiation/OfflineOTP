import Foundation
import CryptoKit

/// Bidirectional credential interchange with LinSCP (`LSCPVLT2` encrypted vault, portable
/// `BackupBundle` JSON with `lscp1$` password decryption, and `sessions.ini`).
///
/// Mirrors `core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/LinScpCredentials.kt`.
public enum LinScpVault {
    public static let magicText = "LSCPVLT2"
    public static let magic: [UInt8] = Array(magicText.utf8)

    public static let argon2MemoryKiB = 65_536
    public static let argon2Iterations = 3
    public static let argon2Parallelism = 1
    public static let saltBytes = 16
    public static let nonceBytes = 12
    public static let tagBytes = 16
    public static let minVaultBytes = 8 + saltBytes + nonceBytes + tagBytes
    public static let maxFileBytes = InputPolicy.maxDocumentBytes

    public static let sessionJsonColumn = "linscp_session_json"
    public static let sshKeyLabelColumn = "linscp_ssh_key_label"
    public static let tunnelKeyLabelColumn = "linscp_tunnel_key_label"
    public static let lscp1Prefix = "lscp1$"
    public static let masterKeyBytes = 32

    private static let pwdMagic: UInt32 = 0x5057_4431 // "PWD1"
    private static let sshMagic: UInt32 = 0x5353_4831 // "SSH1"
    private static let lscp1NonceBytes = 16
    private static let lscp1TagBytes = 32
    private static let lscp1MaxPasswordBytes = 4096

    private static let lscp1TagLabel = Data("lscp1".utf8)
    private static let lscp1EncKeyLabel = Data("LinSCP sessions file v1 encryption key".utf8)
    private static let lscp1MacKeyLabel = Data("LinSCP sessions file v1 authentication key".utf8)

    /// True when `data` starts with the 8-byte `"LSCPVLT2"` header.
    public static func isEncryptedVault(_ data: Data) -> Bool {
        guard data.count >= magic.count else { return false }
        return Array(data.prefix(magic.count)) == magic
    }

    /// True when `data` is a `sessions.ini` (or `BackupBundle` JSON missing `sessions_key_b64`)
    /// containing `lscp1$` encrypted passwords.
    public static func needsSessionMasterKey(_ data: Data) -> Bool {
        guard !data.isEmpty, data.count <= maxFileBytes, !isEncryptedVault(data),
              let rawText = String(data: data, encoding: .utf8) else {
            return false
        }
        let trimmed = stripBOM(rawText).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        if trimmed.hasPrefix("{") {
            guard (try? InputPolicy.validateJSONDepth(trimmed)) != nil,
                  let obj = (try? JSONValue.decode(trimmed))?.objectValue else {
                return false
            }
            let ini = obj["sessions_ini"]?.stringValue ?? ""
            let keyB64 = obj["sessions_key_b64"]?.stringValue ?? ""
            return keyB64.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                iniContainsLscp1Secret(ini)
        }
        return looksLikeSessionsIni(trimmed) && iniContainsLscp1Secret(trimmed)
    }

    /// Opens an `LSCPVLT2` vault and encodes the imported entries as a `VaultDocument` string.
    public static func openToDocument(password: String, data: Data) throws -> String {
        let entries = try open(password: password, data: data)
        return try VaultDocument.encode(entries)
    }

    /// Decrypts and validates a LinSCP `LSCPVLT2` vault, enriching linked `SSH_KEY` entries with
    /// their corresponding session or jump-host connection metadata.
    public static func open(password: String, data: Data) throws -> [Entry] {
        guard password.count <= InputPolicy.maxVaultPasswordChars, !password.isEmpty else {
            throw EncryptedBackupError.invalidPassword
        }
        guard data.count >= minVaultBytes, data.count <= maxFileBytes, isEncryptedVault(data) else {
            throw LinScpVaultError.invalidFormat
        }
        let bytes = [UInt8](data)
        let salt = Array(bytes[magic.count..<(magic.count + saltBytes)])
        let sealed = Data(bytes[(magic.count + saltBytes)..<bytes.count])

        var key = try VaultCrypto.argon2id(
            password: password,
            salt: salt,
            memoryKiB: argon2MemoryKiB,
            iterations: argon2Iterations,
            parallelism: argon2Parallelism
        )
        defer {
            for i in 0..<key.count { key[i] = 0 }
        }
        let plain = try VaultCrypto.open(sealed, key: key, authenticating: Data(magic))
        guard let jsonText = String(data: plain, encoding: .utf8) else {
            throw LinScpVaultError.invalidUTF8
        }
        try InputPolicy.validateJSONDepth(jsonText)
        guard let elements = (try JSONValue.decode(jsonText)).arrayValue,
              (1...InputPolicy.maxEntries).contains(elements.count) else {
            throw LinScpVaultError.invalidFormat
        }
        return try parseVaultElements(elements)
    }

    /// Imports entries from any supported LinSCP file (`LSCPVLT2` vault, `BackupBundle` JSON, or `sessions.ini`).
    public static func importData(
        _ data: Data,
        password: String? = nil,
        masterKeyData: Data? = nil
    ) throws -> [Entry] {
        guard !data.isEmpty, data.count <= maxFileBytes else {
            throw LinScpVaultError.invalidFormat
        }
        if isEncryptedVault(data) {
            guard let password else { throw EncryptedBackupError.invalidPassword }
            return try open(password: password, data: data)
        }
        guard let rawText = String(data: data, encoding: .utf8) else {
            throw LinScpVaultError.invalidUTF8
        }
        let trimmed = stripBOM(rawText).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw LinScpVaultError.invalidFormat }
        let callerKey = try masterKeyData.map(parseMasterKey)
        if trimmed.hasPrefix("{") {
            return try importBackupBundle(trimmed, callerKey: callerKey)
        }
        guard looksLikeSessionsIni(trimmed) else { throw LinScpVaultError.invalidFormat }
        return try importSessionsIni(trimmed, masterKey: callerKey)
    }

    /// Encrypts `SSH_KEY` and `PASSWORD` entries into a LinSCP `LSCPVLT2` vault.
    public static func sealWithFreshParameters(entries: [Entry], password: String) throws -> Data {
        try seal(
            entries: entries,
            password: password,
            salt: VaultCrypto.randomBytes(saltBytes),
            nonce: VaultCrypto.randomBytes(nonceBytes)
        )
    }

    /// Deterministic `LSCPVLT2` sealer for reproducible vector verification.
    public static func seal(
        entries: [Entry],
        password: String,
        salt: [UInt8],
        nonce: [UInt8]
    ) throws -> Data {
        guard password.count <= InputPolicy.maxVaultPasswordChars, !password.isEmpty else {
            throw EncryptedBackupError.invalidPassword
        }
        guard salt.count == saltBytes, nonce.count == nonceBytes else {
            throw LinScpVaultError.invalidFormat
        }
        let credentials = entries.filter { $0.kind == .sshKey || $0.kind == .password }
        guard !credentials.isEmpty, credentials.count <= InputPolicy.maxEntries else {
            throw LinScpVaultError.emptyCredentials
        }

        var linkedLabels = Set<String>()
        for entry in credentials where entry.kind == .password {
            if let bytes = entry.secretBytes, let pairs = try? decodePasswordMaterial(bytes) {
                for (col, val) in pairs {
                    let lower = col.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                    if lower == sshKeyLabelColumn || lower == tunnelKeyLabelColumn {
                        let trimmed = val.trimmingCharacters(in: .whitespacesAndNewlines)
                        if !trimmed.isEmpty { linkedLabels.insert(trimmed) }
                    }
                }
            }
        }

        var usedKeyLabels = Set<String>()
        var exportedEntries: [Entry] = []
        exportedEntries.reserveCapacity(credentials.count)
        for var entry in credentials {
            if entry.kind == .sshKey {
                let rawLabel = entry.label.trimmingCharacters(in: .whitespacesAndNewlines)
                let fallback = entry.issuer.trimmingCharacters(in: .whitespacesAndNewlines)
                let candidate = rawLabel.isEmpty ? (fallback.isEmpty ? "SSH key" : fallback) : rawLabel
                let baseLabel = linkedLabels.contains(candidate)
                    ? candidate
                    : sanitizeLinScpName(candidate)
                let unique = deduplicateLabel(baseLabel, seen: &usedKeyLabels)
                entry.set("label", .string(unique))
                exportedEntries.append(entry)
            } else if entry.kind == .password {
                guard let rawBytes = entry.secretBytes else { throw LinScpVaultError.invalidFormat }
                let pairs = try decodePasswordMaterial(rawBytes)
                var byLower: [String: String] = [:]
                for (name, val) in pairs {
                    byLower[name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()] = val
                }
                var exportPairs: [(String, String)] = [
                    ("url", byLower["url"] ?? ""),
                    ("username", byLower["username"] ?? ""),
                    ("password", byLower["password"] ?? ""),
                ]
                for key in byLower.keys.filter({ $0.hasPrefix("linscp_") }).sorted() {
                    if let val = byLower[key] { exportPairs.append((key, val)) }
                }
                let reencoded = try encodePasswordMaterial(exportPairs)
                entry.set("secret", .string(Base32.encode(reencoded)))
                let cleanLabel = sanitizeLinScpName(entry.label.isEmpty ? entry.issuer : entry.label)
                entry.set("label", .string(cleanLabel))
                exportedEntries.append(entry)
            }
        }

        let jsonText = try VaultDocument.encode(exportedEntries)
        let plainData = Data(jsonText.utf8)
        guard plainData.count <= maxFileBytes else { throw LinScpVaultError.invalidFormat }

        var key = try VaultCrypto.argon2id(
            password: password,
            salt: salt,
            memoryKiB: argon2MemoryKiB,
            iterations: argon2Iterations,
            parallelism: argon2Parallelism
        )
        defer {
            for i in 0..<key.count { key[i] = 0 }
        }
        let sealed = try VaultCrypto.seal(plainData, key: key, nonce: nonce,
                                          authenticating: Data(magic))
        var out = Data()
        out.reserveCapacity(magic.count + salt.count + sealed.count)
        out.append(contentsOf: magic)
        out.append(contentsOf: salt)
        out.append(sealed)
        return out
    }

    /// UTC dated filename matching `BackupFileNames.encrypted`.
    public static func exportFilename(_ date: Date = Date()) -> String {
        BackupFileNames.encrypted(date)
    }

    /// Decrypts a LinSCP `lscp1$` saved-session password token using the 32-byte `sessions.key`.
    public static func decryptLscp1Password(_ encoded: String, masterKey: [UInt8]) throws -> String {
        guard masterKey.count == masterKeyBytes, encoded.hasPrefix(lscp1Prefix) else {
            throw LinScpVaultError.invalidFormat
        }
        let b64 = String(encoded.dropFirst(lscp1Prefix.count))
        guard let blob = Data(base64Encoded: b64) else { throw LinScpVaultError.invalidFormat }
        let bytes = [UInt8](blob)
        guard bytes.count >= lscp1NonceBytes + lscp1TagBytes,
              bytes.count - lscp1NonceBytes - lscp1TagBytes <= lscp1MaxPasswordBytes else {
            throw LinScpVaultError.invalidFormat
        }
        let nonce = Data(bytes[0..<lscp1NonceBytes])
        let cipher = Data(bytes[lscp1NonceBytes..<(bytes.count - lscp1TagBytes)])
        let tag = Array(bytes[(bytes.count - lscp1TagBytes)..<bytes.count])

        let masterSym = SymmetricKey(data: Data(masterKey))
        let encKeyBytes = Array(HMAC<SHA256>.authenticationCode(for: lscp1EncKeyLabel, using: masterSym))
        let macKeyBytes = Array(HMAC<SHA256>.authenticationCode(for: lscp1MacKeyLabel, using: masterSym))
        let macSym = SymmetricKey(data: Data(macKeyBytes))
        var mac = HMAC<SHA256>(key: macSym)
        mac.update(data: lscp1TagLabel)
        mac.update(data: nonce)
        mac.update(data: cipher)
        let expectedTag = Array(mac.finalize())
        guard VaultCrypto.constantTimeEquals(expectedTag, tag) else {
            throw VaultCryptoError.authenticationFailed
        }

        let encSym = SymmetricKey(data: Data(encKeyBytes))
        let cipherBytes = [UInt8](cipher)
        var plainBytes = [UInt8](repeating: 0, count: cipherBytes.count)
        var offset = 0
        var counter: UInt32 = 0
        while offset < cipherBytes.count {
            var blockHmac = HMAC<SHA256>(key: encSym)
            blockHmac.update(data: nonce)
            var be = counter.bigEndian
            withUnsafeBytes(of: &be) { blockHmac.update(data: Data($0)) }
            let keystream = Array(blockHmac.finalize())
            let take = min(cipherBytes.count - offset, keystream.count)
            for i in 0..<take {
                plainBytes[offset + i] = cipherBytes[offset + i] ^ keystream[i]
            }
            offset += take
            counter &+= 1
        }
        guard let decoded = String(bytes: plainBytes, encoding: .utf8) else {
            throw LinScpVaultError.invalidUTF8
        }
        return decoded
    }

    public static func parseMasterKey(_ data: Data) throws -> [UInt8] {
        if data.count == masterKeyBytes { return [UInt8](data) }
        guard let ascii = String(data: data, encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines) else {
            throw LinScpVaultError.invalidMasterKey
        }
        if ascii.utf8.count == masterKeyBytes, Data(base64Encoded: ascii) == nil {
            return Array(ascii.utf8)
        }
        guard let decoded = Data(base64Encoded: ascii), decoded.count == masterKeyBytes else {
            throw LinScpVaultError.invalidMasterKey
        }
        return [UInt8](decoded)
    }

    // MARK: - Internal parsing & encoding

    private struct KeyLink {
        let host: String
        let port: Int
        let username: String
        let password: String
    }

    private static func parseVaultElements(_ elements: [JSONValue]) throws -> [Entry] {
        var sshIndices: [Int] = []
        var loginEntries: [(Int, Entry)] = []
        var keyLinks: [String: KeyLink] = [:]
        var parsedByIndex: [Int: Entry] = [:]

        for (idx, element) in elements.enumerated() {
            guard let obj = element.objectValue else { throw LinScpVaultError.invalidFormat }
            let kind = obj["type"]?.stringValue ?? ""
            if kind == "SSH_KEY" {
                sshIndices.append(idx)
            } else if kind == "PASSWORD" {
                var entry = try Entry.parse(element)
                guard let raw = entry.secretBytes else { throw LinScpVaultError.invalidFormat }
                let pairs = try decodePasswordMaterial(raw)
                var byLower: [String: String] = [:]
                for (name, val) in pairs {
                    let norm = name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                    guard byLower.updateValue(val, forKey: norm) == nil else {
                        throw LinScpVaultError.invalidFormat
                    }
                }
                guard let url = byLower["url"], let username = byLower["username"],
                      let password = byLower["password"] else {
                    throw LinScpVaultError.invalidFormat
                }
                var host = ""
                var port = 0
                var user = username
                var tHost = ""
                var tPort = 0
                var tUser = ""
                var tPass = ""
                if let sessionJson = byLower[sessionJsonColumn], !sessionJson.isEmpty {
                    try InputPolicy.validateJSONDepth(sessionJson)
                    guard let siteObj = (try JSONValue.decode(sessionJson)).objectValue else {
                        throw LinScpVaultError.invalidFormat
                    }
                    host = siteObj["host"]?.stringValue ?? ""
                    port = siteObj["port"]?.intValue.map(Int.init) ?? 0
                    if user.isEmpty { user = siteObj["username"]?.stringValue ?? "" }
                    if let tunnel = siteObj["tunnel"]?.objectValue {
                        tHost = tunnel["host"]?.stringValue ?? ""
                        tPort = tunnel["port"]?.intValue.map(Int.init) ?? 0
                        tUser = tunnel["username"]?.stringValue ?? ""
                        tPass = tunnel["password"]?.stringValue ?? ""
                    }
                }
                if let mainLabel = byLower[sshKeyLabelColumn], !mainLabel.isEmpty,
                   keyLinks[mainLabel] == nil {
                    keyLinks[mainLabel] = KeyLink(host: host, port: port, username: user, password: password)
                }
                if let tunLabel = byLower[tunnelKeyLabelColumn], !tunLabel.isEmpty,
                   keyLinks[tunLabel] == nil {
                    keyLinks[tunLabel] = KeyLink(host: tHost, port: tPort, username: tUser, password: tPass)
                }
                if entry.issuer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    let derived = !host.isEmpty ? host : hostFromUrl(url)
                    if !derived.isEmpty { entry.set("issuer", .string(derived)) }
                }
                loginEntries.append((idx, entry))
                parsedByIndex[idx] = entry
            }
        }

        for idx in sshIndices {
            var entry = try Entry.parse(elements[idx])
            guard let raw = entry.secretBytes else { throw LinScpVaultError.invalidFormat }
            _ = try decodeSshKeyMaterial(raw)
            if let link = keyLinks[entry.label] {
                if (entry.sshHost ?? "").isEmpty, !link.host.isEmpty,
                   !link.host.contains(where: { $0.isWhitespace }) {
                    entry.set("ssh_host", .string(link.host))
                }
                if entry.sshPort == nil, (1...65_535).contains(link.port) {
                    entry.set("ssh_port", .int(Int64(link.port)))
                }
                if (entry.sshUsername ?? "").isEmpty, !link.username.isEmpty,
                   !link.username.contains(where: { $0.isWhitespace }) {
                    entry.set("ssh_username", .string(link.username))
                }
                if (entry.sshPassword ?? "").isEmpty,
                   !link.password.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    entry.set("ssh_password", .string(link.password))
                }
                if entry.issuer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                   let h = entry.sshHost, !h.isEmpty {
                    entry.set("issuer", .string(h))
                }
            }
            parsedByIndex[idx] = entry
        }

        let ordered = elements.indices.compactMap { parsedByIndex[$0] }
        guard !ordered.isEmpty else { throw LinScpVaultError.emptyCredentials }
        return ordered
    }

    private static func importBackupBundle(_ jsonText: String, callerKey: [UInt8]?) throws -> [Entry] {
        try InputPolicy.validateJSONDepth(jsonText)
        guard let bundle = (try JSONValue.decode(jsonText)).objectValue else {
            throw LinScpVaultError.invalidFormat
        }
        if let ver = bundle["version"]?.intValue, ver != 1 {
            throw LinScpVaultError.invalidFormat
        }
        guard let ini = bundle["sessions_ini"]?.stringValue,
              !ini.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw LinScpVaultError.invalidFormat
        }
        let key: [UInt8]?
        if let b64 = bundle["sessions_key_b64"]?.stringValue?
            .trimmingCharacters(in: .whitespacesAndNewlines), !b64.isEmpty {
            key = try parseMasterKey(Data(b64.utf8))
        } else {
            key = callerKey
        }
        return try importSessionsIni(ini, masterKey: key)
    }

    private static func importSessionsIni(_ iniText: String, masterKey: [UInt8]?) throws -> [Entry] {
        struct IniSite {
            var name: String
            var fields: [String: String]
        }
        var sections: [IniSite] = []
        var sectionIndexByName: [String: Int] = [:]
        var currentName: String?
        var currentFields: [String: String] = [:]

        func flush() {
            guard let rawName = currentName?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !rawName.isEmpty else {
                currentFields.removeAll()
                return
            }
            let site = IniSite(name: rawName, fields: currentFields)
            currentFields.removeAll()
            if let existing = sectionIndexByName[rawName] {
                sections[existing] = site
            } else {
                sectionIndexByName[rawName] = sections.count
                sections.append(site)
            }
        }

        for rawLine in iniText.components(separatedBy: "\n") {
            let line = rawLine.hasSuffix("\r") ? String(rawLine.dropLast()) : rawLine
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty || trimmed.hasPrefix("#") || trimmed.hasPrefix(";") { continue }
            if trimmed.hasPrefix("[") && trimmed.hasSuffix("]") && trimmed.count > 2 {
                flush()
                let inner = String(trimmed.dropFirst().dropLast())
                currentName = unescapeIni(inner)
                continue
            }
            guard currentName != nil, let eq = line.firstIndex(of: "=") else {
                throw LinScpVaultError.invalidFormat
            }
            let key = String(line[..<eq]).trimmingCharacters(in: .whitespacesAndNewlines)
            let value = unescapeIni(String(line[line.index(after: eq)...]))
            if key.isEmpty { throw LinScpVaultError.invalidFormat }
            currentFields[key] = value
        }
        flush()
        guard !sections.isEmpty, sections.count <= InputPolicy.maxEntries else {
            throw LinScpVaultError.emptyCredentials
        }

        var out: [Entry] = []
        out.reserveCapacity(sections.count)
        for site in sections {
            var map = site.fields
            let proto = normalizeProtocol(map.removeValue(forKey: "protocol"))
            let host = map.removeValue(forKey: "host") ?? ""
            let port = map.removeValue(forKey: "port")
                .flatMap { Int($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
                .flatMap { (1...65_535).contains($0) ? $0 : nil }
                ?? defaultPort(for: proto)
            let username = map.removeValue(forKey: "username") ?? ""
            let password = try decodeIniSecret(map.removeValue(forKey: "password"), masterKey: masterKey)
            _ = map.removeValue(forKey: "private_key")
            let hostkey = map.removeValue(forKey: "hostkey")
            let remoteDir = map.removeValue(forKey: "remote_directory") ?? ""
            _ = map.removeValue(forKey: "local_directory")
            let preset = map.removeValue(forKey: "preset") ?? ""
            let lastUsed = map.removeValue(forKey: "last_used")
                .flatMap { Int64($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
            let proxyMethod = map.removeValue(forKey: "proxy_method") ?? ""
            let proxyHost = map.removeValue(forKey: "proxy_host") ?? ""
            let proxyPort = map.removeValue(forKey: "proxy_port")
                .flatMap { Int($0.trimmingCharacters(in: .whitespacesAndNewlines)) } ?? 0
            let proxyUser = map.removeValue(forKey: "proxy_username") ?? ""
            let proxyPass = try decodeIniSecret(map.removeValue(forKey: "proxy_password"), masterKey: masterKey)
            let proxyCmd = map.removeValue(forKey: "proxy_command") ?? ""
            let proxyExcl = map.removeValue(forKey: "proxy_exclude") ?? ""
            let proxyDns = map.removeValue(forKey: "proxy_dns") ?? ""
            let proxyLocal = parseToggle(map.removeValue(forKey: "proxy_localhost")) ?? false
            let tunnelEnabled = parseToggle(map.removeValue(forKey: "tunnel")) ?? false
            let tunnelHost = map.removeValue(forKey: "tunnel_host") ?? ""
            let tunnelPort = map.removeValue(forKey: "tunnel_port")
                .flatMap { Int($0.trimmingCharacters(in: .whitespacesAndNewlines)) } ?? 22
            let tunnelUser = map.removeValue(forKey: "tunnel_username") ?? ""
            let tunnelPass = try decodeIniSecret(map.removeValue(forKey: "tunnel_password"), masterKey: masterKey)
            _ = map.removeValue(forKey: "tunnel_private_key")
            let tunnelPassphrase = try decodeIniSecret(map.removeValue(forKey: "tunnel_passphrase"), masterKey: masterKey)
            let tunnelHostkey = map.removeValue(forKey: "tunnel_hostkey") ?? ""
            let tunnelLocalPort = map.removeValue(forKey: "tunnel_local_port")
                .flatMap { Int($0.trimmingCharacters(in: .whitespacesAndNewlines)) } ?? 0

            let s3Access = map.removeValue(forKey: "s3_access_key") ?? ""
            let s3Secret = try decodeIniSecret(map.removeValue(forKey: "s3_secret_key"), masterKey: masterKey)
            let exportedUser = !username.isEmpty ? username : s3Access
            let exportedPass = (password?.isEmpty == false ? password : s3Secret) ?? ""

            var tunnelObj: [String: JSONValue] = [
                "enabled": .bool(tunnelEnabled),
                "host": .string(tunnelHost),
                "port": .int(Int64(tunnelPort)),
                "username": .string(tunnelUser),
                "hostkey": .string(tunnelHostkey),
                "local_port": .int(Int64(tunnelLocalPort)),
            ]
            if let tunnelPass, !tunnelPass.isEmpty {
                tunnelObj["password"] = .string(tunnelPass)
            }
            var extraObj: [String: JSONValue] = [:]
            for (k, v) in map {
                extraObj[k] = .string(v)
            }
            let sessionObj: [String: JSONValue] = [
                "name": .string(site.name),
                "protocol": .string(proto),
                "host": .string(host),
                "port": .int(Int64(port)),
                "username": .string(exportedUser),
                "password": exportedPass.isEmpty ? .null : .string(exportedPass),
                "private_key": .null,
                "hostkey": (hostkey?.isEmpty == false) ? .string(hostkey!) : .null,
                "remote_directory": .string(remoteDir),
                "local_directory": .string(""),
                "preset": .string(preset),
                "last_used": lastUsed.map(JSONValue.int) ?? .null,
                "proxy_method": .string(proxyMethod),
                "proxy_host": .string(proxyHost),
                "proxy_port": .int(Int64(proxyPort)),
                "proxy_username": .string(proxyUser),
                "proxy_password": (proxyPass?.isEmpty == false) ? .string(proxyPass!) : .null,
                "proxy_command": .string(proxyCmd),
                "proxy_exclude": .string(proxyExcl),
                "proxy_dns": .string(proxyDns),
                "proxy_localhost": .bool(proxyLocal),
                "tunnel": .object(tunnelObj),
                "tunnel_passphrase": (tunnelPassphrase?.isEmpty == false) ? .string(tunnelPassphrase!) : .null,
                "extra_fields": .object(extraObj),
            ]
            let sessionJson = JSONValue.object(sessionObj).compact()
            let url = buildSessionUrl(proto: proto, host: host, port: port)
            let pwdBytes = try encodePasswordMaterial([
                ("url", url),
                ("username", exportedUser),
                ("password", exportedPass),
                (sessionJsonColumn, sessionJson),
            ])
            let entryFields: [String: JSONValue] = [
                "secret": .string(Base32.encode(pwdBytes)),
                "issuer": .string(host.isEmpty ? "LinSCP" : host),
                "label": .string(site.name),
                "digits": .int(6),
                "type": .string("PASSWORD"),
                "algorithm": .string("SHA1"),
                "thumbnail": .string("Default"),
                "last_used": .int(0),
                "used_frequency": .int(0),
                "tags": .array([]),
            ]
            out.append(try Entry.parse(.object(entryFields)))
        }
        return out
    }

    private static func decodeIniSecret(_ raw: String?, masterKey: [UInt8]?) throws -> String? {
        guard let raw, !raw.isEmpty else { return nil }
        guard raw.hasPrefix(lscp1Prefix) else { return raw }
        guard let masterKey else { throw LinScpVaultError.missingMasterKey }
        return try decryptLscp1Password(raw, masterKey: masterKey)
    }

    private static func decodePasswordMaterial(_ bytes: [UInt8]) throws -> [(String, String)] {
        guard bytes.count >= 8, bytes.count <= SecretLimit.password else {
            throw LinScpVaultError.invalidFormat
        }
        var cursor = 0
        func readUInt32() throws -> UInt32 {
            guard cursor + 4 <= bytes.count else { throw LinScpVaultError.invalidFormat }
            let val = (UInt32(bytes[cursor]) << 24) | (UInt32(bytes[cursor + 1]) << 16)
                | (UInt32(bytes[cursor + 2]) << 8) | UInt32(bytes[cursor + 3])
            cursor += 4
            return val
        }
        func readField() throws -> String {
            let len = Int(try readUInt32())
            guard len >= 0, len <= 65_536, cursor + len <= bytes.count else {
                throw LinScpVaultError.invalidFormat
            }
            let slice = bytes[cursor..<(cursor + len)]
            cursor += len
            guard let text = String(bytes: slice, encoding: .utf8) else {
                throw LinScpVaultError.invalidUTF8
            }
            return text
        }
        guard try readUInt32() == pwdMagic else { throw LinScpVaultError.invalidFormat }
        let count = Int(try readUInt32())
        guard (1...64).contains(count) else { throw LinScpVaultError.invalidFormat }
        var out: [(String, String)] = []
        out.reserveCapacity(count)
        for _ in 0..<count {
            out.append((try readField(), try readField()))
        }
        guard cursor == bytes.count else { throw LinScpVaultError.invalidFormat }
        return out
    }

    private static func encodePasswordMaterial(_ pairs: [(String, String)]) throws -> [UInt8] {
        guard (1...64).contains(pairs.count) else { throw LinScpVaultError.invalidFormat }
        var out: [UInt8] = []
        func appendUInt32(_ val: UInt32) {
            out.append(UInt8((val >> 24) & 0xFF))
            out.append(UInt8((val >> 16) & 0xFF))
            out.append(UInt8((val >> 8) & 0xFF))
            out.append(UInt8(val & 0xFF))
        }
        func appendField(_ text: String) throws {
            let utf8 = Array(text.utf8)
            guard utf8.count <= 65_536 else { throw LinScpVaultError.invalidFormat }
            appendUInt32(UInt32(utf8.count))
            out.append(contentsOf: utf8)
        }
        appendUInt32(pwdMagic)
        appendUInt32(UInt32(pairs.count))
        for (name, val) in pairs {
            try appendField(name)
            try appendField(val)
        }
        guard out.count <= SecretLimit.password else { throw LinScpVaultError.invalidFormat }
        return out
    }

    private static func decodeSshKeyMaterial(_ bytes: [UInt8]) throws -> (String, String) {
        guard bytes.count >= 8, bytes.count <= SecretLimit.sshKey else {
            throw LinScpVaultError.invalidFormat
        }
        let m = (UInt32(bytes[0]) << 24) | (UInt32(bytes[1]) << 16)
            | (UInt32(bytes[2]) << 8) | UInt32(bytes[3])
        if m == sshMagic {
            guard bytes.count >= 12 else { throw LinScpVaultError.invalidFormat }
            let pubLen = Int((UInt32(bytes[4]) << 24) | (UInt32(bytes[5]) << 16)
                | (UInt32(bytes[6]) << 8) | UInt32(bytes[7]))
            guard pubLen >= 0, 8 + pubLen + 4 <= bytes.count else { throw LinScpVaultError.invalidFormat }
            let privOff = 8 + pubLen
            let privLen = Int((UInt32(bytes[privOff]) << 24) | (UInt32(bytes[privOff + 1]) << 16)
                | (UInt32(bytes[privOff + 2]) << 8) | UInt32(bytes[privOff + 3]))
            guard privLen >= 0, privOff + 4 + privLen == bytes.count,
                  let pub = String(bytes: bytes[8..<(8 + pubLen)], encoding: .utf8),
                  let priv = String(bytes: bytes[(privOff + 4)..<bytes.count], encoding: .utf8) else {
                throw LinScpVaultError.invalidFormat
            }
            return (pub, priv)
        }
        let pubLen = Int(m)
        guard pubLen >= 0, 4 + pubLen + 4 <= bytes.count else { throw LinScpVaultError.invalidFormat }
        let privOff = 4 + pubLen
        let privLen = Int((UInt32(bytes[privOff]) << 24) | (UInt32(bytes[privOff + 1]) << 16)
            | (UInt32(bytes[privOff + 2]) << 8) | UInt32(bytes[privOff + 3]))
        guard privLen >= 0, privOff + 4 + privLen == bytes.count,
              let pub = String(bytes: bytes[4..<(4 + pubLen)], encoding: .utf8),
              let priv = String(bytes: bytes[(privOff + 4)..<bytes.count], encoding: .utf8) else {
            throw LinScpVaultError.invalidFormat
        }
        return (pub, priv)
    }

    private static func buildSessionUrl(proto: String, host: String, port: Int) -> String {
        guard !host.isEmpty else { return "linscp://saved-session" }
        let formatted = (host.contains(":") && !host.hasPrefix("[")) ? "[\(host)]" : host
        let p = port == 0 ? defaultPort(for: proto) : port
        return "\(proto)://\(formatted):\(p)"
    }

    private static func normalizeProtocol(_ raw: String?) -> String {
        switch raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? "" {
        case "scp": return "scp"
        case "ssh": return "ssh"
        case "ftp": return "ftp"
        case "ftps": return "ftps"
        case "ftpes": return "ftpes"
        case "dav", "http": return "dav"
        case "davs", "https": return "davs"
        case "webdav": return "webdav"
        case "webdavs": return "webdavs"
        case "s3", "s3s": return "s3"
        case "local": return "local"
        case "rdp", "rdps", "msrdp", "ms-rdp": return "rdp"
        case "vnc", "rfb": return "vnc"
        case "spice": return "spice"
        case "x2go", "nx", "nx3": return "x2go"
        case "x11", "x", "waypipe": return "x11"
        default: return "sftp"
        }
    }

    private static func defaultPort(for proto: String) -> Int {
        switch proto {
        case "sftp", "scp", "ssh", "x2go", "x11": return 22
        case "ftp", "ftpes": return 21
        case "ftps": return 990
        case "dav": return 80
        case "davs", "webdav", "webdavs", "s3": return 443
        case "rdp": return 3389
        case "vnc", "spice": return 5900
        case "local": return 0
        default: return 22
        }
    }

    private static func unescapeIni(_ escaped: String) -> String {
        var out = ""
        out.reserveCapacity(escaped.count)
        var idx = escaped.startIndex
        while idx < escaped.endIndex {
            let ch = escaped[idx]
            idx = escaped.index(after: idx)
            if ch == "\\", idx < escaped.endIndex {
                let next = escaped[idx]
                idx = escaped.index(after: idx)
                switch next {
                case "n": out.append("\n")
                case "r": out.append("\r")
                case "t": out.append("\t")
                case "\\": out.append("\\")
                default:
                    out.append("\\")
                    out.append(next)
                }
            } else {
                out.append(ch)
            }
        }
        return out
    }

    private static func parseToggle(_ raw: String?) -> Bool? {
        switch raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case nil, "": return nil
        case "0", "off", "no", "false": return false
        case "1", "2", "on", "yes", "true", "auto", "automatic": return true
        default: return nil
        }
    }

    private static func looksLikeSessionsIni(_ text: String) -> Bool {
        var sawSection = false
        for rawLine in text.components(separatedBy: "\n") {
            let trimmed = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty || trimmed.hasPrefix("#") || trimmed.hasPrefix(";") { continue }
            if trimmed.hasPrefix("[") && trimmed.hasSuffix("]") && trimmed.count > 2 {
                sawSection = true
                continue
            }
            if !sawSection || !rawLine.contains("=") { return false }
        }
        return sawSection
    }

    private static func iniContainsLscp1Secret(_ ini: String) -> Bool {
        for line in ini.components(separatedBy: "\n") {
            guard let eq = line.firstIndex(of: "=") else { continue }
            let val = line[line.index(after: eq)...].trimmingCharacters(in: .whitespaces)
            if val.hasPrefix(lscp1Prefix) { return true }
        }
        return false
    }

    private static func stripBOM(_ text: String) -> String {
        text.hasPrefix("\u{FEFF}") ? String(text.dropFirst()) : text
    }

    private static func sanitizeLinScpName(_ raw: String) -> String {
        let cleaned = raw
            .replacingOccurrences(of: "[", with: "(")
            .replacingOccurrences(of: "]", with: ")")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return cleaned.isEmpty ? "Saved session" : cleaned
    }

    private static func deduplicateLabel(_ base: String, seen: inout Set<String>) -> String {
        if seen.insert(base).inserted { return base }
        var n = 2
        while true {
            let candidate = "\(base) (\(n))"
            if seen.insert(candidate).inserted { return candidate }
            n += 1
        }
    }

    private static func hostFromUrl(_ url: String) -> String {
        guard let range = url.range(of: "://") else { return "" }
        let after = String(url[range.upperBound...])
        let authPart = after.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false)
            .first.map(String.init) ?? ""
        let hostPort = authPart.components(separatedBy: "@").last ?? ""
        if hostPort.hasPrefix("[") {
            guard let close = hostPort.firstIndex(of: "]") else { return "" }
            return String(hostPort[hostPort.index(after: hostPort.startIndex)..<close])
        }
        return hostPort.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
            .first.map(String.init) ?? ""
    }
}

public enum LinScpVaultError: Error, Equatable {
    case invalidFormat
    case invalidUTF8
    case emptyCredentials
    case missingMasterKey
    case invalidMasterKey
}
