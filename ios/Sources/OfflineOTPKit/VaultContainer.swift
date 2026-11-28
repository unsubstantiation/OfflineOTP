import Foundation

/// Byte-compatible with :core VaultContainer. A copied file carries all password recovery metadata.
/// The complete 40-byte header is authenticated as AES-GCM associated data. No device keys/verifier.
public enum VaultContainer {
    private static let magic: [UInt8] = [79, 84, 80, 86, 76, 84, 13, 10]
    public static let headerBytes = 40
    public static let overheadBytes = 68
    public static let maxPlaintextBytes = InputPolicy.maxDocumentBytes - overheadBytes
    public struct Header {
        fileprivate let bytes: Data
        public var salt: [UInt8] { Array(bytes.dropFirst(24).prefix(16)) }
        public var kdf: String { bytes[10] == 1 ? VaultCrypto.kdfArgon2id : VaultCrypto.kdfPbkdf2 }
        public var memoryKiB: Int { VaultContainer.intAt(bytes, 12) }
        public var iterations: Int { VaultContainer.intAt(bytes, 16) }
        public var parallelism: Int { VaultContainer.intAt(bytes, 20) }
    }
    public static func isContainer(_ data: Data) -> Bool { data.starts(with: magic) }
    public static func header(_ data: Data, protocolPolicy: VaultProtocolAdapter = NativeVaultProtocolAdapter()) throws -> Header {
        Header(bytes: try protocolPolicy.vaultHeader(data))
    }
    static func nativeHeader(_ data: Data) throws -> Data {
        guard data.count > overheadBytes, data.count <= InputPolicy.maxDocumentBytes, isContainer(data) else {
            throw VaultOpenError.corrupt
        }
        return try nativeCheckedHeader(Data(data.prefix(headerBytes)))
    }
    public static func fromSettings(_ settings: KeyValueSettings, protocolPolicy: VaultProtocolAdapter = NativeVaultProtocolAdapter()) throws -> Header {
        let text = settings.string(VaultSettingsKeys.authKdf)?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        let kdf = text.isEmpty ? VaultCrypto.kdfPbkdf2 : text
        guard kdf == VaultCrypto.kdfArgon2id || kdf == VaultCrypto.kdfPbkdf2,
              let saltText = settings.string(VaultSettingsKeys.authSalt),
              let salt = VaultCrypto.base64URLDecode(saltText), salt.count == 16 else { throw VaultOpenError.corrupt }
        let argon = kdf == VaultCrypto.kdfArgon2id
        let memory = settings.integer(VaultSettingsKeys.authMemoryKiB) ?? VaultCrypto.argon2MemoryKiB
        let iterations = settings.integer(VaultSettingsKeys.authIterations) ?? (argon ? VaultCrypto.argon2Iterations : VaultCrypto.minAuthIterations)
        let parallelism = settings.integer(VaultSettingsKeys.authParallelism) ?? VaultCrypto.argon2Parallelism
        return Header(bytes: try protocolPolicy.makeVaultHeader(kdf: kdf, salt: salt, memoryKiB: memory,
            iterations: iterations, parallelism: parallelism))
    }

    public static func deriveKey(password: String, header: Header) throws -> [UInt8] {
        guard !password.isEmpty, password.utf16.count <= InputPolicy.maxTextChars else { throw VaultOpenError.authenticationFailed }
        let credentials: VaultCrypto.Credentials
        if header.kdf == VaultCrypto.kdfArgon2id {
            credentials = try VaultCrypto.argon2idCredentials(password: password, salt: header.salt, memoryKiB: header.memoryKiB,
                iterations: header.iterations, parallelism: header.parallelism)
        } else {
            credentials = try VaultCrypto.credentials(password: password, salt: header.salt, iterations: header.iterations)
        }
        // Swift/CryptoKit may retain immutable/COW temporaries; physical zeroization is not promised.
        return credentials.key
    }
    public static func open(password: String, data: Data, protocolPolicy: VaultProtocolAdapter = NativeVaultProtocolAdapter()) throws -> Data {
        let descriptor = try header(data, protocolPolicy: protocolPolicy)
        var key = try deriveKey(password: password, header: descriptor)
        defer { for i in key.indices { key[i] = 0 } }
        return try openWithKey(key, data: data, protocolPolicy: protocolPolicy)
    }
    public static func openWithKey(_ key: [UInt8], data: Data, protocolPolicy: VaultProtocolAdapter = NativeVaultProtocolAdapter()) throws -> Data {
        let descriptor = try header(data, protocolPolicy: protocolPolicy)
        guard key.count == (descriptor.kdf == VaultCrypto.kdfArgon2id ? 32 : 16) else { throw VaultOpenError.authenticationFailed }
        return try VaultCrypto.open(Data(data.dropFirst(headerBytes)), key: key, authenticating: descriptor.bytes)
    }
    public static func seal(key: [UInt8], header: Header, plaintext: Data, protocolPolicy: VaultProtocolAdapter = NativeVaultProtocolAdapter()) throws -> Data {
        guard !plaintext.isEmpty, plaintext.count <= maxPlaintextBytes,
              key.count == (header.kdf == VaultCrypto.kdfArgon2id ? 32 : 16) else { throw VaultWriteError.invalidInput }
        let descriptor = Header(bytes: try protocolPolicy.makeVaultHeader(kdf: header.kdf, salt: header.salt,
            memoryKiB: header.memoryKiB, iterations: header.iterations, parallelism: header.parallelism))
        let sealed = try VaultCrypto.seal(plaintext, key: key, nonce: VaultCrypto.randomBytes(12), authenticating: descriptor.bytes)
        return descriptor.bytes + sealed
    }
    static func nativeCheckedHeader(_ bytes: Data) throws -> Data {
        guard bytes.count == headerBytes, isContainer(bytes), bytes[8] == 1, bytes[9] == 1, bytes[11] == 0 else { throw VaultOpenError.corrupt }
        let memory = intAt(bytes, 12), iterations = intAt(bytes, 16), parallelism = intAt(bytes, 20)
        if bytes[10] == 1 {
            do { try Argon2id.validateParameters(saltCount: 16, memoryKiB: memory, iterations: iterations, parallelism: parallelism, tagLength: 64) }
            catch { throw VaultOpenError.corrupt }
        } else if bytes[10] == 2 {
            guard memory == 0, parallelism == 0, (1...20_000_000).contains(iterations) else { throw VaultOpenError.corrupt }
        } else { throw VaultOpenError.corrupt }
        return bytes
    }
    private static func intAt(_ data: Data, _ offset: Int) -> Int {
        (0..<4).reduce(0) { ($0 << 8) | Int(data[offset + $1]) }
    }
}
