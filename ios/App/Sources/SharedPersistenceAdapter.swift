import OfflineOTPKit
import OfflineOTPPresentation

struct SharedPersistenceAdapter: PersistenceAdapter {
    func transaction(requiresBackup: Bool) -> PersistenceTransaction {
        SharedPersistenceTransaction(requiresBackup: requiresBackup)
    }
    func canInitialize(vaultExists: Bool, backupExists: Bool) -> Bool {
        PersistenceRecoveryPolicy.shared.canInitialize(vaultExists: vaultExists, backupExists: backupExists, pendingExists: false)
    }
}

private final class SharedPersistenceTransaction: PersistenceTransaction {
    private let workflow: PersistenceWorkflow
    init(requiresBackup: Bool) { workflow = PersistenceWorkflow(requiresBackup: requiresBackup, metadataTransaction: false) }
    func verified() -> Bool { workflow.verified(permitted: true) }
    func backedUp() -> Bool { workflow.backedUp() }
    func beginPromotion() -> Bool { workflow.beginPromotion(permitted: true) }
    func promoted() -> Bool { workflow.promoted() }
    func promotionFailed() { workflow.promotionFailed() }
}
