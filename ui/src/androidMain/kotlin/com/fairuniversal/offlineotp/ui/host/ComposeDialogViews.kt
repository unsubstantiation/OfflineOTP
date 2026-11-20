package com.fairuniversal.offlineotp.ui.host

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.fairuniversal.offlineotp.ui.theme.ForestTheme
import com.fairuniversal.offlineotp.ui.vault.BiometricEnableDialog
import com.fairuniversal.offlineotp.ui.vault.EntryConfirmationDialog

/**
 * Android hosts for shared Compose dialogs that are also used by the desktop and iOS clients.
 *
 * A Material 3 `AlertDialog` already owns its own platform dialog window. These hosts therefore do
 * not wrap the form in another Android `Dialog`: they attach an invisible composition to the
 * Activity decor and let the shared form own the visible dialog. The host keeps only a `dismiss()`
 * command for native lifecycle/lock handling; every form callback goes back to the app, which must
 * re-check the live session before acting. No secret, key or vault entry is retained in the view.
 *
 * `DisposeOnDetachedFromWindow` is deliberate: the composition is removed when the host is
 * dismissed or the Activity window goes away, so it cannot outlive the session that opened it.
 */
abstract class ComposeDialogHost(private val activity: Activity) : AbstractComposeView(activity) {

    private var attachedTo: ViewGroup? = null

    /** Adds the composition to the Activity window. Calling it twice is harmless. */
    fun show() {
        val decor = attachedTo ?: (activity.getWindow().decorView as? ViewGroup ?: return)
        if (attachedTo == null) {
            decor.addView(this, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            attachedTo = decor
        }
    }

    /** Removes the composition, which also closes the shared dialog's own window. */
    fun dismiss() {
        attachedTo?.removeView(this)
        attachedTo = null
    }
}

/** Shared biometric-enable password form; the host owns verification and key-store access. */
class BiometricEnableComposeView(
    activity: Activity,
    private val title: String,
    private val detail: String,
    private val passwordLabel: String,
    private val confirmLabel: String,
    private val cancelLabel: String,
    private val onConfirm: (String) -> String?,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            BiometricEnableDialog(
                title = title,
                detail = detail,
                passwordLabel = passwordLabel,
                confirmLabel = confirmLabel,
                cancelLabel = cancelLabel,
                initialError = null,
                onConfirm = onConfirm,
                onDismiss = onDismiss
            )
        }
    }
}

/** Shared delete confirmation; the host resolves the pending entry again before removing it. */
class DeleteConfirmationComposeView(
    activity: Activity,
    private val title: String,
    private val message: String,
    private val confirmLabel: String,
    private val dismissLabel: String,
    private val onConfirm: () -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            EntryConfirmationDialog(
                title = title,
                message = message,
                confirmLabel = confirmLabel,
                dismissLabel = dismissLabel,
                onConfirm = onConfirm,
                onDismiss = onDismiss
            )
        }
    }
}
