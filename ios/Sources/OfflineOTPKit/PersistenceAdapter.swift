import Foundation

/// Sequencing only. Production injects the shared Kotlin policy; standalone SwiftPM retains
/// this independent oracle until native parity gates pass. No fallback after shared rejection.
public protocol PersistenceTransaction: AnyObject {
    func verified() -> Bool
    func backedUp() -> Bool
    func beginPromotion() -> Bool
    func promoted() -> Bool
    func promotionFailed()
}

public protocol PersistenceAdapter {
    func transaction(requiresBackup: Bool) -> PersistenceTransaction
    func canInitialize(vaultExists: Bool, backupExists: Bool) -> Bool
}

public struct NativePersistenceAdapter: PersistenceAdapter {
    public init() { }
    public func transaction(requiresBackup: Bool) -> PersistenceTransaction {
        NativePersistenceTransaction(requiresBackup: requiresBackup)
    }
    public func canInitialize(vaultExists: Bool, backupExists: Bool) -> Bool { !vaultExists && !backupExists }
}

private final class NativePersistenceTransaction: PersistenceTransaction {
    private enum Phase { case preparing, verified, backedUp, promoting, saved, recovery }
    private var phase = Phase.preparing
    private let requiresBackup: Bool
    init(requiresBackup: Bool) { self.requiresBackup = requiresBackup }
    func verified() -> Bool {
        guard phase == .preparing else { return false }; phase = .verified; return true
    }
    func backedUp() -> Bool {
        guard requiresBackup, phase == .verified else { return false }; phase = .backedUp; return true
    }
    func beginPromotion() -> Bool {
        guard phase == (requiresBackup ? .backedUp : .verified) else { return false }
        phase = .promoting; return true
    }
    func promoted() -> Bool {
        guard phase == .promoting else { return false }; phase = .saved; return true
    }
    func promotionFailed() { if phase == .promoting { phase = .recovery } }
}
