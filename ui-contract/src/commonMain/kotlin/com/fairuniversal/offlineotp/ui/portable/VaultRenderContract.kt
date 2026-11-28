package com.fairuniversal.offlineotp.ui.portable

/** One immutable render snapshot. Contains only already-authorized display values, never entries,
 * keys or session authority. Do not save/serialize frames; immutable strings cannot be zeroized.
 */
class VaultCardContent(
    val title: String,
    val subtitle: String = "",
    val revealed: Boolean = false,
    val showThumbnail: Boolean = true,
    val thumbnailName: String = "",
    val tileSeed: String = title,
    val thumbnailSizeDp: Float = 42f,
    val scrollLabels: Boolean = false,
    val labelSizeSp: Int? = null,
    val labelMaxLines: Int = 1,
    val verticalPaddingDp: Int = 12,
    val outerVerticalPaddingDp: Int = 0,
    val codeBelowIdentity: Boolean = false,
    val showCodeArea: Boolean = false,
    val tags: List<String> = emptyList(),
    val customPeriodLabel: String? = null,
    val detailLabel: String? = null,
    val metadataLabel: String? = null,
    val metadataWarning: Boolean = false,
    val revealedContent: String? = null,
    val isNote: Boolean = false,
    token: String? = null,
    val tokenVisible: Boolean = false,
    val expiring: Boolean = false,
    previousToken: String? = null,
    val showCountdown: Boolean = false,
    val progress: Float = 0f,
    val remainingSeconds: Int = 0,
    val showSecondsText: Boolean = true,
    val pinRequired: Boolean = false,
    val pinRequiredLabel: String = "PIN required",
    val counterText: String? = null,
    val noCodeLabel: String = "—",
    val onTap: String? = null,
    val onDoubleTap: String? = null,
    val actions: List<VaultCardCommand> = emptyList()
) {
    val token: String? = token?.let { value -> if (tokenVisible) value else value.map { if (it == ' ') ' ' else '\u2022' }.joinToString("") }
    val previousToken: String? = previousToken?.takeIf { tokenVisible }
    override fun toString() = "VaultCardContent(redacted)"
}
class VaultCardCommand(val key: String, val label: String, val description: String,
    val enabled: Boolean = true, val longPress: String? = null) {
    override fun toString() = "VaultCardCommand(redacted)"
}

/** Gesture metadata only, confined to the UI executor. No optimistic repeated writes: wait for the
 * exact requested order or cancel when a host publishes a different order/filter/session.
 */
class VaultDragSession {
    var held: Long? = null; private set
    private var order: List<Long> = emptyList()
    private var awaiting: List<Long>? = null
    private var expected: List<Long>? = null
    val waiting: Boolean get() = awaiting != null
    fun start(id: Long, order: List<Long>): Boolean {
        cancel()
        if (id !in order || order.distinct().size != order.size) return false
        held = id; this.order = order.toList(); return true
    }
    fun adopt(current: List<Long>): Boolean {
        if (held == null) return false
        if (awaiting != null) {
            if (current == awaiting) return false
            if (current != expected) { cancel(); return false }
            order = current.toList(); awaiting = null; expected = null
        }
        if (current != order) { cancel(); return false }
        return true
    }
    fun request(direction: Int, current: List<Long>): Boolean {
        if (direction !in listOf(-1, 1) || !adopt(current)) return false
        val index = current.indexOf(held)
        if (index < 0 || index + direction !in current.indices) return false
        val next = current.toMutableList()
        next[index] = current[index + direction]; next[index + direction] = current[index]
        awaiting = current.toList(); expected = next
        return true
    }
    fun cancel() { held = null; order = emptyList(); awaiting = null; expected = null }
    override fun toString() = "VaultDragSession(redacted)"
}

/** UI delivery token, not vault authority. Replacing an index-addressed Apple frame invalidates
 * callbacks from old frames; closing is terminal. Hosts still check live session/entry identity.
 */
class VaultFrameDelivery {
    private var epoch = 0L
    private var closed = false
    private var published = false
    fun publish(): Long { if (!closed) { epoch++; published = true }; return epoch }
    fun current(ticket: Long): Boolean = !closed && published && ticket > 0 && ticket == epoch
    fun invalidate() { epoch++; published = false }
    fun close() { closed = true; invalidate() }
}
