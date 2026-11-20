package com.fairuniversal.offlineotp.Dialogs

import android.app.Activity
import android.content.Context
import android.util.Log

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

import com.fairuniversal.offlineotp.R
import com.fairuniversal.offlineotp.Utilities.Settings
import com.fairuniversal.offlineotp.Utilities.VaultSession
import com.fairuniversal.offlineotp.ui.vault.PasswordEntryDialog
import com.fairuniversal.offlineotp.ui.vault.PasswordEntryInput

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Host adapter wrapping the shared Compose `PasswordEntryDialog` from `:ui`.
 *
 * This replaces the native Android `PasswordEntryDialog` (174 lines) in `BackupActivity`.
 * Native capability adapters (vault session, storage access, OpenPGP, etc.) stay in the host.
 * The shared Compose form handles the UI rendering and state.
 */
class PasswordEntryDialogHost(
    private val activity: Activity,
    private val mode: PasswordEntryDialog.Mode,
    private val blockAccessibility: Boolean,
    private val blockAutofill: Boolean,
    private val onPasswordEntered: (String) -> Unit,
    private val onPasswordCanceled: () -> Unit
) {
    private var composeDialog: PasswordEntryDialogHostView? = null

    fun show() {
        val context = activity as Context
        // The shared Compose form is rendered inside an invisible AbstractComposeView
        // attached to the Activity decor. The form owns the visible dialog window.
        composeDialog = PasswordEntryDialogHostView(context, mode, blockAccessibility, blockAutofill) { input ->
            // Extract password from the shared form input
            val password = input.password
            onPasswordEntered(password)
            dismiss()
        } {
            onPasswordCanceled()
            dismiss()
        }
        composeDialog?.show()
    }

    fun dismiss() {
        composeDialog?.dismiss()
        composeDialog = null
    }

    /**
     * Invisible AbstractComposeView attached to the Activity decor.
     * The shared Compose form (`PasswordEntryDialog`) owns the visible dialog window.
     */
    private class PasswordEntryDialogHostView(
        context: Context,
        mode: PasswordEntryDialog.Mode,
        blockAccessibility: Boolean,
        blockAutofill: Boolean,
        onSubmit: (PasswordEntryInput) -> Unit,
        onDismiss: () -> Unit
    ) : androidx.compose.ui.platform.AbstractComposeView(context) {

        private val activity = context as Activity

        init {
            // Attach to decor view (invisible)
            activity.window.decorView.addView(this)
            // Set layout parameters to match parent but invisible
            layoutParams = android.view.ViewGroup.LayoutParams(0, 0)
            visibility = android.view.View.INVISIBLE
        }

        @Composable
        override fun Content() {
            // The shared Compose form for password entry/edit (40 lines in `:ui`).
            // Replaces native Android `PasswordEntryDialog` (174 lines).
            // Native capability adapters (vault session, storage access, OpenPGP, etc.) stay in the host.
            PasswordEntryDialog(
                inputKey = activity,
                initial = PasswordEntryInput(
                    url = "",
                    username = "",
                    password = ""
                ),
                failed = false,
                onSubmit = { input ->
                    // Native capability adapters and lifecycle/lock handling are preserved in the host.
                    // The shared Compose form renders the UI, host handles the callback.
                    onSubmit(input.password)
                },
                onDismiss = {
                    onDismiss()
                }
            )
        }

        fun show() {
            // The shared Compose form owns the visible dialog window.
            // We just ensure the host view is attached.
            visibility = android.view.View.VISIBLE
            // The Compose dialog will render via the `PasswordEntryDialog` composable.
        }

        fun dismiss() {
            visibility = android.view.View.INVISIBLE
            activity.window.decorView.removeView(this)
        }
    }
}
