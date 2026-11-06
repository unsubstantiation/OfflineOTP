import Foundation
import LocalAuthentication
import Security

/// Which sensor the device uses for the quick unlock. Only ever used for wording: the decision the
/// system makes is the same one either way.
public enum BiometryKind: Equatable {
    case faceID
    case touchID
    case opticID
    /// The device has a biometric sensor of a kind this build does not name.
    case other

    /// The name Apple gives it, in the sentence the person reads.
    public var title: String {
        switch self {
        case .faceID: return "Face ID"
        case .touchID: return "Touch ID"
        case .opticID: return "Optic ID"
        case .other: return "Biometrics"
        }
    }

    /// The SF Symbol the interface draws for it, so the lock screen shows a face on a phone with a
    /// face sensor and a fingerprint on one with a fingerprint sensor.
    public var symbol: String {
        switch self {
        case .faceID: return "faceid"
        case .touchID: return "touchid"
        case .opticID: return "opticid"
        case .other: return "touchid"
        }
    }
}

/// Whether the quick unlock can be offered, and why not when it cannot.
public enum BiometryAvailability: Equatable {
    case available(BiometryKind)
    /// The sensor is there but this device has no enrolled face or finger.
    case notEnrolled(BiometryKind)
    /// No sensor, no passcode, or the device forbids it.
    case unavailable(BiometryKind)

    public var kind: BiometryKind {
        switch self {
        case .available(let kind), .notEnrolled(let kind), .unavailable(let kind): return kind
        }
    }

    public var isAvailable: Bool {
        if case .available = self { return true }
        return false
    }

    /// What to say when the switch cannot be turned on. Empty when it can.
    public var refusal: String {
        switch self {
        case .available:
            return ""
        case .notEnrolled(let kind):
            return "Set up \(kind.title) on this device first."
        case .unavailable(let kind):
            return "\(kind.title) is not available on this device."
        }
    }
}

/// Why a quick unlock did not happen. Deliberately small: the sentence a person sees is separate, and
/// never repeats anything the system said — a biometric failure explains nothing about the vault.
public enum BiometricKeyError: Error, Equatable {
    /// No sensor, or the device forbids biometrics.
    case unavailable
    /// No face or finger is enrolled on this device.
    case notEnrolled
    /// The person cancelled, or chose the password instead.
    case cancelled
    /// Too many failed attempts; the system wants the device passcode before it will try again.
    case lockedOut
    /// The system refused for another reason.
    case failed
    /// No key is filed for this vault — it was removed, or the item was invalidated because the
    /// enrolled face or finger changed.
    case missing

    public var message: String {
        switch self {
        case .unavailable:
            return BiometricWording.unavailable
        case .notEnrolled:
            return BiometricWording.notEnrolled
        case .cancelled:
            return BiometricWording.cancelled
        case .lockedOut:
            return BiometricWording.lockedOut
        case .failed, .missing:
            return BiometricWording.stale
        }
    }
}

/// The sentences the quick unlock uses, in one place so a test can pin them and so the same words are
/// used on the lock screen and in the settings sheet.
///
/// There is no Android or desktop wording to copy here — neither client has a biometric unlock — so
/// these are ours, and they follow the other clients' voice: short, and never explaining what the
/// system refused.
public enum BiometricWording {
    public static let reason = "Unlock your OfflineOTP vault."
    public static let cancel = "Use password"
    public static let use = "Use"
    public static let switchTitle = "Unlock with Face ID"
    public static let switchDetail =
        "Unlock this vault with Face ID instead of typing the password. The vault stays encrypted "
        + "with the password: Face ID only releases a key this device keeps, and the password still "
        + "works. A second account on this device cannot use it."
    public static let enableQuestion = "Enter the vault password to turn this on."
    public static let wrongPassword = "That password does not open this vault."
    public static let saveFailed = "This device could not keep the unlock key. Try again."
    public static let unavailable = "This device cannot use biometric unlock."
    public static let notEnrolled = "Set up Face ID on this device first."
    public static let cancelled = "Biometric unlock was cancelled. Enter your password."
    public static let lockedOut = "Too many attempts. Enter your password to unlock."
    public static let stale =
        "Biometric unlock is no longer set up for this vault. Enter your password, then turn it on "
        + "again."
    public static let turnedOn = "Biometric unlock is on for this vault."
    public static let turnedOff = "Biometric unlock is off."
}

/// The Keychain half of the quick unlock, behind a protocol so the app's tests can drive it without a
/// sensor.
public protocol BiometricKeyStore {
    /// What this device can do right now — it changes when a face is enrolled or removed, so it is
    /// asked again every time the lock screen appears.
    func availability() -> BiometryAvailability

    /// Files `key` for `account` behind the sensor. Replaces an earlier key for the same account.
    func save(key: [UInt8], account: String) throws

    /// Asks the system for the key, which prompts for the sensor and calls back on the main queue.
    func load(account: String, reason: String,
              completion: @escaping (Result<[UInt8], BiometricKeyError>) -> Void)

    /// Removes the key for `account`. Safe to call when there is none.
    func remove(account: String)
}

/// A Keychain item that the system releases only after a **successful biometric check of the face or
/// finger enrolled at the time it was written** — `kSecAccessControlBiometryCurrentSet`.
///
/// The properties are the security decision, so they are spelled out:
///
/// - the item never leaves this device (`ThisDeviceOnly`, no `kSecAttrSynchronizable`), so a stolen
///   backup, an iCloud restore on another phone or a copied app container does not carry it;
/// - enrolling a new face or finger invalidates it, which is the point of `CurrentSet`: someone who
///   can add a face to an unlocked phone still cannot use the key they added;
/// - it holds the vault's *session key*, never the password, and never anything that could derive it.
///   The vault file stays PBKDF2 + AES-GCM over the password, so losing this item only costs the
///   shortcut — the password opens the vault exactly as before.
public struct KeychainBiometricKeyStore: BiometricKeyStore {
    /// The Keychain service name; the account is the vault's `vaultIdentifier`.
    public static let service = "com.fairuniversal.offlineotp.vault-key"

    public init() {}

    public func availability() -> BiometryAvailability {
        let context = LAContext()
        var error: NSError?
        // `canEvaluatePolicy` is also what fills in `biometryType`, so it has to be asked first.
        let allowed = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics,
                                                error: &error)
        let kind = Self.kind(for: context.biometryType)
        guard allowed else {
            if error?.code == LAError.Code.biometryNotEnrolled.rawValue {
                return .notEnrolled(kind)
            }
            return .unavailable(kind)
        }
        return .available(kind)
    }

    public func save(key: [UInt8], account: String) throws {
        guard case .available = availability(), !key.isEmpty else {
            throw BiometricKeyError.unavailable
        }
        // A replace, not a second item: an item written under a changed face set has to go.
        remove(account: account)
        var creationError: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            .biometryCurrentSet,
            &creationError
        ) else {
            throw BiometricKeyError.failed
        }
        let item: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: account,
            kSecValueData as String: Data(key),
            kSecAttrAccessControl as String: access,
            kSecAttrSynchronizable as String: false,
        ]
        guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else {
            throw BiometricKeyError.failed
        }
    }

    public func load(account: String, reason: String,
                     completion: @escaping (Result<[UInt8], BiometricKeyError>) -> Void) {
        let context = LAContext()
        context.localizedCancelTitle = BiometricWording.cancel
        var evaluationError: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics,
                                        error: &evaluationError) else {
            completion(.failure(Self.error(for: evaluationError)))
            return
        }
        context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics,
                               localizedReason: reason) { success, error in
            guard success else {
                DispatchQueue.main.async { completion(.failure(Self.error(for: error))) }
                return
            }
            // The same, now-authenticated context reads the item, so the person is asked once. The
            // item's own access control still decides: a context that did not pass biometry cannot
            // read it.
            let query: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: Self.service,
                kSecAttrAccount as String: account,
                kSecReturnData as String: true,
                kSecMatchLimit as String: kSecMatchLimitOne,
                kSecUseAuthenticationContext as String: context,
            ]
            var result: CFTypeRef?
            let status = SecItemCopyMatching(query as CFDictionary, &result)
            DispatchQueue.main.async {
                switch status {
                case errSecSuccess:
                    if let data = result as? Data, !data.isEmpty {
                        completion(.success([UInt8](data)))
                    } else {
                        completion(.failure(.missing))
                    }
                case errSecItemNotFound, errSecAuthFailed, errSecInteractionNotAllowed:
                    // The face or finger set changed, or the item was removed: same answer either
                    // way, and the caller offers the password.
                    completion(.failure(.missing))
                default:
                    completion(.failure(.failed))
                }
            }
        }
    }

    public func remove(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: account,
        ]
        _ = SecItemDelete(query as CFDictionary)
    }

    private static func kind(for type: LABiometryType) -> BiometryKind {
        switch type {
        case .faceID: return .faceID
        case .touchID: return .touchID
        case .opticID: return .opticID
        default: return .other
        }
    }

    /// The system's answer, mapped. Compared by raw code so the mapping does not depend on a bridge
    /// cast from `NSError` to `LAError`.
    private static func error(for error: Error?) -> BiometricKeyError {
        guard let error else { return .failed }
        switch (error as NSError).code {
        case LAError.Code.userCancel.rawValue, LAError.Code.appCancel.rawValue,
             LAError.Code.systemCancel.rawValue, LAError.Code.userFallback.rawValue:
            return .cancelled
        case LAError.Code.biometryNotEnrolled.rawValue:
            return .notEnrolled
        case LAError.Code.biometryLockout.rawValue:
            return .lockedOut
        case LAError.Code.biometryNotAvailable.rawValue, LAError.Code.passcodeNotSet.rawValue:
            return .unavailable
        default:
            return .failed
        }
    }
}
