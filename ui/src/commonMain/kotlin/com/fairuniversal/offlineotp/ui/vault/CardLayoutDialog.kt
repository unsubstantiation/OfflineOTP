package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.Composable
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.ui.portable.PortableCardLayout

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
    onSaveCardDisplay: ((Constants.CardLayouts, Boolean, Boolean, Int?, Boolean) -> Unit)? = null
) {
    val layouts = Constants.CardLayouts.values()
    val initial = when (current) {
        Constants.CardLayouts.DEFAULT -> PortableCardLayout.DEFAULT
        Constants.CardLayouts.COMPACT -> PortableCardLayout.COMPACT
        Constants.CardLayouts.FULL -> PortableCardLayout.FULL
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
        } else null
    )
}
