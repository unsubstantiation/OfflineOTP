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
    onDismiss: () -> Unit
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
        onDismiss = onDismiss
    )
}
