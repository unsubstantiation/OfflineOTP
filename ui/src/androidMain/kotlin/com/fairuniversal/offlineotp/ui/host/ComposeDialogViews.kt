package com.fairuniversal.offlineotp.ui.host

import android.app.Activity
import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.fairuniversal.offlineotp.Utilities.EmailMaterial
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import com.fairuniversal.offlineotp.ui.theme.ForestTheme
import com.fairuniversal.offlineotp.ui.vault.BiometricEnableDialog
import com.fairuniversal.offlineotp.ui.vault.CheckboxConfirmationDialog
import com.fairuniversal.offlineotp.ui.vault.EmailDetailsDialog
import com.fairuniversal.offlineotp.ui.vault.EmailImportDialog
import com.fairuniversal.offlineotp.ui.vault.EncryptedRestoreDialog
import com.fairuniversal.offlineotp.ui.vault.EntryConfirmationDialog
import com.fairuniversal.offlineotp.ui.vault.EntryCopyDialog
import com.fairuniversal.offlineotp.ui.vault.EntryCounterDialog
import com.fairuniversal.offlineotp.ui.vault.EntryDetailsDialog
import com.fairuniversal.offlineotp.ui.vault.HideableDialog
import com.fairuniversal.offlineotp.ui.vault.MultiChoiceSelectionDialog
import com.fairuniversal.offlineotp.ui.vault.QrCodeDialog
import com.fairuniversal.offlineotp.ui.vault.SharedEntryPinDialog
import com.fairuniversal.offlineotp.ui.vault.TagsDialog
import com.fairuniversal.offlineotp.ui.vault.ThumbnailPickerDialog
import com.fairuniversal.offlineotp.ui.vault.VaultArtwork

/**
 * Android hosts for shared Compose dialogs that are also used by the desktop and iOS clients.
 *
 * A Material 3 `AlertDialog` already owns its own platform window. These hosts therefore do not
 * wrap the form in another native window: they attach an invisible composition to the Activity
 * decor and let the shared form own the visible prompt. The host keeps only a `dismiss()` command
 * for native lifecycle/lock handling; every form callback goes back to the app, which must
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

/** Shared mOTP entry PIN dialog; the host re-resolves the live entry before persisting. */
class PinEntryComposeView(
    activity: Activity,
    private val title: String,
    private val inputLabel: String,
    private val confirmLabel: String,
    private val dismissLabel: String,
    private val initialPin: String,
    private val onSubmit: (String) -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            SharedEntryPinDialog(
                entryId = 0L,
                title = title,
                inputLabel = inputLabel,
                confirmLabel = confirmLabel,
                dismissLabel = dismissLabel,
                maxLength = InputPolicy.MAX_TAG_CHARS,
                initialValue = initialPin,
                onSubmit = onSubmit,
                onDismiss = onDismiss,
                inputKey = this@PinEntryComposeView
            )
        }
    }
}

/** Shared HOTP counter editor; validation stays host-supplied. */
class CounterEntryComposeView(
    activity: Activity,
    private val initialCounter: String,
    private val title: String,
    private val inputLabel: String,
    private val confirmLabel: String,
    private val dismissLabel: String,
    private val isValid: (String) -> Boolean,
    private val onSubmit: (String) -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            EntryCounterDialog(
                inputKey = this@CounterEntryComposeView,
                initialCounter = initialCounter,
                title = title,
                inputLabel = inputLabel,
                confirmLabel = confirmLabel,
                dismissLabel = dismissLabel,
                isValid = isValid,
                onSubmit = onSubmit,
                onDismiss = onDismiss
            )
        }
    }
}

/** Shared thumbnail picker; images load lazily through the host's bounded bitmap loader. */
class ThumbnailPickerComposeView(
    activity: Activity,
    private val title: String,
    private val selected: String?,
    private val unchangedLabel: String,
    artworkLoader: ((String) -> Bitmap?)? = null,
    private val onSelect: (String?) -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    private val artwork: VaultArtwork? = artworkLoader?.let { loader ->
        VaultArtwork { name -> loader(name)?.asImageBitmap() }
    }

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            ThumbnailPickerDialog(
                title = title,
                selected = selected,
                artwork = artwork,
                unchangedLabel = unchangedLabel,
                onSelect = onSelect,
                onDismiss = onDismiss
            )
        }
    }
}

/** Shared tag picker and creator; host commits validated tags to the live entry. */
class TagsComposeView(
    activity: Activity,
    private val title: String,
    private val newTagLabel: String,
    private val addTagLabel: String,
    private val confirmLabel: String,
    private val dismissLabel: String,
    private val initialStates: Map<String, Boolean>,
    private val tagCounts: Map<String, Int>,
    private val onTagCreated: (String) -> Unit,
    private val onSelectionConfirmed: (Map<String, Boolean>) -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            TagsDialog(
                inputKey = this@TagsComposeView,
                initialStates = initialStates,
                onTagCreated = onTagCreated,
                onSelectionConfirmed = onSelectionConfirmed,
                onDismiss = onDismiss,
                title = title,
                newTagLabel = newTagLabel,
                addTagLabel = addTagLabel,
                confirmLabel = confirmLabel,
                dismissLabel = dismissLabel,
                maxTagChars = InputPolicy.MAX_TAG_CHARS,
                tagCounts = tagCounts
            )
        }
    }
}

/** Shared dismissible warning with a persistent "don't show again" setting callback. */
class HideableNoticeComposeView(
    activity: Activity,
    private val title: String,
    private val message: String,
    private val dontShowAgainLabel: String,
    private val confirmLabel: String,
    private val initialHidden: Boolean,
    private val onHiddenChanged: (Boolean) -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            HideableDialog(
                inputKey = this@HideableNoticeComposeView,
                title = title,
                message = message,
                dontShowAgainLabel = dontShowAgainLabel,
                confirmLabel = confirmLabel,
                initialHidden = initialHidden,
                onHiddenChanged = onHiddenChanged,
                onDismiss = onDismiss
            )
        }
    }
}

/** Shared chooser for named entry fields or import options; never holds secret values. */
class EntryCopyComposeView(
    activity: Activity,
    private val title: String,
    private val cancelLabel: String,
    private val fields: List<String>,
    private val onSelect: (Int) -> Unit,
    private val onDismiss: () -> Unit,
    private val message: String? = null
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            EntryCopyDialog(
                fields = fields,
                onSelect = onSelect,
                onDismiss = onDismiss,
                title = title,
                cancelLabel = cancelLabel,
                message = message
            )
        }
    }
}

/** Shared FairEmail connection-details dialog; rows are computed on demand by the host. */
class EmailDetailsComposeView(
    activity: Activity,
    private val rowsProvider: (Boolean) -> List<EmailMaterial.Detail>?,
    private val onDismiss: () -> Unit,
    private val onEditSettings: (() -> Unit)? = null
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        var secretsVisible by remember { mutableStateOf(true) }
        ForestTheme {
            EmailDetailsDialog(
                rows = rowsProvider(secretsVisible),
                secretsVisible = secretsVisible,
                onToggleSecrets = { secretsVisible = !secretsVisible },
                onDismiss = onDismiss,
                onEditSettings = onEditSettings
            )
        }
    }
}

/** Shared read-only entry detail dialog. */
class EntryDetailsComposeView(
    activity: Activity,
    private val title: String,
    private val body: String,
    private val confirmLabel: String,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            EntryDetailsDialog(
                title = title,
                body = body,
                confirmLabel = confirmLabel,
                onDismiss = onDismiss
            )
        }
    }
}

/** Shared FairEmail encrypted backup password prompt; host owns and zeroes the staged bytes. */
class EmailImportPasswordComposeView(
    activity: Activity,
    private val onRead: (String) -> Boolean,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    private var failed by mutableStateOf(false)

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            EmailImportDialog(
                inputKey = this@EmailImportPasswordComposeView,
                busy = false,
                count = null,
                failed = failed,
                onRead = { password ->
                    val succeeded = onRead(password)
                    if (!succeeded) failed = true
                },
                onConfirm = {},
                onDismiss = onDismiss
            )
        }
    }
}

/** Shared multi-choice checklist dialog for account/record export selection. */
class MultiChoiceSelectionComposeView(
    activity: Activity,
    private val title: String,
    private val message: String,
    private val items: List<String>,
    private val confirmLabel: String,
    private val dismissLabel: String,
    private val onConfirm: (Set<Int>) -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            MultiChoiceSelectionDialog(
                inputKey = this@MultiChoiceSelectionComposeView,
                title = title,
                message = message,
                items = items,
                confirmLabel = confirmLabel,
                dismissLabel = dismissLabel,
                onConfirm = onConfirm,
                onDismiss = onDismiss
            )
        }
    }
}

/** Shared QR-code presentation dialog; detaches on vault lock so the bitmap never lingers. */
class QrCodeComposeView(
    activity: Activity,
    private val title: String,
    bitmap: Bitmap,
    private val confirmLabel: String,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    private val qrBitmap = bitmap.asImageBitmap()

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            QrCodeDialog(
                title = title,
                qrBitmap = qrBitmap,
                confirmLabel = confirmLabel,
                onDismiss = onDismiss
            )
        }
    }
}

/** Shared two-button confirmation dialog with an explicit checkbox option (e.g. hardware touch). */
class CheckboxConfirmationComposeView(
    activity: Activity,
    private val title: String,
    private val message: String,
    private val checkboxLabel: String,
    private val confirmLabel: String,
    private val dismissLabel: String,
    private val onConfirm: (Boolean) -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            CheckboxConfirmationDialog(
                inputKey = this@CheckboxConfirmationComposeView,
                title = title,
                message = message,
                checkboxLabel = checkboxLabel,
                confirmLabel = confirmLabel,
                dismissLabel = dismissLabel,
                onConfirm = onConfirm,
                onDismiss = onDismiss
            )
        }
    }
}

/** Shared password prompt for encrypted backup restore; never saves password state. */
class EncryptedRestorePasswordComposeView(
    activity: Activity,
    private val onDecode: (String) -> Unit,
    private val onDismiss: () -> Unit,
    private val decodeDescription: String = "Open a password-encrypted OfflineOTP backup. You will confirm before entries are changed."
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            EncryptedRestoreDialog(
                inputKey = this@EncryptedRestorePasswordComposeView,
                busy = false,
                count = null,
                failed = false,
                onDecode = onDecode,
                onConfirm = {},
                onDismiss = onDismiss,
                decodeDescription = decodeDescription
            )
        }
    }
}
