package com.fairuniversal.offlineotp.desktop.control

/**
 * One window-wide write lease, shared across sessions. Thread-safe, with no UI callbacks.
 * All writers/rekeys and unlock admission must consult the same instance before integration.
 * A successful worker keeps the lease until UI adoption; abandoned work releases only after
 * worker completion. Rejected UI dispatch intentionally blocks further writes (fail closed).
 */
class DesktopWriteGate {
    private var owner: Lease? = null
    val busy: Boolean @Synchronized get() = owner != null

    @Synchronized fun acquire(): Lease? {
        if (owner != null) return null
        return Lease(this).also { owner = it }
    }
    @Synchronized private fun release(lease: Lease) { if (owner === lease) owner = null }

    class Lease internal constructor(private val gate: DesktopWriteGate) {
        private var started = false
        private var finished = false
        private var abandoned = false
        private var released = false

        @Synchronized fun start(): Boolean {
            if (started || abandoned || released) return false
            started = true
            return true
        }
        @Synchronized fun workerFinished() {
            if (!started || finished) return
            finished = true
            if (abandoned) release()
        }
        /** UI adoption/rollback completed. Must not be called merely because a write was queued. */
        @Synchronized fun acknowledged() {
            if (finished) release()
        }
        /** Cancel queued work, or suppress adoption while allowing a started write to finish. */
        @Synchronized fun abandon() {
            abandoned = true
            if (!started || finished) release()
        }
        private fun release() {
            if (!released) { released = true; gate.release(this) }
        }
    }
}
