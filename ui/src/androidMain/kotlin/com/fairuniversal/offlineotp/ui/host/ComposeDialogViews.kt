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
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.EmailMaterial
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import com.fairuniversal.offlineotp.Utilities.PasswordImport
import com.fairuniversal.offlineotp.ui.portable.PortableCardDisplaySelection
import com.fairuniversal.offlineotp.ui.portable.PortableCardLayout
import com.fairuniversal.offlineotp.ui.theme.ForestTheme
import com.fairuniversal.offlineotp.ui.unlock.HardwareEnrollmentDialog
import com.fairuniversal.offlineotp.ui.vault.ArchiveExportDialog
import com.fairuniversal.offlineotp.ui.vault.AutoLockDialog
import com.fairuniversal.offlineotp.ui.vault.AutoLockOptions
import com.fairuniversal.offlineotp.ui.vault.BiometricEnableDialog
import com.fairuniversal.offlineotp.ui.vault.BusyProgressDialog
import com.fairuniversal.offlineotp.ui.vault.CardLayoutDialog
import com.fairuniversal.offlineotp.ui.vault.CheckboxConfirmationDialog
import com.fairuniversal.offlineotp.ui.vault.EmailDetailsDialog
import com.fairuniversal.offlineotp.ui.vault.EmailImportDialog
import com.fairuniversal.offlineotp.ui.vault.EmailPasswordDialog
import com.fairuniversal.offlineotp.ui.vault.EmailPasswordInput
import com.fairuniversal.offlineotp.ui.vault.EmailSettingsDialog
import com.fairuniversal.offlineotp.ui.vault.EmailSettingsInput
import com.fairuniversal.offlineotp.ui.vault.EncryptedExportDialog
import com.fairuniversal.offlineotp.ui.vault.EncryptedRestoreDialog
import com.fairuniversal.offlineotp.ui.vault.EntryConfirmationDialog
import com.fairuniversal.offlineotp.ui.vault.EntryCopyDialog
import com.fairuniversal.offlineotp.ui.vault.EntryCounterDialog
import com.fairuniversal.offlineotp.ui.vault.EntryDetailsDialog
import com.fairuniversal.offlineotp.ui.vault.HardwareCopyDialog
import com.fairuniversal.offlineotp.ui.vault.HideableDialog
import com.fairuniversal.offlineotp.ui.vault.MultiChoiceSelectionDialog
import com.fairuniversal.offlineotp.ui.vault.OpenPgpExportDialog
import com.fairuniversal.offlineotp.ui.vault.PasswordChangeDialog
import com.fairuniversal.offlineotp.ui.vault.PasswordExportDialog
import com.fairuniversal.offlineotp.ui.vault.PasswordImportDialog
import com.fairuniversal.offlineotp.ui.vault.PreferencesImportDialog
import com.fairuniversal.offlineotp.ui.vault.QrCodeDialog
import com.fairuniversal.offlineotp.ui.vault.SharedEntryPinDialog
import com.fairuniversal.offlineotp.ui.vault.StorageLocationDialog
import com.fairuniversal.offlineotp.ui.vault.TagsDialog
import com.fairuniversal.offlineotp.ui.vault.ThumbnailPickerDialog
import com.fairuniversal.offlineotp.ui.vault.UriImportDialog
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
    private var dismissListener: (() -> Unit)? = null
    var dismissals: Int = 0
        private set

    /** Registers a callback fired when [dismiss] detaches an open composition. */
    fun setOnDismissListener(listener: () -> Unit) {
        dismissListener = listener
    }

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
        dismissals++
        val hadAttachment = attachedTo != null
        attachedTo?.removeView(this)
        attachedTo = null
        val listener = dismissListener
        dismissListener = null
        if (hadAttachment || listener != null) {
            listener?.invoke()
        }
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

    private var busy by mutableStateOf(false)
    private var operationError by mutableStateOf<String?>(null)

    fun updateOperation(pending: Boolean, error: String? = null) {
        if (dismissals > 0) return
        busy = pending
        operationError = error
    }

    fun submitPassword(password: String): String? {
        if (dismissals > 0 || busy) return null
        operationError = null
        return onConfirm(password)
    }

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
                onConfirm = ::submitPassword,
                onDismiss = onDismiss,
                busy = busy,
                operationError = operationError
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
    private val onEditSettings: (() -> Unit)? = null,
    private val onEditPasswords: (() -> Unit)? = null
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        var secretsVisible by remember { mutableStateOf(false) }
        ForestTheme {
            EmailDetailsDialog(
                rows = rowsProvider(secretsVisible),
                secretsVisible = secretsVisible,
                onToggleSecrets = { secretsVisible = !secretsVisible },
                onDismiss = onDismiss,
                onEditSettings = onEditSettings,
                onEditPasswords = onEditPasswords
            )
        }
    }
}

/** Shared non-secret email connection & sender identity settings dialog for Android. */
class EmailSettingsComposeView(
    activity: Activity,
    private val initial: EmailSettingsInput,
    private val onSubmit: (EmailSettingsInput) -> String?,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        var errorText by remember { mutableStateOf<String?>(null) }
        ForestTheme {
            EmailSettingsDialog(
                inputKey = initial,
                initial = initial,
                failed = errorText != null,
                errorText = errorText,
                onSubmit = { input ->
                    val error = onSubmit(input)
                    if (error == null) onDismiss() else errorText = error
                },
                onDismiss = onDismiss
            )
        }
    }
}

/** Shared masked credential-only email password/token editor dialog for Android. */
class EmailPasswordComposeView(
    activity: Activity,
    private val initial: EmailPasswordInput,
    private val onSubmit: (EmailPasswordInput) -> String?,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        var errorText by remember { mutableStateOf<String?>(null) }
        ForestTheme {
            EmailPasswordDialog(
                inputKey = initial,
                initial = initial,
                failed = errorText != null,
                errorText = errorText,
                onSubmit = { input ->
                    val error = onSubmit(input)
                    if (error == null) onDismiss() else errorText = error
                },
                onDismiss = onDismiss
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
    private var consumed = false

    fun readPassword(password: String) {
        if (consumed || dismissals > 0) return
        // Revoke reentrant/repeated success callbacks; a failed read explicitly permits retry.
        consumed = true
        val succeeded = onRead(password)
        if (!succeeded && dismissals == 0) { consumed = false; failed = true }
    }

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
                onRead = ::readPassword,
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
    val items: List<String>,
    private val confirmLabel: String,
    private val dismissLabel: String,
    private val onConfirm: (Set<Int>) -> Unit,
    private val onDismiss: () -> Unit,
    val initialSelected: Set<Int> = emptySet(),
    private val allowEmpty: Boolean = false
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    /** Direct entry point for headless host checks while Compose stubs remain inert. */
    fun confirmSelection(selected: Set<Int>) {
        onConfirm(selected)
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
                onDismiss = onDismiss,
                initialSelected = initialSelected,
                allowEmpty = allowEmpty
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

/** Shared portable-preferences import confirmation host; displays parsed setting counts before applying. */
class PreferencesImportComposeView(
    activity: Activity,
    private val count: Int,
    private val skipped: Int,
    private val applyDetail: String,
    private val onConfirm: () -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    private var consumed = false
    fun confirm() {
        if (consumed || dismissals > 0) return
        consumed = true
        onConfirm()
    }
    fun cancel() {
        if (consumed || dismissals > 0) return
        consumed = true
        onDismiss()
    }

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            PreferencesImportDialog(
                busy = false,
                count = count,
                skipped = skipped,
                failed = false,
                onConfirm = ::confirm,
                onDismiss = ::cancel,
                platformName = "Android",
                applyDetail = applyDetail
            )
        }
    }
}

/** Shared confirmed-password prompt for encrypted backup export; enforces minimum length and never saves secret state. */
class EncryptedExportComposeView(
    activity: Activity,
    private val minPasswordLength: Int,
    private val tooShortMessage: String,
    private val onConfirm: (String) -> String?,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    private var errorMessage by mutableStateOf<String?>(null)

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            EncryptedExportDialog(
                inputKey = this@EncryptedExportComposeView,
                busy = false,
                error = errorMessage,
                onSubmit = { first, second ->
                    errorMessage = when {
                        first.length < minPasswordLength -> tooShortMessage
                        first != second -> tooShortMessage
                        else -> onConfirm(first)
                    }
                },
                onDismiss = onDismiss
            )
        }
    }
}

/** Shared OTP/migration URI import prompt; masks seed URIs and keeps session validation in the host. */
class UriImportComposeView(
    activity: Activity,
    private val onChooseImage: (() -> Unit)? = null,
    private val onSubmit: (uri: String, allowAdditional: Boolean) -> String?,
    private val onDismiss: () -> Unit,
    private val onScanCamera: (() -> Unit)? = null
) : ComposeDialogHost(activity) {

    private var statusMessage by mutableStateOf<String?>(null)
    var collectingBatch by mutableStateOf(false)

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            UriImportDialog(
                inputKey = this@UriImportComposeView,
                message = statusMessage,
                collecting = collectingBatch,
                onChooseImage = onChooseImage,
                onScanCamera = onScanCamera,
                onSubmit = { uri, allowAdditional ->
                    statusMessage = onSubmit(uri, allowAdditional)
                },
                onDismiss = onDismiss
            )
        }
    }
}

/** Shared browser/password-file import preview and mode chooser; host owns file bytes and install. */
class PasswordImportComposeView(
    activity: Activity,
    private val onRead: (PasswordImport.Mode) -> Int?,
    private val onConfirm: () -> Boolean,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        var count by remember { mutableStateOf<Int?>(null) }
        var failed by remember { mutableStateOf(false) }
        ForestTheme {
            PasswordImportDialog(
                inputKey = this@PasswordImportComposeView,
                busy = false,
                count = count,
                failed = failed,
                onRead = { mode ->
                    val parsed = onRead(mode)
                    if (parsed != null) {
                        count = parsed
                        failed = false
                    } else {
                        count = null
                        failed = true
                    }
                },
                onConfirm = {
                    if (!onConfirm()) failed = true
                },
                onDismiss = onDismiss
            )
        }
    }
}

/** Shared browser-CSV snapshot group chooser; only dialect/column group counts enter the UI. */
class PasswordExportComposeView(
    activity: Activity,
    private val groups: List<String>,
    private val foreign: Int,
    private val archives: Int,
    private val onSelect: (Int) -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            PasswordExportDialog(
                groups = groups,
                foreign = foreign,
                archives = archives,
                onSelect = onSelect,
                onDismiss = onDismiss
            )
        }
    }
}

/** Shared stored-archive chooser; labels identify entries while secret bytes remain in the host. */
class ArchiveExportComposeView(
    activity: Activity,
    private val labels: List<String>,
    private val onSelect: (Int) -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            ArchiveExportDialog(
                labels = labels,
                onSelect = onSelect,
                onDismiss = onDismiss
            )
        }
    }
}

/** Shared two-step hardware seed-export chooser and confirmation; owns no vault Entry references. */
class HardwareCopyComposeView(
    activity: Activity,
    val labels: List<String>,
    private val transportNotice: String,
    private val requireTouchLabel: String,
    private val confirmLabel: String,
    private val dismissLabel: String,
    private val onConfirm: (index: Int, pin: CharArray, requireTouch: Boolean) -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    var selectedIndex by mutableStateOf(-1)
        private set
    var selecting by mutableStateOf(true)
        private set

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    fun selectEntry(index: Int) {
        if (index !in labels.indices) return
        selectedIndex = index
        selecting = false
    }

    fun confirmSelected(pin: CharArray = CharArray(0), requireTouch: Boolean = false) {
        if (selecting || selectedIndex !in labels.indices) {
            pin.fill('\u0000')
            return
        }
        onConfirm(selectedIndex, pin, requireTouch)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            HardwareCopyDialog(
                inputKey = this@HardwareCopyComposeView,
                labels = labels,
                selected = selectedIndex,
                selecting = selecting,
                confirming = !selecting && selectedIndex in labels.indices,
                message = null,
                onSelect = ::selectEntry,
                onSubmit = { pin -> confirmSelected(pin, requireTouch = false) },
                onDismiss = onDismiss,
                transportNotice = transportNotice,
                requireTouchLabel = requireTouchLabel,
                confirmLabel = confirmLabel,
                dismissLabel = dismissLabel,
                onSubmitWithTouch = { pin, requireTouch -> confirmSelected(pin, requireTouch) }
            )
        }
    }
}

/** Shared OpenPGP full-vault export confirmation over Android SAF-configured recipient key files. */
class OpenPgpExportComposeView(
    activity: Activity,
    val recipientNames: List<String>,
    private val maxRecipients: Int,
    private val onExport: () -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    fun confirmExport() {
        if (recipientNames.isNotEmpty()) onExport()
    }

    @Composable
    override fun Content() {
        ForestTheme {
            OpenPgpExportDialog(
                busy = false,
                recipientNames = recipientNames,
                error = null,
                onChooseKey = {},
                onClearKeys = {},
                onExport = ::confirmExport,
                onDismiss = onDismiss,
                allowManageKeys = false,
                maxRecipients = maxRecipients
            )
        }
    }
}

/** Shared read-only vault storage directory and managed-filenames summary dialog. */
class StorageLocationComposeView(
    activity: Activity,
    val directory: String,
    val filenames: List<String>,
    private val title: String,
    private val guidance: List<String>,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            StorageLocationDialog(
                directory = directory,
                filenames = filenames,
                onDismiss = onDismiss,
                title = title,
                guidance = guidance
            )
        }
    }
}

/** Shared modal progress/status dialog (non-dismissible unless [cancelLabel] and [onCancel] are provided). */
class BusyProgressComposeView(
    activity: Activity,
    val title: String,
    val message: String,
    val cancelLabel: String? = null,
    private val onCancel: (() -> Unit)? = null
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            BusyProgressDialog(
                title = title,
                message = message,
                cancelLabel = cancelLabel,
                onCancel = if (onCancel != null) {
                    {
                        onCancel.invoke()
                        dismiss()
                    }
                } else null
            )
        }
    }
}

/** Shared FIDO2 hardware-unlock enrollment confirmation & optional FIDO PIN dialog. */
class HardwareEnrollmentComposeView(
    activity: Activity,
    val title: String,
    val warningMessage: String,
    val pinLabel: String,
    val confirmLabel: String,
    val dismissLabel: String,
    private val onConfirm: (CharArray) -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            HardwareEnrollmentDialog(
                inputKey = this,
                busy = false,
                result = null,
                onSubmit = onConfirm,
                onDismiss = onDismiss,
                title = title,
                primaryWarning = warningMessage,
                secondaryWarning = null,
                pinLabel = pinLabel,
                submitLabel = confirmLabel,
                cancelLabel = dismissLabel
            )
        }
    }
}

/** Shared vault card layout, countdown, thumbnail, and tap-gesture configuration dialog. */
class CardDisplayComposeView(
    activity: Activity,
    val current: PortableCardDisplaySelection,
    private val failed: Boolean = false,
    private val onSave: (PortableCardDisplaySelection) -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    /** Direct entry point for headless host checks while Compose stubs remain inert. */
    fun saveSelection(selection: PortableCardDisplaySelection) {
        onSave(selection)
    }

    @Composable
    override fun Content() {
        val currentLayout = when (current.layout) {
            PortableCardLayout.COMPACT -> Constants.CardLayouts.COMPACT
            PortableCardLayout.FULL -> Constants.CardLayouts.FULL
            PortableCardLayout.DEFAULT -> Constants.CardLayouts.DEFAULT
        }
        val currentLabelDisplay = Constants.LabelDisplay.values()
            .firstOrNull { it.name == current.labelDisplay.name }
            ?: Constants.LabelDisplay.TRUNCATE
        ForestTheme {
            CardLayoutDialog(
                inputKey = this,
                current = currentLayout,
                failed = failed,
                onSave = {},
                onDismiss = onDismiss,
                hideIssuer = current.hideIssuer,
                showPreviousToken = current.showPreviousToken,
                tokenGroupSize = current.tokenGroupSize,
                showIndividualCountdowns = current.showIndividualCountdowns,
                labelDisplay = currentLabelDisplay,
                showThumbnails = current.showThumbnails,
                labelSizeSp = current.labelSizeSp,
                highlightExpiringTokens = current.highlightExpiringTokens,
                tapSingle = current.tapSingle,
                tapDouble = current.tapDouble,
                hideGlobalTimeout = current.hideGlobalTimeout,
                tapToRevealTimeoutSeconds = current.tapToRevealTimeoutSeconds,
                thumbnailSizeDp = current.thumbnailSizeDp,
                focusSearchOnStart = current.focusSearchOnStart,
                minimizeOnCopy = current.minimizeOnCopy,
                allowDefaultGrouping = false,
                onSaveSelection = onSave
            )
        }
    }
}

/** Shared inactivity auto-lock timeout & background-relock selector backed by [AutoLockOptions.seconds]. */
class AutoLockComposeView(
    activity: Activity,
    val currentSeconds: Int,
    val relockOnBackground: Boolean = false,
    private val failed: Boolean = false,
    private val onSave: (Int, Boolean) -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    /** Direct entry point for headless host checks while Compose stubs remain inert. */
    fun saveTimeout(seconds: Int, backgroundRelock: Boolean = relockOnBackground) {
        onSave(seconds, backgroundRelock)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            AutoLockDialog(
                inputKey = this,
                currentSeconds = currentSeconds,
                options = AutoLockOptions.seconds,
                failed = failed,
                onSave = { seconds -> onSave(seconds, relockOnBackground) },
                onDismiss = onDismiss,
                relockOnBackground = relockOnBackground,
                onSaveWithBackground = onSave
            )
        }
    }
}

/** Shared vault password rekey dialog backed by [PasswordChangeDialog]. */
class PasswordChangeComposeView(
    activity: Activity,
    private val maxFieldChars: Int = InputPolicy.MAX_TEXT_CHARS,
    private val requiresUnlock: Boolean = false,
    private val onSubmit: (String, String, String) -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {

    var busy: Boolean by mutableStateOf(false)
        private set
    var result: String? by mutableStateOf(null)
        private set
    var invalid: Boolean by mutableStateOf(false)
        private set
    var errorText: String? by mutableStateOf(null)
        private set
    private var revision: Int by mutableStateOf(0)

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    fun updateState(
        busy: Boolean = this.busy,
        result: String? = this.result,
        invalid: Boolean = this.invalid,
        errorText: String? = this.errorText,
        resetFields: Boolean = false
    ) {
        this.busy = busy
        this.result = result
        this.invalid = invalid
        this.errorText = errorText
        if (resetFields) revision++
    }

    /** Direct entry point for headless host checks while Compose stubs remain inert. */
    fun submitPasswordChange(current: String, next: String, confirmation: String) {
        if (!busy && result == null) {
            onSubmit(current, next, confirmation)
        }
    }

    @Composable
    override fun Content() {
        ForestTheme {
            PasswordChangeDialog(
                inputKey = this to revision,
                busy = busy,
                result = result,
                invalid = invalid,
                maxFieldChars = maxFieldChars,
                requiresUnlock = requiresUnlock,
                errorText = errorText,
                onSubmit = onSubmit,
                onDismiss = { if (!busy) onDismiss() }
            )
        }
    }
}




/** Android lifecycle seam for the same capability-filtered tools used by Desktop. */
class VaultToolsComposeView(
    activity: Activity,
    private val actions: com.fairuniversal.offlineotp.ui.vault.VaultActions,
    private val strings: com.fairuniversal.offlineotp.ui.vault.VaultStrings,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {
    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    fun selectTool(key: String) {
        val tool = com.fairuniversal.offlineotp.ui.vault.VaultTool.available(actions)
            .firstOrNull { it.name == key } ?: return
        onDismiss()
        tool.invoke(actions)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            com.fairuniversal.offlineotp.ui.vault.SharedVaultToolsDialog(
                title = strings.tools,
                groups = com.fairuniversal.offlineotp.ui.vault.vaultToolGroups(actions, strings),
                closeLabel = strings.closeTools,
                onSelectTool = ::selectTool,
                onDismiss = onDismiss
            )
        }
    }
}

/** Dedicated settings-export disclosure; no settings bytes or passwords enter Compose state. */
class PreferencesExportComposeView(
    activity: Activity,
    private val title: String,
    private val message: String,
    private val backupPasswordLabel: String,
    private val onConfirm: (Boolean) -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {
    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            com.fairuniversal.offlineotp.ui.vault.PreferencesExportDialog(
                inputKey = this,
                title = title,
                detail = message,
                allowBackupPassword = true,
                backupPasswordLabel = backupPasswordLabel,
                onConfirm = onConfirm,
                onDismiss = onDismiss
            )
        }
    }
}

/** Native backup picker seam for the shared, merge-by-default restore-mode selector. */
class RestoreModeComposeView(
    activity: Activity,
    private val onConfirm: (Boolean) -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {
    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        ForestTheme {
            com.fairuniversal.offlineotp.ui.vault.RestoreModeDialog(
                inputKey = this, onConfirm = onConfirm, onDismiss = onDismiss
            )
        }
    }
}

/** Visible Android backup page; the activity publishes only non-secret presentation values. */
class BackupOverviewComposeView(
    context: android.content.Context,
    private val onSelectTool: (String) -> Unit
) : AbstractComposeView(context) {
    var onBack: (() -> Unit)? = null
    fun navigateBack() { if (!closed && !busy) onBack?.invoke() }
    private var title by mutableStateOf("")
    private var description by mutableStateOf("")
    private var groups by mutableStateOf(emptyList<com.fairuniversal.offlineotp.ui.portable.PortableToolGroup>())
    private var warning by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var notice by mutableStateOf("")
    private var noticeRevision by mutableStateOf(0L)
    fun showNotice(message: String): Long {
        if (!closed) { noticeRevision++; notice = message }
        return noticeRevision
    }
    fun dismissNotice(revision: Long) {
        if (!closed && revision == noticeRevision) notice = ""
    }
    private var closed = false

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        isSaveEnabled = false
    }

    fun render(title: String, description: String,
               groups: List<com.fairuniversal.offlineotp.ui.portable.PortableToolGroup>, warning: String, busy: Boolean) {
        if (closed) return
        this.title = title
        this.description = description
        this.groups = groups
        this.warning = warning
        this.busy = busy
    }

    fun selectTool(key: String) {
        if (closed || busy || groups.flatMap { it.items }.none { it.key == key && it.enabled && it.interactive }) return
        onSelectTool(key)
    }

    fun clear(message: String = "") {
        closed = true
        notice = ""
        noticeRevision++
        groups = emptyList()
        title = ""
        description = ""
        warning = message
        busy = false
    }

    @Composable
    override fun Content() {
        ForestTheme {
            val revision = noticeRevision
            com.fairuniversal.offlineotp.ui.vault.SharedFeedbackPage(notice, { dismissNotice(revision) }) {
                com.fairuniversal.offlineotp.ui.vault.SharedPageNavigation(onBack?.let { { navigateBack() } }, !busy) {
                    com.fairuniversal.offlineotp.ui.vault.SharedBackupScreen(
                        title, description, groups, warning, busy, ::selectTool
                    )
                }
            }
        }
    }
}

/** Main-thread frame bridge for the shared hardware page; owns no device or vault authority. */
class HardwareKeysComposeView(
    context: android.content.Context,
    private val onSelectTool: (String, CharArray) -> Unit
) : AbstractComposeView(context) {
    private var title by mutableStateOf("")
    private var introduction by mutableStateOf("")
    private var passwordLabel by mutableStateOf("")
    private var groups by mutableStateOf(emptyList<com.fairuniversal.offlineotp.ui.portable.PortableToolGroup>())
    private var status by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var secretEpoch by mutableStateOf(0)
    private var closed = false

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        isSaveEnabled = false
    }

    fun render(title: String, introduction: String, passwordLabel: String,
               groups: List<com.fairuniversal.offlineotp.ui.portable.PortableToolGroup>, status: String, busy: Boolean) {
        if (closed) return
        this.title = title
        this.introduction = introduction
        this.passwordLabel = passwordLabel
        this.groups = groups
        this.status = status
        if (this.busy != busy) secretEpoch++
        this.busy = busy
    }

    fun selectTool(key: String, password: CharArray) {
        try {
            if (closed || busy || groups.flatMap { it.items }.none { it.key == key && it.enabled && it.interactive }) return
            onSelectTool(key, password)
        } finally { password.fill('\u0000') }
    }

    /** Stop clears transient codes and password input; resume may publish a fresh frame. */
    fun clear() {
        groups = emptyList()
        status = ""
        busy = false
        secretEpoch++
    }

    fun close() { clear(); closed = true }

    @Composable
    override fun Content() {
        ForestTheme {
            com.fairuniversal.offlineotp.ui.vault.SharedHardwareKeysScreen(
                inputKey = secretEpoch, title = title, introduction = introduction, passwordLabel = passwordLabel,
                groups = groups, status = status, busy = busy, onSelectTool = ::selectTool
            )
        }
    }
}

/** Android transport seam for the same FIDO PIN form used by Desktop. */
class HardwareUnlockComposeView(
    activity: Activity,
    private val transportNotice: String,
    private val pinLabel: String,
    private val confirmLabel: String,
    private val error: String?,
    private val onSubmit: (CharArray) -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {
    private var active = true

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        isSaveEnabled = false
    }

    /** Single-use UI request; PIN ownership never escapes this callback. */
    fun submitPin(pin: CharArray) {
        try {
            if (!active) return
            active = false
            onSubmit(pin)
        } finally { pin.fill('\u0000') }
    }

    fun cancelToPassword() {
        if (!active) return
        active = false
        onDismiss()
    }

    fun close() { active = false; dismiss() }

    @Composable
    override fun Content() {
        ForestTheme {
            com.fairuniversal.offlineotp.ui.unlock.HardwareUnlockDialog(
                busy = false, error = error, inputKey = this,
                transportNotice = transportNotice, pinLabel = pinLabel, confirmLabel = confirmLabel,
                onSubmit = ::submitPin, onDismiss = ::cancelToPassword
            )
        }
    }
}

/** Android settings page bridge; feedback and visible controls use common Compose presentation. */
class SettingsOverviewComposeView(
    context: android.content.Context,
    private val onSelectTool: (String) -> Unit
) : AbstractComposeView(context) {
    var onBack: (() -> Unit)? = null
    fun navigateBack() { if (!closed && !busy) onBack?.invoke() }
    private var notice by mutableStateOf("")
    private var noticeRevision by mutableStateOf(0L)
    fun showNotice(message: String): Long {
        if (closed) return noticeRevision
        noticeRevision++
        notice = message
        return noticeRevision
    }
    fun dismissNotice(revision: Long) { if (!closed && revision == noticeRevision) { notice = ""; noticeRevision++ } }
    private var title by mutableStateOf("")
    private var description by mutableStateOf("")
    private var groups by mutableStateOf(emptyList<com.fairuniversal.offlineotp.ui.portable.PortableToolGroup>())
    private var busy by mutableStateOf(false)
    private var closed = false

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        isSaveEnabled = false
    }

    fun render(title: String, description: String,
               groups: List<com.fairuniversal.offlineotp.ui.portable.PortableToolGroup>, busy: Boolean) {
        if (closed) return
        this.title = title
        this.description = description
        this.groups = groups
        this.busy = busy
    }

    fun selectTool(key: String) {
        if (closed || busy || groups.flatMap { it.items }.none { it.key == key && it.enabled && it.interactive }) return
        onSelectTool(key)
    }

    fun close() { closed = true; groups = emptyList(); notice = ""; noticeRevision++ }

    @Composable
    override fun Content() {
        ForestTheme {
            val revision = noticeRevision
            com.fairuniversal.offlineotp.ui.vault.SharedFeedbackPage(notice, { dismissNotice(revision) }) {
                com.fairuniversal.offlineotp.ui.vault.SharedPageNavigation(onBack?.let { { navigateBack() } }, !busy) {
                    com.fairuniversal.offlineotp.ui.vault.SharedVaultToolsScreen(
                        title, description, groups, "", busy, ::selectTool
                    )
                }
            }
        }
    }
}

/** Platform-neutral single-choice preference form with a single-use host callback. */
class SingleChoiceSelectionComposeView(
    activity: Activity,
    private val title: String,
    items: List<String>,
    private val initialSelected: Int,
    private val confirmLabel: String,
    private val dismissLabel: String,
    private val onConfirm: (Int) -> Unit,
    private val onDismiss: () -> Unit,
    private val message: String = "",
    enabledIndices: Set<Int>? = null
) : ComposeDialogHost(activity) {
    private val permittedIndices = enabledIndices?.toSet()
    private val choices = items.toList()
    private var consumed = false
    init { setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow) }
    fun confirmSelection(index: Int) {
        if (consumed || dismissals > 0 || index !in choices.indices) return
        if (permittedIndices != null && index !in permittedIndices) return
        consumed = true
        onConfirm(index)
    }
    @Composable
    override fun Content() {
        ForestTheme {
            com.fairuniversal.offlineotp.ui.vault.SingleChoiceSelectionDialog(
                inputKey = this@SingleChoiceSelectionComposeView, title = title, items = choices,
                initialSelected = initialSelected, confirmLabel = confirmLabel, dismissLabel = dismissLabel, message = message, enabledIndices = permittedIndices,
                onConfirm = ::confirmSelection, onDismiss = { if (!consumed) { consumed = true; onDismiss() } })
        }
    }
}

/** Bounded numeric preference adapter reusing the common numeric form, not a native picker. */
class NumericPreferenceComposeView(
    activity: Activity,
    private val initialValue: Int,
    private val minimum: Int,
    private val maximum: Int,
    private val title: String,
    private val inputLabel: String,
    private val confirmLabel: String,
    private val dismissLabel: String,
    private val onSave: (Int) -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {
    private var consumed = false
    init { setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow) }
    fun submitValue(text: String) {
        val value = text.toIntOrNull() ?: return
        if (consumed || dismissals > 0 || value !in minimum..maximum) return
        consumed = true
        onSave(value)
    }
    @Composable
    override fun Content() {
        ForestTheme {
            EntryCounterDialog(inputKey = this@NumericPreferenceComposeView,
                initialCounter = initialValue.toString(), title = title, inputLabel = inputLabel,
                confirmLabel = confirmLabel, dismissLabel = dismissLabel,
                isValid = { it.toIntOrNull()?.let { n -> n in minimum..maximum } == true },
                onSubmit = ::submitValue, onDismiss = { if (!consumed) { consumed = true; onDismiss() } })
        }
    }
}

/** Single-use bridge for provider options. Resolution and signature enforcement stay native. */
class OpenPgpProviderOptionsComposeView(
    activity: Activity,
    private val initialRecipients: String,
    private val initialVerify: Boolean,
    private val title: String,
    private val recipientsLabel: String,
    private val recipientsDescription: String,
    private val verifyLabel: String,
    private val verifyDescription: String,
    private val confirmLabel: String,
    private val dismissLabel: String,
    private val onSave: (String, Boolean) -> Unit,
    private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {
    private var consumed = false
    init { setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow) }
    fun saveOptions(recipients: String, verify: Boolean) {
        if (consumed || dismissals > 0 || recipients.length > com.fairuniversal.offlineotp.ui.portable.PortableInputBounds.MAX_TEXT_CHARS) return
        consumed = true
        onSave(recipients, verify)
    }
    @Composable
    override fun Content() {
        ForestTheme {
            com.fairuniversal.offlineotp.ui.vault.OpenPgpProviderOptionsDialog(
                this@OpenPgpProviderOptionsComposeView, initialRecipients, initialVerify,
                title, recipientsLabel, recipientsDescription, verifyLabel, verifyDescription, confirmLabel, dismissLabel,
                ::saveOptions, { if (!consumed) { consumed = true; onDismiss() } })
        }
    }
}

/** Setup step renderer; closed/destroyed views cannot mutate a replacement wizard fragment. */
class SetupChoiceComposeView(context: android.content.Context, private val onSelect: (Int) -> Unit) : AbstractComposeView(context) {
    private var title by mutableStateOf("")
    private var description by mutableStateOf("")
    private var choices by mutableStateOf(emptyList<String>())
    private var selected by mutableStateOf(-1)
    private var selectionDescription by mutableStateOf("")
    private var closed = false
    init { setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow); isSaveEnabled = false }
    fun render(title: String, description: String, choices: List<String>, selected: Int, selectionDescription: String) {
        if (closed) return
        this.title = title; this.description = description; this.choices = choices.toList()
        this.selected = selected; this.selectionDescription = selectionDescription
    }
    fun selectChoice(index: Int) { if (!closed && index in choices.indices) onSelect(index) }
    fun close() { closed = true; choices = emptyList() }
    @Composable override fun Content() {
        ForestTheme {
            com.fairuniversal.offlineotp.ui.unlock.SharedSetupChoiceScreen(title, description, choices, selected,
                selectionDescription, ::selectChoice)
        }
    }
}

/** Non-secret setup completion state with synchronous duplicate-action and destroyed-view guards. */
class SetupCompletionComposeView(context: android.content.Context, private val onCreate: () -> Unit,
                                 private val onRestore: () -> Unit) : AbstractComposeView(context) {
    private var title by mutableStateOf("")
    private var message by mutableStateOf("")
    private var createLabel by mutableStateOf("")
    private var restoreLabel by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var completed by mutableStateOf(false)
    private var closed = false
    init { setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow); isSaveEnabled = false }
    fun render(title: String, message: String, createLabel: String, restoreLabel: String, busy: Boolean, completed: Boolean) {
        if (closed) return
        this.title = title; this.message = message; this.createLabel = createLabel; this.restoreLabel = restoreLabel
        this.busy = busy; this.completed = completed
    }
    fun selectAction(restore: Boolean) {
        if (closed || busy || completed) return
        busy = true
        if (restore) onRestore() else onCreate()
    }
    fun close() { closed = true }
    @Composable override fun Content() {
        ForestTheme {
            com.fairuniversal.offlineotp.ui.unlock.SharedSetupCompletionScreen(title, message, createLabel, restoreLabel,
                busy, completed, { selectAction(false) }, { selectAction(true) })
        }
    }
}

/** Wizard bridge. Password snapshots are never saved and are cleared on mode change or disposal. */
class SetupCredentialsComposeView(context: android.content.Context,
    private val onSelect: (Int) -> Unit, private val onPassword: (String) -> Unit,
    private val onConfirmation: (String) -> Unit, private val onNext: () -> Unit
) : AbstractComposeView(context) {
    private var title by mutableStateOf("")
    private var description by mutableStateOf("")
    private var choices by mutableStateOf(emptyList<String>())
    private var selected by mutableStateOf(-1)
    private var enabledChoices by mutableStateOf(emptySet<Int>())
    private var passwordRequired by mutableStateOf(false)
    private var password by mutableStateOf("")
    private var confirmation by mutableStateOf("")
    private var passwordLabel by mutableStateOf("")
    private var confirmationLabel by mutableStateOf("")
    private var warning by mutableStateOf("")
    private var closed = false
    private var epoch by mutableStateOf(0)
    init { setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow); isSaveEnabled = false }
    fun render(title: String, description: String, choices: List<String>, selected: Int, enabledChoices: Set<Int>,
               passwordRequired: Boolean, password: String, confirmation: String, passwordLabel: String,
               confirmationLabel: String, warning: String) {
        if (closed) return
        if (this.selected != selected || this.passwordRequired != passwordRequired) epoch++
        this.title = title; this.description = description; this.choices = choices.toList(); this.selected = selected
        this.enabledChoices = enabledChoices.toSet(); this.passwordRequired = passwordRequired
        this.password = if (passwordRequired) password else ""; this.confirmation = if (passwordRequired) confirmation else ""
        this.passwordLabel = passwordLabel; this.confirmationLabel = confirmationLabel; this.warning = warning
    }
    fun selectChoice(index: Int) { if (!closed && index in choices.indices && index in enabledChoices && index != selected) onSelect(index) }
    fun changePassword(value: String) { if (!closed && passwordRequired && value.length <= com.fairuniversal.offlineotp.ui.portable.PortableInputBounds.MAX_TEXT_CHARS) onPassword(value) }
    fun changeConfirmation(value: String) { if (!closed && passwordRequired && value.length <= com.fairuniversal.offlineotp.ui.portable.PortableInputBounds.MAX_TEXT_CHARS) onConfirmation(value) }
    fun next() { if (!closed) onNext() }
    fun clearCredentials() { epoch++; password = ""; confirmation = "" }
    fun close() { closed = true; clearCredentials(); choices = emptyList(); enabledChoices = emptySet() }
    @Composable override fun Content() {
        val renderedEpoch = epoch
        ForestTheme {
            com.fairuniversal.offlineotp.ui.unlock.SharedSetupCredentialsScreen(title, description, choices, selected, enabledChoices,
                passwordRequired, password, confirmation, passwordLabel, confirmationLabel, warning,
                { if (renderedEpoch == epoch) selectChoice(it) }, { if (renderedEpoch == epoch) changePassword(it) },
                { if (renderedEpoch == epoch) changeConfirmation(it) }, { if (renderedEpoch == epoch) next() })
        }
    }
}

/** Native input is a capability adapter for Android autofill/accessibility, not another screen. */
class AuthenticationComposeView(context: android.content.Context, input: android.view.View,
    private val title: String, private val message: String, private val unlockLabel: String,
    private val hardwareLabel: String, private val biometricLabel: String,
    private val onAction: (String) -> Unit
) : AbstractComposeView(context) {
    private var nativeInput: android.view.View? by mutableStateOf(input)
    private var feedback by mutableStateOf<String?>(null)
    fun showFeedback(message: String) { if (!closed && foreground) feedback = message }
    fun clearFeedback() { feedback = null }
    private var busy by mutableStateOf(false)
    private var hardware by mutableStateOf(false)
    private var biometric by mutableStateOf(false)
    private var foreground = false
    private var closed = false
    init { setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow); isSaveEnabled = false }
    fun render(busy: Boolean, hardware: Boolean, biometric: Boolean, foreground: Boolean) {
        if (closed) return
        if (!foreground) feedback = null
        this.busy = busy; this.hardware = hardware; this.biometric = biometric; this.foreground = foreground
    }
    fun selectAction(key: String) {
        if (closed || !foreground || busy || key !in setOf("unlock", "hardware", "biometric")) return
        if ((key == "hardware" && !hardware) || (key == "biometric" && !biometric)) return
        feedback = null
        busy = true
        onAction(key)
    }
    fun close() { closed = true; foreground = false; feedback = null; nativeInput = null }
    @Composable override fun Content() {
        val field = nativeInput ?: return
        ForestTheme {
            com.fairuniversal.offlineotp.ui.unlock.SharedAuthenticationScreen(title, message, unlockLabel, busy,
                hardwareLabel.takeIf { hardware }, biometricLabel.takeIf { biometric },
                { selectAction("unlock") }, { selectAction("hardware") }, { selectAction("biometric") }, feedback = feedback) {
                androidx.compose.ui.viewinterop.AndroidView(factory = { field })
            }
        }
    }
}

/** Passwords stay in the protected native adapter, never in the shared preference shell. */
class ConfirmedSecretPreferenceComposeView(activity: Activity, input: android.view.View,
    private val title: String, private val description: String, private val confirmLabel: String,
    private val dismissLabel: String, private val onSave: () -> Unit, private val onDismiss: () -> Unit
) : ComposeDialogHost(activity) {
    private var nativeInput: android.view.View? by mutableStateOf(input)
    private var valid by mutableStateOf(false)
    private var consumed = false
    fun updateValidity(valid: Boolean) { if (!consumed && dismissals == 0) this.valid = valid }
    fun confirm() {
        if (consumed || dismissals > 0 || !valid) return
        consumed = true
        onSave()
    }
    fun releaseInput() { consumed = true; valid = false; nativeInput = null }
    @Composable override fun Content() {
        val field = nativeInput ?: return
        ForestTheme {
            com.fairuniversal.offlineotp.ui.vault.ConfirmedSecretPreferenceDialog(title, description, confirmLabel, dismissLabel,
                valid, ::confirm, { if (!consumed) { consumed = true; onDismiss() } }) {
                androidx.compose.ui.viewinterop.AndroidView(factory = { field })
            }
        }
    }
}

/** Full-screen shared About content. Android supplies only metadata and external intents. */
class AboutScreenComposeView(
    activity: Activity,
    private val version: String,
    val libraries: List<com.fairuniversal.offlineotp.ui.portable.PortableLibraryNotice>,
    private val extraLinks: List<com.fairuniversal.offlineotp.ui.vault.AboutLink>,
    private val onOpenLink: (String) -> Boolean,
    private val onVersionTap: () -> Unit,
    private val onClose: () -> Unit
) : AbstractComposeView(activity) {
    private var closed = false
    private var notice by mutableStateOf("")
    private var revision by mutableStateOf(0L)
    fun showNotice(message: String): Long {
        if (!closed) { revision++; notice = message }
        return revision
    }
    fun dismissNotice(expected: Long) {
        if (!closed && expected == revision) notice = ""
    }
    fun close() { closed = true; notice = ""; revision++ }

    init { setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow) }
    @Composable override fun Content() {
        ForestTheme {
            val expected = revision
            com.fairuniversal.offlineotp.ui.vault.SharedFeedbackPage(notice, { dismissNotice(expected) }) {
            androidx.compose.foundation.layout.Column {
                com.fairuniversal.offlineotp.ui.vault.ForestTextAction(text = "Back", onClick = onClose)
                com.fairuniversal.offlineotp.ui.vault.AboutContent(
                    version = version, onOpenLink = onOpenLink, libraries = libraries,
                    extraLinks = extraLinks, onVersionTap = onVersionTap)
            }
            }
        }
    }
}

/** The legacy pager is only a fragment/lifecycle adapter; no native wizard controls are mounted. */
class SetupShellComposeView(context: android.content.Context, pager: android.view.View,
    private val count: Int, private val backLabel: String, private val nextLabel: String,
    private val onNavigate: (Int) -> Unit
) : AbstractComposeView(context) {
    private var pager: android.view.View? by mutableStateOf(pager)
    private var step by mutableStateOf(0)
    private var busy by mutableStateOf(false)
    private var completed by mutableStateOf(false)
    private var closed = false
    init { setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow) }
    fun render(step: Int, busy: Boolean = false, completed: Boolean = false) {
        if (closed || step !in 0 until count) return
        this.step = step; this.busy = busy; this.completed = completed
    }
    fun navigate(direction: Int) {
        if (closed || busy || direction !in setOf(-1, 1)) return
        if (direction < 0 && (step <= 0 || completed)) return
        if (direction > 0 && step == count - 1 && !completed) return
        onNavigate(direction)
    }
    fun close() { closed = true; pager = null }
    @Composable override fun Content() {
        val nativePager = pager ?: return
        ForestTheme {
            com.fairuniversal.offlineotp.ui.unlock.SharedSetupShell(step, count, backLabel, nextLabel, busy, completed,
                { navigate(-1) }, { navigate(1) }) {
                androidx.compose.ui.viewinterop.AndroidView(factory = { nativePager })
            }
        }
    }
}
