package com.fairuniversal.offlineotp.ui.portable

/**
 * Toolkit-free presentation values for the portable iOS surface.
 *
 * This is deliberately not `:ui-model`: that module is compiled against JVM `core` and cannot be
 * consumed by Kotlin/Native. These values contain strings, numbers and booleans only, so the same
 * Compose declaration can be hosted by UIKit without dragging the engine into the framework.
 */

/** One vault card. [index] is an index into the host's live entry array, never a row position. */
data class PortableCard(
    val index: Int,
    val title: String,
    val subtitle: String,
    val concealedTitle: String,
    val code: String,
    val previousCode: String,
    val metadata: String,
    val secondsLeft: Int,
    val revealed: Boolean,
    val canStep: Boolean,
    val hasCode: Boolean,
    val mark: String,
    val tags: List<String>,
    val canReorder: Boolean = false,
    val canCopy: Boolean = hasCode,
    val canCopyAccount: Boolean = false,
    val canCopyPassword: Boolean = false,
    val detailLabel: String = "",
    val revealedContent: String = "",
    val isNote: Boolean = false,
    val pinRequired: Boolean = false,
    val periodSeconds: Int = 30,
    val showCountdown: Boolean = true,
    val counterLabel: String = "",
    val isEmail: Boolean = false
) {
    constructor(
        index: Int,
        title: String,
        subtitle: String,
        concealedTitle: String,
        code: String,
        previousCode: String,
        metadata: String,
        secondsLeft: Int,
        revealed: Boolean,
        canStep: Boolean,
        hasCode: Boolean,
        mark: String,
        tags: List<String>,
        canReorder: Boolean,
        canCopy: Boolean,
        canCopyAccount: Boolean,
        canCopyPassword: Boolean,
        detailLabel: String,
        revealedContent: String,
        isNote: Boolean,
        pinRequired: Boolean
    ) : this(
        index = index,
        title = title,
        subtitle = subtitle,
        concealedTitle = concealedTitle,
        code = code,
        previousCode = previousCode,
        metadata = metadata,
        secondsLeft = secondsLeft,
        revealed = revealed,
        canStep = canStep,
        hasCode = hasCode,
        mark = mark,
        tags = tags,
        canReorder = canReorder,
        canCopy = canCopy,
        canCopyAccount = canCopyAccount,
        canCopyPassword = canCopyPassword,
        detailLabel = detailLabel,
        revealedContent = revealedContent,
        isNote = isNote,
        pinRequired = pinRequired,
        periodSeconds = 30,
        showCountdown = true,
        counterLabel = "",
        isEmail = false
    )

    /** The name shown while the row is concealed, never the hidden label. */
    fun visibleTitle(hideIssuer: Boolean): String =
        if (revealed || !hideIssuer) title else concealedTitle

    /** Brand thumbnail name or tile seed while visible; falls back to [concealedTitle] when issuer hiding is active. */
    fun visibleMark(hideIssuer: Boolean): String =
        if (revealed || !hideIssuer) mark else concealedTitle

    /** A displayed code is never guessed from a hidden entry. */
    fun visibleCode(): String = if (revealed && hasCode) code else ""

    /** Connection/detail summary is disclosed only while the card is revealed. */
    fun visibleDetailLabel(): String = if (revealed) detailLabel else ""

    /** Revealed body (note text, SSH public key, password URL, email address) is hidden until revealed. */
    fun visibleRevealedContent(): String = if (revealed) revealedContent else ""

    /** Matches `:ui-model`'s 5-second rollover warning window. */
    val isExpiring: Boolean
        get() = hasCode && secondsLeft in 1..5

    /** Normalized countdown arc fraction for time-based OTP cards (`0f` when hidden or counter-based). */
    val countdownProgress: Float
        get() {
            if (!hasCode || secondsLeft <= 0) return 0f
            val step = if (metadata.startsWith("MOTP")) 10 else maxOf(periodSeconds.coerceAtLeast(1), secondsLeft)
            return (secondsLeft.toFloat() / step.toFloat()).coerceIn(0f, 1f)
        }

    /** Non-default rollover interval summary matching `:ui-model`'s custom-period card label. */
    val customPeriodLabel: String
        get() = if (revealed && hasCode && periodSeconds > 0 && periodSeconds != 30 && !metadata.startsWith("MOTP")) {
            "${periodSeconds}s period"
        } else {
            ""
        }

    /** Redacts secret-bearing strings from diagnostic logs and assertion failures. */
    override fun toString(): String =
        "PortableCard(index=$index, revealed=$revealed, hasCode=$hasCode, pinRequired=$pinRequired)"
}

/** A tab or tag chip. Counts are supplied by the host so the model never re-derives them. */
data class PortableChip(
    val label: String,
    val count: Int,
    val selected: Boolean
)

/** The unlocked vault screen: everything a host has already decided, ready to draw. */
data class PortableVaultState(
    val tabs: List<PortableChip>,
    val query: String,
    val tags: List<PortableChip>,
    val includeUntagged: Boolean,
    val tagRule: String,
    val cards: List<PortableCard>,
    val sortLabel: String,
    val toast: String,
    val hiddenTitleEnabled: Boolean = false,
    val countLabel: String = "",
    val sortOptions: List<PortableChip> = emptyList(),
    val labelDisplayName: String = "TRUNCATE",
    val showThumbnails: Boolean = true,
    val labelSizeSp: Int = 0,
    val highlightExpiringTokens: Boolean = true,
    val tapSingleName: String = "REVEAL",
    val tapDoubleName: String = "NOTHING",
    val globalProgress: Float = 0f,
    val globalExpiring: Boolean = false,
    val hideGlobalTimeout: Boolean = false,
    val customThumbnailSizeDp: Int = 0,
    val focusSearchOnStart: Boolean = false,
    val toolGroups: List<PortableToolGroup> = emptyList()
) {
    val labelDisplay: PortableLabelDisplay
        get() = PortableLabelDisplay.values().firstOrNull { it.name == labelDisplayName }
            ?: PortableLabelDisplay.TRUNCATE

    val tapSingle: PortableTapMode
        get() = PortableTapMode.fromName(tapSingleName, fallback = PortableTapMode.REVEAL)

    val tapDouble: PortableTapMode
        get() = PortableTapMode.fromName(tapDoubleName, fallback = PortableTapMode.NOTHING)

    constructor(
        tabs: List<PortableChip>,
        query: String,
        tags: List<PortableChip>,
        includeUntagged: Boolean,
        tagRule: String,
        cards: List<PortableCard>,
        sortLabel: String,
        toast: String,
        hiddenTitleEnabled: Boolean,
        countLabel: String,
        sortOptions: List<PortableChip>,
        labelDisplayName: String,
        showThumbnails: Boolean,
        labelSizeSp: Int,
        highlightExpiringTokens: Boolean,
        tapSingleName: String,
        tapDoubleName: String,
        globalProgress: Float,
        globalExpiring: Boolean,
        hideGlobalTimeout: Boolean,
        customThumbnailSizeDp: Int,
        focusSearchOnStart: Boolean
    ) : this(
        tabs = tabs,
        query = query,
        tags = tags,
        includeUntagged = includeUntagged,
        tagRule = tagRule,
        cards = cards,
        sortLabel = sortLabel,
        toast = toast,
        hiddenTitleEnabled = hiddenTitleEnabled,
        countLabel = countLabel,
        sortOptions = sortOptions,
        labelDisplayName = labelDisplayName,
        showThumbnails = showThumbnails,
        labelSizeSp = labelSizeSp,
        highlightExpiringTokens = highlightExpiringTokens,
        tapSingleName = tapSingleName,
        tapDoubleName = tapDoubleName,
        globalProgress = globalProgress,
        globalExpiring = globalExpiring,
        hideGlobalTimeout = hideGlobalTimeout,
        customThumbnailSizeDp = customThumbnailSizeDp,
        focusSearchOnStart = focusSearchOnStart,
        toolGroups = emptyList()
    )

    constructor(
        tabs: List<PortableChip>,
        query: String,
        tags: List<PortableChip>,
        includeUntagged: Boolean,
        tagRule: String,
        cards: List<PortableCard>,
        sortLabel: String,
        toast: String,
        hiddenTitleEnabled: Boolean,
        countLabel: String,
        sortOptions: List<PortableChip>,
        labelDisplayName: String,
        showThumbnails: Boolean,
        labelSizeSp: Int,
        highlightExpiringTokens: Boolean,
        tapSingleName: String,
        tapDoubleName: String,
        globalProgress: Float,
        globalExpiring: Boolean,
        hideGlobalTimeout: Boolean
    ) : this(
        tabs = tabs,
        query = query,
        tags = tags,
        includeUntagged = includeUntagged,
        tagRule = tagRule,
        cards = cards,
        sortLabel = sortLabel,
        toast = toast,
        hiddenTitleEnabled = hiddenTitleEnabled,
        countLabel = countLabel,
        sortOptions = sortOptions,
        labelDisplayName = labelDisplayName,
        showThumbnails = showThumbnails,
        labelSizeSp = labelSizeSp,
        highlightExpiringTokens = highlightExpiringTokens,
        tapSingleName = tapSingleName,
        tapDoubleName = tapDoubleName,
        globalProgress = globalProgress,
        globalExpiring = globalExpiring,
        hideGlobalTimeout = hideGlobalTimeout,
        customThumbnailSizeDp = 0,
        focusSearchOnStart = false
    )

    constructor(
        tabs: List<PortableChip>,
        query: String,
        tags: List<PortableChip>,
        includeUntagged: Boolean,
        tagRule: String,
        cards: List<PortableCard>,
        sortLabel: String,
        toast: String,
        hiddenTitleEnabled: Boolean,
        countLabel: String,
        sortOptions: List<PortableChip>,
        labelDisplayName: String,
        showThumbnails: Boolean,
        labelSizeSp: Int,
        highlightExpiringTokens: Boolean,
        tapSingleName: String,
        tapDoubleName: String
    ) : this(
        tabs = tabs,
        query = query,
        tags = tags,
        includeUntagged = includeUntagged,
        tagRule = tagRule,
        cards = cards,
        sortLabel = sortLabel,
        toast = toast,
        hiddenTitleEnabled = hiddenTitleEnabled,
        countLabel = countLabel,
        sortOptions = sortOptions,
        labelDisplayName = labelDisplayName,
        showThumbnails = showThumbnails,
        labelSizeSp = labelSizeSp,
        highlightExpiringTokens = highlightExpiringTokens,
        tapSingleName = tapSingleName,
        tapDoubleName = tapDoubleName,
        globalProgress = 0f,
        globalExpiring = false,
        hideGlobalTimeout = false
    )

    constructor(
        tabs: List<PortableChip>,
        query: String,
        tags: List<PortableChip>,
        includeUntagged: Boolean,
        tagRule: String,
        cards: List<PortableCard>,
        sortLabel: String,
        toast: String,
        hiddenTitleEnabled: Boolean,
        countLabel: String
    ) : this(
        tabs = tabs,
        query = query,
        tags = tags,
        includeUntagged = includeUntagged,
        tagRule = tagRule,
        cards = cards,
        sortLabel = sortLabel,
        toast = toast,
        hiddenTitleEnabled = hiddenTitleEnabled,
        countLabel = countLabel,
        sortOptions = emptyList(),
        labelDisplayName = "TRUNCATE",
        showThumbnails = true,
        labelSizeSp = 0,
        highlightExpiringTokens = true,
        tapSingleName = "REVEAL",
        tapDoubleName = "NOTHING"
    )

    constructor(
        tabs: List<PortableChip>,
        query: String,
        tags: List<PortableChip>,
        includeUntagged: Boolean,
        tagRule: String,
        cards: List<PortableCard>,
        sortLabel: String,
        toast: String,
        hiddenTitleEnabled: Boolean,
        countLabel: String,
        sortOptions: List<PortableChip>
    ) : this(
        tabs = tabs,
        query = query,
        tags = tags,
        includeUntagged = includeUntagged,
        tagRule = tagRule,
        cards = cards,
        sortLabel = sortLabel,
        toast = toast,
        hiddenTitleEnabled = hiddenTitleEnabled,
        countLabel = countLabel,
        sortOptions = sortOptions,
        labelDisplayName = "TRUNCATE",
        showThumbnails = true,
        labelSizeSp = 0,
        highlightExpiringTokens = true,
        tapSingleName = "REVEAL",
        tapDoubleName = "NOTHING"
    )

    constructor(
        tabs: List<PortableChip>,
        query: String,
        tags: List<PortableChip>,
        includeUntagged: Boolean,
        tagRule: String,
        cards: List<PortableCard>,
        sortLabel: String,
        toast: String,
        hiddenTitleEnabled: Boolean,
        countLabel: String,
        sortOptions: List<PortableChip>,
        labelDisplayName: String,
        showThumbnails: Boolean
    ) : this(
        tabs = tabs,
        query = query,
        tags = tags,
        includeUntagged = includeUntagged,
        tagRule = tagRule,
        cards = cards,
        sortLabel = sortLabel,
        toast = toast,
        hiddenTitleEnabled = hiddenTitleEnabled,
        countLabel = countLabel,
        sortOptions = sortOptions,
        labelDisplayName = labelDisplayName,
        showThumbnails = showThumbnails,
        labelSizeSp = 0,
        highlightExpiringTokens = true,
        tapSingleName = "REVEAL",
        tapDoubleName = "NOTHING"
    )

    val emptyMessage: String
        get() = if (query.isNotEmpty()) "No entries match this search."
                else "Nothing here yet. Add an entry with +."

    override fun toString(): String =
        "PortableVaultState(tabs=${tabs.size}, cards=${cards.size}, hasQuery=${query.isNotEmpty()})"

    companion object {
        val EMPTY = PortableVaultState(
            tabs = emptyList(), query = "", tags = emptyList(), includeUntagged = false,
            tagRule = "Any", cards = emptyList(), sortLabel = "Order", toast = ""
        )
    }
}

/** One row in a portable dialog. [key] is stable for the lifetime of the request. */
data class PortableRow(
    val key: String,
    val title: String,
    val value: String = "",
    val kind: String = KIND_BUTTON,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val detail: String = ""
) {
    companion object {
        const val KIND_BUTTON = "button"
        const val KIND_TOGGLE = "toggle"
        const val KIND_TEXT = "text"
        const val KIND_SECURE = "secure"
        const val KIND_INFO = "info"
        const val KIND_ACTION = "action"
    }
}

/** A titled group of rows. The renderer never invents a section. */
data class PortableSection(val title: String, val rows: List<PortableRow>)

/**
 * A dialog whose content is entirely host-supplied. Migration, backup, editor, detail and settings
 * surfaces share this one renderer; the host owns validation and every state change.
 */
data class PortableSurfaceState(
    val key: String,
    val title: String,
    val sections: List<PortableSection>,
    val confirmLabel: String = "Save",
    val dismissLabel: String = "Cancel",
    val showConfirm: Boolean = true,
    val message: String = "",
    val busy: Boolean = false
) {
    /** Rows the host marked as editable or actionable, in display order. */
    val interactiveRows: List<PortableRow>
        get() = sections.flatMap { it.rows }.filter {
            it.kind != PortableRow.KIND_INFO && it.enabled
        }
}

/** Label/value row for read-only detail dialogs shared across Android, Desktop and iOS. */
data class PortableDetailRow(val label: String, val value: String) {
    override fun toString(): String = "PortableDetailRow($label=<redacted>)"
}

/** Ephemeral password / archive creation and edit fields; never exposes credentials in diagnostics. */
data class PortablePasswordEditDraft(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val issuer: String = "",
    val label: String = "",
    val tags: String = "",
    val thumbnail: String = "",
    val extraFields: List<PortableEmailField> = emptyList(),
    val customColumnsMode: Boolean = false,
    val archiveMode: Boolean = false,
    val archiveSizeBytes: Int = 0,
    val note: String = ""
) {
    constructor(
        url: String,
        username: String,
        password: String,
        issuer: String,
        label: String,
        tags: String,
        thumbnail: String,
        extraFields: List<PortableEmailField>,
        customColumnsMode: Boolean,
        archiveMode: Boolean,
        archiveSizeBytes: Int
    ) : this(
        url = url,
        username = username,
        password = password,
        issuer = issuer,
        label = label,
        tags = tags,
        thumbnail = thumbnail,
        extraFields = extraFields,
        customColumnsMode = customColumnsMode,
        archiveMode = archiveMode,
        archiveSizeBytes = archiveSizeBytes,
        note = ""
    )

    fun withoutPassword(): PortablePasswordEditDraft = copy(
        password = "",
        extraFields = extraFields.map { field ->
            if (field.kind == PortableEmailField.KIND_SECRET) field.copy(value = "") else field
        }
    )
    override fun toString(): String = "PortablePasswordEditDraft(redacted)"
}

/** Ephemeral email account creation/editing draft shared across Desktop, Android and iOS. */
data class PortableEmailAccountDraft(
    val name: String = "",
    val address: String = "",
    val protocol: Int = 0,
    val incomingHost: String = "",
    val incomingPort: String = "993",
    val incomingEncryption: Int = 0,
    val incomingAuth: String = "1",
    val incomingUser: String = "",
    val incomingPassword: String = "",
    val outgoingHost: String = "",
    val outgoingPort: String = "465",
    val outgoingEncryption: Int = 0,
    val outgoingAuth: String = "1",
    val outgoingUser: String = "",
    val outgoingPassword: String = "",
    val tags: String = "",
    val thumbnail: String = ""
) {
    constructor(
        name: String,
        address: String,
        protocol: Int,
        incomingHost: String,
        incomingPort: String,
        incomingEncryption: Int,
        incomingAuth: String,
        incomingUser: String,
        incomingPassword: String,
        outgoingHost: String,
        outgoingPort: String,
        outgoingEncryption: Int,
        outgoingAuth: String,
        outgoingUser: String,
        outgoingPassword: String,
        tags: String
    ) : this(
        name = name,
        address = address,
        protocol = protocol,
        incomingHost = incomingHost,
        incomingPort = incomingPort,
        incomingEncryption = incomingEncryption,
        incomingAuth = incomingAuth,
        incomingUser = incomingUser,
        incomingPassword = incomingPassword,
        outgoingHost = outgoingHost,
        outgoingPort = outgoingPort,
        outgoingEncryption = outgoingEncryption,
        outgoingAuth = outgoingAuth,
        outgoingUser = outgoingUser,
        outgoingPassword = outgoingPassword,
        tags = tags,
        thumbnail = ""
    )

    fun withoutSecrets(): PortableEmailAccountDraft = copy(incomingPassword = "", outgoingPassword = "")
    override fun toString(): String = "PortableEmailAccountDraft(redacted)"
}

/** Ephemeral manual OTP / Note / SSH-key entry draft for native-compatible Compose editors. */
data class PortableEntryDraft(
    val kind: String = "TOTP",
    val issuer: String = "",
    val label: String = "",
    val hiddenLabel: String = "",
    val secret: String = "",
    val digits: String = "6",
    val period: String = "30",
    val counter: String = "0",
    val algorithm: String = "SHA1",
    val pin: String = "",
    val publicKey: String = "",
    val privateKey: String = "",
    val sshHost: String = "",
    val sshPort: String = "",
    val sshUsername: String = "",
    val sshPassword: String = "",
    val tags: String = "",
    val thumbnail: String = "",
    val clearHiddenLabel: Boolean = false,
    val clearPin: Boolean = false,
    val clearSshPassword: Boolean = false
) {
    fun withoutSecrets(): PortableEntryDraft =
        copy(secret = "", pin = "", hiddenLabel = "", privateKey = "", sshPassword = "")

    override fun toString(): String = "PortableEntryDraft(kind=$kind, redacted)"
}

/** Toolkit-free mirror of `PasswordImport.Mode` for `:ui-foundation`. */
enum class PortablePasswordImportMode {
    INDIVIDUAL,
    ARCHIVE
}

/** Toolkit-free mirror of `Constants.CardLayouts` for `:ui-foundation`. */
enum class PortableCardLayout {
    COMPACT,
    DEFAULT,
    FULL;

    val cardVerticalPaddingDp: Int
        get() = if (this == COMPACT) 8 else 12

    val codeBelowIdentity: Boolean
        get() = this == FULL

    val truncatedLabelMaxLines: Int
        get() = if (this == FULL) 2 else 1

    val thumbnailSizeDp: Float
        get() = when (this) {
            COMPACT -> 32f
            DEFAULT -> 40f
            FULL -> 44f
        }

    fun effectiveThumbnailSizeDp(customSizeDp: Int): Float {
        val normalized = normalizeThumbnailSizeDp(customSizeDp)
        return if (normalized > 0) normalized.toFloat() else thumbnailSizeDp
    }

    companion object {
        val THUMBNAIL_SIZE_OPTIONS: List<Int> = listOf(0, 24, 32, 40, 48, 64)

        fun normalizeThumbnailSizeDp(value: Int): Int =
            if (value <= 0) 0 else value.coerceIn(20, 96)

        fun thumbnailSizeLabel(value: Int): String {
            val normalized = normalizeThumbnailSizeDp(value)
            return if (normalized <= 0) "Default" else "$normalized dp"
        }
    }
}

/** Toolkit-free mirror of `Constants.LabelDisplay` shared across `:ui-model` and `:ui-foundation`. */
enum class PortableLabelDisplay {
    TRUNCATE,
    SCROLL,
    MULTILINE;

    val scrollLabels: Boolean
        get() = this == SCROLL

    fun labelMaxLines(layout: PortableCardLayout): Int = when (this) {
        MULTILINE -> 10
        SCROLL -> 1
        TRUNCATE -> layout.truncatedLabelMaxLines
    }
}

/** Toolkit-free mirror of portable card single/double-tap actions across Desktop and iOS. */
enum class PortableTapMode {
    NOTHING,
    REVEAL,
    COPY;

    companion object {
        val LABEL_SIZE_OPTIONS: List<Int> = listOf(0, 14, 16, 18, 20, 24)
        val REVEAL_TIMEOUT_OPTIONS: List<Int> = listOf(0, 15, 30, 60, 120)

        fun normalizeLabelSizeSp(value: Int): Int =
            if (value <= 0) 0 else value.coerceIn(12, 24)

        fun normalizeRevealTimeoutSeconds(value: Int): Int =
            if (value <= 0) 0 else value.coerceIn(5, 600)

        fun revealTimeoutLabel(seconds: Int): String =
            if (seconds <= 0) "Stay visible until hidden" else "${seconds}s"

        fun fromName(raw: String?, fallback: PortableTapMode = NOTHING): PortableTapMode {
            val normalized = raw?.trim()?.uppercase() ?: return fallback
            return values().firstOrNull { it.name == normalized } ?: fallback
        }
    }
}

/** Complete portable card layout and interaction configuration saved from [SharedCardLayoutDialog]. */
data class PortableCardDisplaySelection(
    val layout: PortableCardLayout,
    val hideIssuer: Boolean,
    val showPreviousToken: Boolean,
    val tokenGroupSize: Int?,
    val showIndividualCountdowns: Boolean,
    val labelDisplay: PortableLabelDisplay,
    val showThumbnails: Boolean,
    val labelSizeSp: Int,
    val highlightExpiringTokens: Boolean,
    val tapSingle: PortableTapMode,
    val tapDouble: PortableTapMode,
    val hideGlobalTimeout: Boolean = false,
    val tapToRevealTimeoutSeconds: Int = 0,
    val thumbnailSizeDp: Int = 0,
    val focusSearchOnStart: Boolean = false,
    val minimizeOnCopy: Boolean = false
) {
    constructor(
        layout: PortableCardLayout,
        hideIssuer: Boolean,
        showPreviousToken: Boolean,
        tokenGroupSize: Int?,
        showIndividualCountdowns: Boolean,
        labelDisplay: PortableLabelDisplay,
        showThumbnails: Boolean,
        labelSizeSp: Int,
        highlightExpiringTokens: Boolean,
        tapSingle: PortableTapMode,
        tapDouble: PortableTapMode,
        hideGlobalTimeout: Boolean,
        tapToRevealTimeoutSeconds: Int
    ) : this(
        layout = layout,
        hideIssuer = hideIssuer,
        showPreviousToken = showPreviousToken,
        tokenGroupSize = tokenGroupSize,
        showIndividualCountdowns = showIndividualCountdowns,
        labelDisplay = labelDisplay,
        showThumbnails = showThumbnails,
        labelSizeSp = labelSizeSp,
        highlightExpiringTokens = highlightExpiringTokens,
        tapSingle = tapSingle,
        tapDouble = tapDouble,
        hideGlobalTimeout = hideGlobalTimeout,
        tapToRevealTimeoutSeconds = tapToRevealTimeoutSeconds,
        thumbnailSizeDp = 0,
        focusSearchOnStart = false,
        minimizeOnCopy = false
    )

    constructor(
        layout: PortableCardLayout,
        hideIssuer: Boolean,
        showPreviousToken: Boolean,
        tokenGroupSize: Int?,
        showIndividualCountdowns: Boolean,
        labelDisplay: PortableLabelDisplay,
        showThumbnails: Boolean,
        labelSizeSp: Int,
        highlightExpiringTokens: Boolean,
        tapSingle: PortableTapMode,
        tapDouble: PortableTapMode,
        hideGlobalTimeout: Boolean,
        tapToRevealTimeoutSeconds: Int,
        thumbnailSizeDp: Int,
        focusSearchOnStart: Boolean
    ) : this(
        layout = layout,
        hideIssuer = hideIssuer,
        showPreviousToken = showPreviousToken,
        tokenGroupSize = tokenGroupSize,
        showIndividualCountdowns = showIndividualCountdowns,
        labelDisplay = labelDisplay,
        showThumbnails = showThumbnails,
        labelSizeSp = labelSizeSp,
        highlightExpiringTokens = highlightExpiringTokens,
        tapSingle = tapSingle,
        tapDouble = tapDouble,
        hideGlobalTimeout = hideGlobalTimeout,
        tapToRevealTimeoutSeconds = tapToRevealTimeoutSeconds,
        thumbnailSizeDp = thumbnailSizeDp,
        focusSearchOnStart = focusSearchOnStart,
        minimizeOnCopy = false
    )
}

/**
 * Typed, lossless FairEmail account/identity field for shared multi-identity editing across
 * Android, Desktop and iOS.
 */
data class PortableEmailField(
    val name: String,
    val value: String,
    val kind: String = KIND_TEXT
) {
    override fun toString(): String = "PortableEmailField(redacted)"

    companion object {
        const val KIND_TEXT: String = "TEXT"
        const val KIND_SECRET: String = "SECRET"
        const val KIND_NUMBER: String = "NUMBER"
        const val KIND_BOOLEAN: String = "BOOLEAN"
        const val KIND_OBJECT: String = "OBJECT"
        const val KIND_ARRAY: String = "ARRAY"
        const val KIND_NULL: String = "NULL"

        /** Updates [index] and keeps legacy `pop` / `starttls` compatibility flags synchronized. */
        fun updated(fields: List<PortableEmailField>, index: Int, value: String): List<PortableEmailField> {
            val name = fields[index].name
            return fields.mapIndexed { i, old ->
                val linked = when {
                    name == "protocol" && old.name == "pop" -> (value == "1").toString()
                    name == "pop" && old.name == "protocol" -> if (value == "true") "1" else "0"
                    name == "encryption" && old.name == "starttls" -> (value == "1").toString()
                    name == "starttls" && old.name == "encryption" -> if (value == "true") "1" else "0"
                    else -> old.value
                }
                old.copy(value = if (i == index) value else linked)
            }
        }
    }
}

/** Comprehensive multi-identity email edit draft shared across Android, Desktop and iOS. */
data class PortableEmailEditDraft(
    val issuer: String = "",
    val label: String = "",
    val hiddenLabel: String = "",
    val tags: String = "",
    val thumbnail: String = "",
    val account: List<PortableEmailField> = emptyList(),
    val identities: List<List<PortableEmailField>> = emptyList()
) {
    fun withoutSecrets(): PortableEmailEditDraft = copy(
        account = account.map { if (it.name == "password") it.copy(value = "") else it },
        identities = identities.map { fields ->
            fields.map { if (it.name == "password") it.copy(value = "") else it }
        }
    )

    override fun toString(): String = "PortableEmailEditDraft(redacted)"
}

/** Masked credential-only email password/token draft; blank fields preserve stored credentials. */
data class PortableEmailPasswordDraft(
    val incoming: String = "",
    val outgoing: List<String> = emptyList()
) {
    fun withoutSecrets(): PortableEmailPasswordDraft =
        PortableEmailPasswordDraft(outgoing = List(outgoing.size) { "" })

    override fun toString(): String = "PortableEmailPasswordDraft(redacted)"
}

/** Transport connection metadata for one incoming account or SMTP identity (never carries secrets). */
data class PortableEmailConnectionDraft(
    val host: String = "",
    val port: String = "",
    val encryption: Int = 0,
    val username: String = "",
    val authentication: String? = null
) {
    override fun toString(): String = "PortableEmailConnectionDraft(redacted)"
}

/** Sender display name and email address for one SMTP identity. */
data class PortableEmailIdentityDraft(
    val name: String = "",
    val address: String = ""
) {
    override fun toString(): String = "PortableEmailIdentityDraft(redacted)"
}

/** Newly created additional SMTP identity staged inside [PortableEmailSettingsDraft]. */
data class PortableAddedEmailIdentityDraft(
    val identity: PortableEmailIdentityDraft = PortableEmailIdentityDraft(),
    val connection: PortableEmailConnectionDraft = PortableEmailConnectionDraft(
        port = "587",
        encryption = 1,
        authentication = "1"
    ),
    val password: String = ""
) {
    fun withoutSecret(): PortableAddedEmailIdentityDraft = copy(password = "")

    override fun toString(): String = "PortableAddedEmailIdentityDraft(redacted)"
}

/** Multi-identity email connection & sender settings draft, including staged SMTP additions, primary reassignment, and removals. */
data class PortableEmailSettingsDraft(
    val protocol: Int = 0,
    val incoming: PortableEmailConnectionDraft = PortableEmailConnectionDraft(),
    val outgoing: List<PortableEmailConnectionDraft> = emptyList(),
    val identities: List<PortableEmailIdentityDraft>? = null,
    val accountName: String? = null,
    val removedIdentities: Set<Int> = emptySet(),
    val removableIdentities: Set<Int> = emptySet(),
    val primaryIdentityIndex: Int? = null,
    val addedIdentities: List<PortableAddedEmailIdentityDraft> = emptyList()
) {
    constructor(
        protocol: Int,
        incoming: PortableEmailConnectionDraft,
        outgoing: List<PortableEmailConnectionDraft>,
        identities: List<PortableEmailIdentityDraft>?,
        accountName: String?,
        removedIdentities: Set<Int>,
        removableIdentities: Set<Int>
    ) : this(
        protocol = protocol,
        incoming = incoming,
        outgoing = outgoing,
        identities = identities,
        accountName = accountName,
        removedIdentities = removedIdentities,
        removableIdentities = removableIdentities,
        primaryIdentityIndex = null,
        addedIdentities = emptyList()
    )

    fun withoutSecrets(): PortableEmailSettingsDraft =
        copy(addedIdentities = addedIdentities.map { it.withoutSecret() })

    override fun toString(): String = "PortableEmailSettingsDraft(redacted)"
}

/** One secondary vault tool item in a grouped tools menu across Android, Desktop and iOS. */
data class PortableToolItem(
    val key: String,
    val label: String,
    val detail: String = "",
    val value: String = "",
    val enabled: Boolean = true,
    val interactive: Boolean = true
) {
    constructor(key: String, label: String) : this(
        key = key,
        label = label,
        detail = "",
        value = "",
        enabled = true,
        interactive = true
    )
}

/** A titled section of secondary vault tools (Import, Backup & export, Settings). */
data class PortableToolGroup(
    val title: String,
    val items: List<PortableToolItem>
)

/** Portable entry-action dialog kind shared across Android, Desktop and iOS. */
enum class PortableEntryDialogKind {
    PIN,
    COUNTER,
    DELETE
}

/** Open-source component and license notice rendered in the shared About dialog's Libraries tab. */
data class PortableLibraryNotice(
    val name: String,
    val license: String,
    val scope: String = ""
)

/** Toolkit-free catalog of third-party open-source dependencies across OfflineOTP's shared and native layers. */
object PortableLibraryCatalog {
    val bundled: List<PortableLibraryNotice> = listOf(
        PortableLibraryNotice(
            name = "Kotlin Standard Library & kotlinx-coroutines",
            license = "Apache License 2.0",
            scope = "Android, Desktop, iOS"
        ),
        PortableLibraryNotice(
            name = "JetBrains Compose Multiplatform & AndroidX Compose",
            license = "Apache License 2.0",
            scope = "Android, Desktop, iOS"
        ),
        PortableLibraryNotice(
            name = "Bouncy Castle Cryptographic APIs",
            license = "MIT License",
            scope = "Android, Desktop (Argon2id, AES-GCM, PBKDF2)"
        ),
        PortableLibraryNotice(
            name = "rPGP & Rust Crypto crates",
            license = "MIT OR Apache License 2.0",
            scope = "Android, Desktop, iOS (in-app OpenPGP)"
        ),
        PortableLibraryNotice(
            name = "Mozilla UniFFI",
            license = "MPL 2.0",
            scope = "Android, Desktop, iOS (OpenPGP FFI bindings)"
        ),
        PortableLibraryNotice(
            name = "ZXing (Zebra Crossing)",
            license = "Apache License 2.0",
            scope = "Android, Desktop, iOS (QR matrix encoding & decoding)"
        ),
        PortableLibraryNotice(
            name = "Yubico YubiKit",
            license = "Apache License 2.0",
            scope = "Android, Desktop (OATH & FIDO2 hardware keys)"
        ),
        PortableLibraryNotice(
            name = "usb4java & Java Native Access (JNA)",
            license = "MIT License / Apache License 2.0",
            scope = "Desktop (USB CCID/CTAP & native bindings)"
        )
    )
}


