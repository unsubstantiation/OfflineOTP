package com.fairuniversal.offlineotp.ui.vault

/**
 * Toolkit-free lifecycle for a host that publishes externally computed frames (Android).
 * Call from one UI thread. Closing is terminal: late renders and retained callbacks are ignored.
 * A newly unlocked session needs a new host; callers still validate live ids and async search
 * revisions within that session. Already-issued immutable frames cannot be zeroized here.
 */
class VaultFrameHost(actions: VaultActions) {
    private var closed = false
    private var published = false
    var frame: VaultUiState = VaultUiState()
        private set

    val actions: VaultActions = guard(actions)

    /** Bind a new frame's callbacks without redirecting callbacks retained from older frames. */
    fun guard(actions: VaultActions): VaultActions = actions.guarded { published && !closed }

    /** Returns false if the view's session has ended. */
    fun render(next: VaultUiState): Boolean {
        if (closed) return false
        frame = next
        published = true
        return true
    }

    fun close() {
        closed = true
        published = false
        frame = VaultUiState()
    }
}
