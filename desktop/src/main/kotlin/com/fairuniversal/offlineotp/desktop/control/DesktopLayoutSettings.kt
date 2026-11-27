package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.ui.portable.PortableCardDisplaySelection
import com.fairuniversal.offlineotp.ui.portable.PortableTapMode

/** Save before applying to the presenter; stale/expired requests cannot change the current view. */
class DesktopLayoutSettings(
    private val state: DesktopVaultState,
    private val read: () -> Constants.CardLayouts,
    private val write: (Constants.CardLayouts) -> Boolean,
    private val readHideIssuer: () -> Boolean = { false },
    private val writeHideIssuer: (Boolean) -> Boolean = { true },
    private val readShowPreviousToken: () -> Boolean = { true },
    private val writeShowPreviousToken: (Boolean) -> Boolean = { true },
    private val readTokenGroupSize: () -> Int? = { null },
    private val writeTokenGroupSize: (Int?) -> Boolean = { true },
    private val readShowIndividualTimeouts: () -> Boolean = { true },
    private val writeShowIndividualTimeouts: (Boolean) -> Boolean = { true },
    private val readLabelDisplay: () -> Constants.LabelDisplay = { Constants.LabelDisplay.TRUNCATE },
    private val writeLabelDisplay: (Constants.LabelDisplay) -> Boolean = { true },
    private val readShowThumbnails: () -> Boolean = { true },
    private val writeShowThumbnails: (Boolean) -> Boolean = { true },
    private val readLabelSizeSp: () -> Int = { 0 },
    private val writeLabelSizeSp: (Int) -> Boolean = { true },
    private val readHighlightToken: () -> Boolean = { true },
    private val writeHighlightToken: (Boolean) -> Boolean = { true },
    private val readTapSingle: () -> PortableTapMode = { PortableTapMode.REVEAL },
    private val writeTapSingle: (PortableTapMode) -> Boolean = { true },
    private val readTapDouble: () -> PortableTapMode = { PortableTapMode.NOTHING },
    private val writeTapDouble: (PortableTapMode) -> Boolean = { true },
    private val readHideGlobalTimeout: () -> Boolean = { false },
    private val writeHideGlobalTimeout: (Boolean) -> Boolean = { true },
    private val readTapToRevealTimeout: () -> Int = { 0 },
    private val writeTapToRevealTimeout: (Int) -> Boolean = { true },
    private val readThumbnailSizeDp: () -> Int = { 0 },
    private val writeThumbnailSizeDp: (Int) -> Boolean = { true },
    private val readFocusSearchOnStart: () -> Boolean = { false },
    private val writeFocusSearchOnStart: (Boolean) -> Boolean = { true },
    private val readMinimizeOnCopy: () -> Boolean = { false },
    private val writeMinimizeOnCopy: (Boolean) -> Boolean = { true },
    private val writeSelection: ((PortableCardDisplaySelection) -> Boolean)? = null
) {
    class Request internal constructor(
        val current: Constants.CardLayouts,
        val hideIssuer: Boolean = false,
        val showPreviousToken: Boolean = true,
        val tokenGroupSize: Int? = null,
        val showIndividualTimeouts: Boolean = true,
        val labelDisplay: Constants.LabelDisplay = Constants.LabelDisplay.TRUNCATE,
        val showThumbnails: Boolean = true,
        val labelSizeSp: Int = 0,
        val highlightToken: Boolean = true,
        val tapSingle: PortableTapMode = PortableTapMode.REVEAL,
        val tapDouble: PortableTapMode = PortableTapMode.NOTHING,
        val hideGlobalTimeout: Boolean = false,
        val tapToRevealTimeoutSeconds: Int = 0,
        val thumbnailSizeDp: Int = 0,
        val focusSearchOnStart: Boolean = false,
        val minimizeOnCopy: Boolean = false
    ) {
        var failed = false
            internal set
    }
    var pending: Request? = null
        private set
    private var closed = false
    fun open() {
        if (!closed && !state.isLocked) {
            pending = Request(
                read(),
                readHideIssuer(),
                readShowPreviousToken(),
                readTokenGroupSize(),
                readShowIndividualTimeouts(),
                readLabelDisplay(),
                readShowThumbnails(),
                readLabelSizeSp(),
                readHighlightToken(),
                readTapSingle(),
                readTapDouble(),
                readHideGlobalTimeout(),
                readTapToRevealTimeout(),
                readThumbnailSizeDp(),
                readFocusSearchOnStart(),
                readMinimizeOnCopy()
            )
        }
    }
    fun dismiss(request: Request) { if (pending === request) pending = null }
    fun close() { closed = true; pending = null }
    fun submit(request: Request, layout: Constants.CardLayouts, active: () -> Boolean,
               apply: (Constants.CardLayouts) -> Unit): Boolean {
        if (!valid(request) || !active() || !valid(request)) return false
        val saved = try { write(layout) } catch (_: Exception) { false }
        // Check the old deadline even on failure, before touching a dialog or live appearance.
        // The guard may synchronously close/replace the request, so check identity afterwards.
        if (!active() || !valid(request)) return false
        if (!saved) { request.failed = true; return false }
        pending = null; apply(layout); return true
    }
    fun submit(
        request: Request,
        layout: Constants.CardLayouts,
        hideIssuer: Boolean,
        active: () -> Boolean,
        apply: (Constants.CardLayouts, Boolean) -> Unit
    ): Boolean {
        if (!valid(request) || !active() || !valid(request)) return false
        val savedLayout = try { write(layout) } catch (_: Exception) { false }
        val savedHide = if (savedLayout) try { writeHideIssuer(hideIssuer) } catch (_: Exception) { false } else false
        if (!active() || !valid(request)) return false
        if (!savedLayout || !savedHide) { request.failed = true; return false }
        pending = null; apply(layout, hideIssuer); return true
    }
    fun submit(
        request: Request,
        layout: Constants.CardLayouts,
        hideIssuer: Boolean,
        showPreviousToken: Boolean,
        tokenGroupSize: Int?,
        active: () -> Boolean,
        apply: (Constants.CardLayouts, Boolean, Boolean, Int?) -> Unit
    ): Boolean {
        if (!valid(request) || !active() || !valid(request)) return false
        val savedLayout = try { write(layout) } catch (_: Exception) { false }
        val savedHide = if (savedLayout) try { writeHideIssuer(hideIssuer) } catch (_: Exception) { false } else false
        val savedPrev = if (savedHide) try { writeShowPreviousToken(showPreviousToken) } catch (_: Exception) { false } else false
        val savedGroup = if (savedPrev) try { writeTokenGroupSize(tokenGroupSize) } catch (_: Exception) { false } else false
        if (!active() || !valid(request)) return false
        if (!savedGroup) { request.failed = true; return false }
        pending = null; apply(layout, hideIssuer, showPreviousToken, tokenGroupSize); return true
    }
    fun submit(
        request: Request,
        layout: Constants.CardLayouts,
        hideIssuer: Boolean,
        showPreviousToken: Boolean,
        tokenGroupSize: Int?,
        showIndividualTimeouts: Boolean,
        active: () -> Boolean,
        apply: (Constants.CardLayouts, Boolean, Boolean, Int?, Boolean) -> Unit
    ): Boolean {
        if (!valid(request) || !active() || !valid(request)) return false
        val savedLayout = try { write(layout) } catch (_: Exception) { false }
        val savedHide = if (savedLayout) try { writeHideIssuer(hideIssuer) } catch (_: Exception) { false } else false
        val savedPrev = if (savedHide) try { writeShowPreviousToken(showPreviousToken) } catch (_: Exception) { false } else false
        val savedGroup = if (savedPrev) try { writeTokenGroupSize(tokenGroupSize) } catch (_: Exception) { false } else false
        val savedTimeouts = if (savedGroup) try { writeShowIndividualTimeouts(showIndividualTimeouts) } catch (_: Exception) { false } else false
        if (!active() || !valid(request)) return false
        if (!savedTimeouts) { request.failed = true; return false }
        pending = null; apply(layout, hideIssuer, showPreviousToken, tokenGroupSize, showIndividualTimeouts); return true
    }
    fun submit(
        request: Request,
        layout: Constants.CardLayouts,
        hideIssuer: Boolean,
        showPreviousToken: Boolean,
        tokenGroupSize: Int?,
        showIndividualTimeouts: Boolean,
        labelDisplay: Constants.LabelDisplay,
        showThumbnails: Boolean,
        active: () -> Boolean,
        apply: (Constants.CardLayouts, Boolean, Boolean, Int?, Boolean, Constants.LabelDisplay, Boolean) -> Unit
    ): Boolean {
        if (!valid(request) || !active() || !valid(request)) return false
        val savedLayout = try { write(layout) } catch (_: Exception) { false }
        val savedHide = if (savedLayout) try { writeHideIssuer(hideIssuer) } catch (_: Exception) { false } else false
        val savedPrev = if (savedHide) try { writeShowPreviousToken(showPreviousToken) } catch (_: Exception) { false } else false
        val savedGroup = if (savedPrev) try { writeTokenGroupSize(tokenGroupSize) } catch (_: Exception) { false } else false
        val savedTimeouts = if (savedGroup) try { writeShowIndividualTimeouts(showIndividualTimeouts) } catch (_: Exception) { false } else false
        val savedLabel = if (savedTimeouts) try { writeLabelDisplay(labelDisplay) } catch (_: Exception) { false } else false
        val savedThumbs = if (savedLabel) try { writeShowThumbnails(showThumbnails) } catch (_: Exception) { false } else false
        if (!active() || !valid(request)) return false
        if (!savedThumbs) { request.failed = true; return false }
        pending = null
        apply(layout, hideIssuer, showPreviousToken, tokenGroupSize, showIndividualTimeouts, labelDisplay, showThumbnails)
        return true
    }
    fun submit(
        request: Request,
        layout: Constants.CardLayouts,
        hideIssuer: Boolean,
        showPreviousToken: Boolean,
        tokenGroupSize: Int?,
        showIndividualTimeouts: Boolean,
        labelDisplay: Constants.LabelDisplay,
        showThumbnails: Boolean,
        labelSizeSp: Int,
        highlightToken: Boolean,
        tapSingle: PortableTapMode,
        tapDouble: PortableTapMode,
        active: () -> Boolean,
        apply: (
            Constants.CardLayouts,
            Boolean,
            Boolean,
            Int?,
            Boolean,
            Constants.LabelDisplay,
            Boolean,
            Int,
            Boolean,
            PortableTapMode,
            PortableTapMode
        ) -> Unit
    ): Boolean {
        if (!valid(request) || !active() || !valid(request)) return false
        val savedLayout = try { write(layout) } catch (_: Exception) { false }
        val savedHide = if (savedLayout) try { writeHideIssuer(hideIssuer) } catch (_: Exception) { false } else false
        val savedPrev = if (savedHide) try { writeShowPreviousToken(showPreviousToken) } catch (_: Exception) { false } else false
        val savedGroup = if (savedPrev) try { writeTokenGroupSize(tokenGroupSize) } catch (_: Exception) { false } else false
        val savedTimeouts = if (savedGroup) try { writeShowIndividualTimeouts(showIndividualTimeouts) } catch (_: Exception) { false } else false
        val savedLabel = if (savedTimeouts) try { writeLabelDisplay(labelDisplay) } catch (_: Exception) { false } else false
        val savedThumbs = if (savedLabel) try { writeShowThumbnails(showThumbnails) } catch (_: Exception) { false } else false
        val savedSize = if (savedThumbs) try { writeLabelSizeSp(labelSizeSp) } catch (_: Exception) { false } else false
        val savedHighlight = if (savedSize) try { writeHighlightToken(highlightToken) } catch (_: Exception) { false } else false
        val savedSingle = if (savedHighlight) try { writeTapSingle(tapSingle) } catch (_: Exception) { false } else false
        val savedDouble = if (savedSingle) try { writeTapDouble(tapDouble) } catch (_: Exception) { false } else false
        if (!active() || !valid(request)) return false
        if (!savedDouble) { request.failed = true; return false }
        pending = null
        apply(
            layout,
            hideIssuer,
            showPreviousToken,
            tokenGroupSize,
            showIndividualTimeouts,
            labelDisplay,
            showThumbnails,
            labelSizeSp,
            highlightToken,
            tapSingle,
            tapDouble
        )
        return true
    }

    fun submit(
        request: Request,
        selection: PortableCardDisplaySelection,
        active: () -> Boolean,
        apply: (PortableCardDisplaySelection) -> Unit
    ): Boolean {
        if (!valid(request) || !active() || !valid(request)) return false
        if (writeSelection != null) {
            val saved = try { writeSelection.invoke(selection) } catch (_: Exception) { false }
            if (!active() || !valid(request)) return false
            if (!saved) { request.failed = true; return false }
            pending = null; apply(selection); return true
        }
        val resolvedLayout = Constants.CardLayouts.values()
            .firstOrNull { it.name == selection.layout.name } ?: Constants.CardLayouts.DEFAULT
        val resolvedLabelDisplay = Constants.LabelDisplay.values()
            .firstOrNull { it.name == selection.labelDisplay.name } ?: Constants.LabelDisplay.TRUNCATE
        val savedLayout = try { write(resolvedLayout) } catch (_: Exception) { false }
        val savedHide = if (savedLayout) try { writeHideIssuer(selection.hideIssuer) } catch (_: Exception) { false } else false
        val savedPrev = if (savedHide) try { writeShowPreviousToken(selection.showPreviousToken) } catch (_: Exception) { false } else false
        val savedGroup = if (savedPrev) try { writeTokenGroupSize(selection.tokenGroupSize) } catch (_: Exception) { false } else false
        val savedTimeouts = if (savedGroup) try { writeShowIndividualTimeouts(selection.showIndividualCountdowns) } catch (_: Exception) { false } else false
        val savedLabel = if (savedTimeouts) try { writeLabelDisplay(resolvedLabelDisplay) } catch (_: Exception) { false } else false
        val savedThumbs = if (savedLabel) try { writeShowThumbnails(selection.showThumbnails) } catch (_: Exception) { false } else false
        val savedSize = if (savedThumbs) try { writeLabelSizeSp(selection.labelSizeSp) } catch (_: Exception) { false } else false
        val savedHighlight = if (savedSize) try { writeHighlightToken(selection.highlightExpiringTokens) } catch (_: Exception) { false } else false
        val savedSingle = if (savedHighlight) try { writeTapSingle(selection.tapSingle) } catch (_: Exception) { false } else false
        val savedDouble = if (savedSingle) try { writeTapDouble(selection.tapDouble) } catch (_: Exception) { false } else false
        val savedGlobal = if (savedDouble) try { writeHideGlobalTimeout(selection.hideGlobalTimeout) } catch (_: Exception) { false } else false
        val savedRevealTimeout = if (savedGlobal) try { writeTapToRevealTimeout(selection.tapToRevealTimeoutSeconds) } catch (_: Exception) { false } else false
        val savedThumbSize = if (savedRevealTimeout) try { writeThumbnailSizeDp(selection.thumbnailSizeDp) } catch (_: Exception) { false } else false
        val savedFocusSearch = if (savedThumbSize) try { writeFocusSearchOnStart(selection.focusSearchOnStart) } catch (_: Exception) { false } else false
        val savedMinimize = if (savedFocusSearch) try { writeMinimizeOnCopy(selection.minimizeOnCopy) } catch (_: Exception) { false } else false
        if (!active() || !valid(request)) return false
        if (!savedMinimize) { request.failed = true; return false }
        pending = null
        apply(selection)
        return true
    }

    private fun valid(request: Request) = !closed && !state.isLocked && pending === request
}
