package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.ui.portable.PortableCardDisplaySelection
import com.fairuniversal.offlineotp.ui.portable.PortableCardLayout
import com.fairuniversal.offlineotp.ui.portable.PortableLabelDisplay
import com.fairuniversal.offlineotp.ui.portable.PortableTapMode

/** Thin `:ui` adapter mapping [Constants.CardLayouts] onto [SharedCardLayoutDialog] in `:ui-foundation`. */
@Composable
fun CardLayoutDialog(
    inputKey: Any,
    current: Constants.CardLayouts,
    failed: Boolean,
    onSave: (Constants.CardLayouts) -> Unit,
    onDismiss: () -> Unit,
    hideIssuer: Boolean? = null,
    onSaveAppearance: ((Constants.CardLayouts, Boolean) -> Unit)? = null,
    showPreviousToken: Boolean? = null,
    tokenGroupSize: Int? = null,
    onSaveDisplay: ((Constants.CardLayouts, Boolean, Boolean, Int?) -> Unit)? = null,
    showIndividualCountdowns: Boolean? = null,
    onSaveCardDisplay: ((Constants.CardLayouts, Boolean, Boolean, Int?, Boolean) -> Unit)? = null,
    labelDisplay: Constants.LabelDisplay? = null,
    showThumbnails: Boolean? = null,
    onSaveFullDisplay: ((Constants.CardLayouts, Boolean, Boolean, Int?, Boolean, Constants.LabelDisplay, Boolean) -> Unit)? = null,
    labelSizeSp: Int? = null,
    highlightExpiringTokens: Boolean? = null,
    tapSingle: PortableTapMode? = null,
    tapDouble: PortableTapMode? = null,
    onSaveCompleteDisplay: ((
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
    ) -> Unit)? = null,
    hideGlobalTimeout: Boolean? = null,
    tapToRevealTimeoutSeconds: Int? = null,
    thumbnailSizeDp: Int? = null,
    focusSearchOnStart: Boolean? = null,
    minimizeOnCopy: Boolean? = null,
    onSaveSelection: ((PortableCardDisplaySelection) -> Unit)? = null
) {
    val layouts = Constants.CardLayouts.values()
    val labelModes = Constants.LabelDisplay.values()
    val initial = when (current) {
        Constants.CardLayouts.DEFAULT -> PortableCardLayout.DEFAULT
        Constants.CardLayouts.COMPACT -> PortableCardLayout.COMPACT
        Constants.CardLayouts.FULL -> PortableCardLayout.FULL
    }
    val initialLabelDisplay = labelDisplay?.let { mode ->
        PortableLabelDisplay.values().firstOrNull { it.name == mode.name } ?: PortableLabelDisplay.TRUNCATE
    }
    SharedCardLayoutDialog(
        inputKey = inputKey,
        current = initial,
        failed = failed,
        onSave = { chosen ->
            onSave(layouts.firstOrNull { it.name == chosen.name } ?: Constants.CardLayouts.DEFAULT)
        },
        onDismiss = onDismiss,
        hideIssuer = hideIssuer,
        onSaveAppearance = if (onSaveAppearance != null) {
            { chosen, hidden ->
                val resolved = layouts.firstOrNull { it.name == chosen.name } ?: Constants.CardLayouts.DEFAULT
                onSaveAppearance(resolved, hidden)
            }
        } else null,
        showPreviousToken = showPreviousToken,
        tokenGroupSize = tokenGroupSize,
        onSaveDisplay = if (onSaveDisplay != null) {
            { chosen, hidden, showPrev, groupSize ->
                val resolved = layouts.firstOrNull { it.name == chosen.name } ?: Constants.CardLayouts.DEFAULT
                onSaveDisplay(resolved, hidden, showPrev, groupSize)
            }
        } else null,
        showIndividualCountdowns = showIndividualCountdowns,
        onSaveCardDisplay = if (onSaveCardDisplay != null) {
            { chosen, hidden, showPrev, groupSize, showIndividual ->
                val resolved = layouts.firstOrNull { it.name == chosen.name } ?: Constants.CardLayouts.DEFAULT
                onSaveCardDisplay(resolved, hidden, showPrev, groupSize, showIndividual)
            }
        } else null,
        labelDisplay = initialLabelDisplay,
        showThumbnails = showThumbnails,
        onSaveFullDisplay = if (onSaveFullDisplay != null) {
            { chosen, hidden, showPrev, groupSize, showIndividual, chosenLabel, showThumbs ->
                val resolved = layouts.firstOrNull { it.name == chosen.name } ?: Constants.CardLayouts.DEFAULT
                val resolvedLabel = labelModes.firstOrNull { it.name == chosenLabel.name } ?: Constants.LabelDisplay.TRUNCATE
                onSaveFullDisplay(resolved, hidden, showPrev, groupSize, showIndividual, resolvedLabel, showThumbs)
            }
        } else null,
        labelSizeSp = labelSizeSp,
        highlightExpiringTokens = highlightExpiringTokens,
        tapSingle = tapSingle,
        tapDouble = tapDouble,
        onSaveCompleteDisplay = if (onSaveCompleteDisplay != null) {
            { chosen, hidden, showPrev, groupSize, showIndividual, chosenLabel, showThumbs, chosenSize, highlight, singleTap, doubleTap ->
                val resolved = layouts.firstOrNull { it.name == chosen.name } ?: Constants.CardLayouts.DEFAULT
                val resolvedLabel = labelModes.firstOrNull { it.name == chosenLabel.name } ?: Constants.LabelDisplay.TRUNCATE
                onSaveCompleteDisplay(
                    resolved,
                    hidden,
                    showPrev,
                    groupSize,
                    showIndividual,
                    resolvedLabel,
                    showThumbs,
                    chosenSize,
                    highlight,
                    singleTap,
                    doubleTap
                )
            }
        } else null,
        hideGlobalTimeout = hideGlobalTimeout,
        tapToRevealTimeoutSeconds = tapToRevealTimeoutSeconds,
        thumbnailSizeDp = thumbnailSizeDp,
        focusSearchOnStart = focusSearchOnStart,
        minimizeOnCopy = minimizeOnCopy,
        onSaveSelection = onSaveSelection
    )
}
