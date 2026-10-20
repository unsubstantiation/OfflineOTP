package org.shadowice.flocke.andotp.View

/** Android-free delayed single/double tap state machine, parameterized by a target and scheduler. */
class TapSequenceController<T : Any>(
    private val nowMillis: () -> Long,
    private val postDelayed: (T, Runnable, Long) -> Unit,
    private val removeCallbacks: (T, Runnable) -> Unit,
    private val onSingleClick: (T) -> Unit,
    private val onDoubleClick: (T) -> Unit,
    private val doubleClickTimeDelta: Long = 300L
) {
    private var lastClickTime = 0L
    private var suppressRapidClicksAfterDouble = false
    private var callbackToken = 0L
    private var pendingSingleClick: Runnable? = null
    private var pendingTarget: T? = null

    init {
        require(doubleClickTimeDelta > 0L)
    }

    fun onClick(target: T) {
        val clickTime = nowMillis()
        if (pendingSingleClick == null && suppressRapidClicksAfterDouble) {
            if (clickTime >= lastClickTime && clickTime - lastClickTime < doubleClickTimeDelta) {
                // Match the original firstTap guard: extra taps in the same rapid sequence after
                // a double are ignored instead of becoming an unintended single action.
                lastClickTime = clickTime
                return
            }
            suppressRapidClicksAfterDouble = false
        }

        val pending = pendingSingleClick
        val firstTarget = pendingTarget

        if (pending != null && firstTarget === target &&
            clickTime >= lastClickTime && clickTime - lastClickTime < doubleClickTimeDelta) {
            removeCallbacks(target, pending)
            pendingSingleClick = null
            pendingTarget = null
            callbackToken++
            lastClickTime = clickTime
            suppressRapidClicksAfterDouble = true
            onDoubleClick(target)
            return
        }

        if (pending != null) {
            if (firstTarget != null) removeCallbacks(firstTarget, pending)
            pendingSingleClick = null
            pendingTarget = null
            callbackToken++
            // A click on another item, or outside the double-tap window, proves the first
            // click was single. Do not silently lose it while arming this new click.
            if (firstTarget != null) onSingleClick(firstTarget)
        }

        lastClickTime = clickTime
        val token = ++callbackToken
        pendingTarget = target
        val singleClick = Runnable {
            if (token == callbackToken && pendingTarget === target) {
                pendingSingleClick = null
                pendingTarget = null
                onSingleClick(target)
            }
        }
        pendingSingleClick = singleClick
        postDelayed(target, singleClick, doubleClickTimeDelta)
    }

    /** Cancels a pending single without delivering it, e.g. when its view is detached. */
    fun cancelPending() {
        val target = pendingTarget
        val callback = pendingSingleClick
        if (target != null && callback != null) removeCallbacks(target, callback)
        pendingTarget = null
        pendingSingleClick = null
        suppressRapidClicksAfterDouble = false
        callbackToken++
    }
}
