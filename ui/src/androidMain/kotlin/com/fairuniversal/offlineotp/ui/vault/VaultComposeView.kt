package com.fairuniversal.offlineotp.ui.vault

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

import com.fairuniversal.offlineotp.ui.theme.ForestTheme

/**
 * Android host seam for the shared vault screen.
 *
 * The Android app is built from Activities, Fragments and a RecyclerView, so the shared Compose
 * screen is reached through an [AbstractComposeView]: the host calls [VaultComposeView.render] with the frame
 * its own session produced and receives every interaction through the [VaultActions] it passed in.
 * The base view owns composition creation and disposal; only frame state changes, so a per-second refresh
 * recomposes the screen instead of rebuilding it; a lock should [clear] the frame (or drop the view)
 * so no code outlives the session that produced it.
 *
 * This class holds no session, key, storage or settings reference — deliberately. The Activity keeps
 * session authority and re-checks it when an action arrives, exactly as the desktop client does.
 *
 * Stage 2 usage (see docs/compose-unified-ui-plan.md):
 *
 * ```
 * val view = VaultComposeView(context, strings = AndroidVaultStrings.of(context), actions = actions)
 * view.render(projection.snapshot(filter, nowMillis, revealedIds, appearance))
 * ```
 */
class VaultComposeView(
    context: Context,
    private val strings: VaultStrings,
    actions: VaultActions,
    private val appearance: VaultAppearance = VaultAppearance(),
    artworkLoader: ((String) -> Bitmap?)? = null
) : AbstractComposeView(context) {

    private val artwork: VaultArtwork? = artworkLoader?.let { loader ->
        VaultArtwork { name -> loader(name)?.asImageBitmap() }
    }

    private var notice by mutableStateOf("")
    private var noticeRevision by mutableStateOf(0L)
    fun showNotice(message: String): Long {
        if (!ended) { noticeRevision++; notice = message }
        return noticeRevision
    }
    fun dismissNotice(revision: Long) {
        if (!ended && noticeRevision == revision) notice = ""
    }

    /** Optional host callback opening the shared OTP URI import dialog from the creation form. */
    var onOpenUriImport: (() -> Unit)? = null

    // A Compose snapshot state owned by the view: platform code writes frames, the composition reads
    // them. The initial frame is empty, so nothing is disclosed before the host renders once.
    private val host = VaultFrameHost(actions)
    private var frame by mutableStateOf(host.frame)
    private var renderedActions by mutableStateOf(host.actions)

    private class EmailCreation(
        val key: Any,
        val onSwitchToManual: (() -> Unit)?,
        val onSwitchToPassword: (() -> Unit)?,
        val submit: (EmailEntryInput) -> String?
    )
    private var emailCreation by mutableStateOf<EmailCreation?>(null)
    fun showEmailCreation(
        key: Any,
        onSwitchToManual: (() -> Unit)? = null,
        onSwitchToPassword: (() -> Unit)? = null,
        submit: (EmailEntryInput) -> String?
    ) {
        if (ended) return
        clearEntryEditor()
        emailCreation = EmailCreation(key, onSwitchToManual, onSwitchToPassword, submit)
    }
    fun ownsEmailCreation(key: Any) = !ended && emailCreation?.key === key

    private class PasswordCreation(
        val key: Any,
        val onSwitchToManual: (() -> Unit)?,
        val onSwitchToEmail: (() -> Unit)?,
        val submit: (PasswordEntryInput) -> String?
    )
    private var passwordCreation by mutableStateOf<PasswordCreation?>(null)
    fun showPasswordCreation(
        key: Any,
        onSwitchToManual: (() -> Unit)? = null,
        onSwitchToEmail: (() -> Unit)? = null,
        submit: (PasswordEntryInput) -> String?
    ) {
        if (ended) return
        clearEntryEditor()
        passwordCreation = PasswordCreation(key, onSwitchToManual, onSwitchToEmail, submit)
    }
    fun ownsPasswordCreation(key: Any) = !ended && passwordCreation?.key === key

    private class EmailEditor(
        val initial: EmailEditInput,
        val onManageTags: (() -> Unit)?,
        val onViewDetails: (() -> Unit)?,
        val onDelete: (() -> Unit)?,
        val submit: (EmailEditInput) -> String?
    )
    private var emailEditor by mutableStateOf<EmailEditor?>(null)
    fun showEmailEditor(
        initial: EmailEditInput,
        onManageTags: (() -> Unit)? = null,
        onViewDetails: (() -> Unit)? = null,
        onDelete: (() -> Unit)? = null,
        submit: (EmailEditInput) -> String?
    ) {
        if (ended) return
        clearEntryEditor()
        emailEditor = EmailEditor(initial, onManageTags, onViewDetails, onDelete, submit)
    }
    fun ownsEmailEditor(initial: EmailEditInput) = !ended && emailEditor?.initial === initial

    private class PasswordEditor(
        val initial: PasswordEntryInput,
        val onManageTags: (() -> Unit)?,
        val onViewDetails: (() -> Unit)?,
        val onExportArchive: (() -> Unit)?,
        val onDelete: (() -> Unit)?,
        val submit: (PasswordEntryInput) -> String?
    )
    private var passwordEditor by mutableStateOf<PasswordEditor?>(null)
    fun showPasswordEditor(
        initial: PasswordEntryInput,
        onManageTags: (() -> Unit)? = null,
        onViewDetails: (() -> Unit)? = null,
        onExportArchive: (() -> Unit)? = null,
        onDelete: (() -> Unit)? = null,
        submit: (PasswordEntryInput) -> String?
    ) {
        if (ended) return
        clearEntryEditor()
        passwordEditor = PasswordEditor(initial, onManageTags, onViewDetails, onExportArchive, onDelete, submit)
    }
    fun ownsPasswordEditor(initial: PasswordEntryInput) = !ended && passwordEditor?.initial === initial

    private class LinScpExport(val key: Any, val choices: List<String>,
        val submit: (Set<Int>, Boolean, String, String) -> String?)
    private var linScpExport by mutableStateOf<LinScpExport?>(null)

    fun showLinScpExport(key: Any, choices: List<String>, submit: (Set<Int>, Boolean, String, String) -> String?) {
        if (ended) return
        clearEntryEditor()
        linScpExport = LinScpExport(key, choices.toList(), submit)
    }
    fun ownsLinScpExport(key: Any) = !ended && linScpExport?.key === key

    private class LinScpRead(val key: Any, val secretLabel: String,
        val submit: (String) -> String?, val dispose: () -> Unit)
    private var linScpRead by mutableStateOf<LinScpRead?>(null)

    /** The read/decrypt stage uses the desktop form; file IO and decoding remain host-owned. */
    fun showLinScpRead(key: Any, secretLabel: String, dispose: () -> Unit, submit: (String) -> String?) {
        if (ended) { dispose(); return }
        clearEntryEditor()
        linScpRead = LinScpRead(key, secretLabel, submit, dispose)
    }
    fun ownsLinScpRead(key: Any) = !ended && linScpRead?.key === key

    private class LinScpImport(val key: Any, val choices: List<String>,
        val submit: (Set<Int>) -> String?, val dispose: () -> Unit)
    private var linScpImport by mutableStateOf<LinScpImport?>(null)

    /** Takes ownership of the staged import until confirmation, replacement, cancellation or lock. */
    fun showLinScpImport(key: Any, choices: List<String>, dispose: () -> Unit, submit: (Set<Int>) -> String?) {
        if (ended) { dispose(); return }
        clearEntryEditor()
        linScpImport = LinScpImport(key, choices.toList(), submit, dispose)
    }
    fun ownsLinScpImport(key: Any) = !ended && linScpImport?.key === key
    fun clearLinScpImport() {
        val reading = linScpRead
        linScpRead = null
        reading?.dispose?.invoke()
        val previous = linScpImport
        linScpImport = null // Revoke callbacks before releasing secret material.
        previous?.dispose?.invoke()
    }

    private var ended = false
    private class Editor(
        val initial: ManualEntryInput,
        val editing: Boolean,
        val onShowQrCode: (() -> Unit)?,
        val onShareKeystrokes: (() -> Unit)?,
        val onManageTags: (() -> Unit)?,
        val onViewDetails: (() -> Unit)?,
        val onDelete: (() -> Unit)?,
        var onPasswordSubmit: ((PasswordEntryInput) -> String?)?,
        var onEmailSubmit: ((EmailEntryInput) -> String?)?,
        val submit: (ManualEntryInput) -> String?
    )
    private var editor by mutableStateOf<Editor?>(null)
    private var editError by mutableStateOf<String?>(null)

    /** Uses the same form and draft validation as desktop; the host owns save authority. */
    fun showEntryEditor(
        initial: ManualEntryInput,
        editing: Boolean = true,
        onShowQrCode: (() -> Unit)? = null,
        onShareKeystrokes: (() -> Unit)? = null,
        onManageTags: (() -> Unit)? = null,
        onViewDetails: (() -> Unit)? = null,
        onDelete: (() -> Unit)? = null,
        onPasswordSubmit: ((PasswordEntryInput) -> String?)? = null,
        onEmailSubmit: ((EmailEntryInput) -> String?)? = null,
        submit: (ManualEntryInput) -> String?
    ) {
        if (ended) return
        clearEntryEditor()
        editor = Editor(initial, editing, onShowQrCode, onShareKeystrokes, onManageTags, onViewDetails, onDelete, onPasswordSubmit, onEmailSubmit, submit)
    }

    /** Attaches password and email creation switchers to the currently owned [initial] creation form. */
    fun setSharedCreationHandlers(
        initial: ManualEntryInput,
        onPasswordSubmit: ((PasswordEntryInput) -> String?)? = null,
        onEmailSubmit: ((EmailEntryInput) -> String?)? = null
    ) {
        val current = editor ?: return
        if (!ended && !current.editing && current.initial === initial) {
            current.onPasswordSubmit = onPasswordSubmit
            current.onEmailSubmit = onEmailSubmit
        }
    }

    fun ownsEntryEditor(initial: ManualEntryInput): Boolean = !ended && editor?.initial === initial

    fun clearEntryEditor() { clearLinScpImport(); editor = null; emailEditor = null; emailCreation = null; passwordCreation = null; passwordEditor = null; linScpExport = null; editError = null }

    init {
        // The Activity's lifecycle destroys the composition; a view left in a detached hierarchy
        // must not keep collecting state.
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    // ComposeView itself is final. Custom Compose-backed Views implement this base-class hook.
    @Composable
    override fun Content() {
        val revision = noticeRevision
        ForestTheme {
            SharedFeedbackPage(notice, { dismissNotice(revision) }) {
                VaultScreenHost(
                    state = frame,
                    strings = strings,
                    actions = renderedActions,
                    appearance = appearance,
                    artwork = artwork
                )
            }
        }
        val reading = linScpRead
        if (reading != null) ForestTheme {
            LinScpImportDialog(reading.key, busy = false, count = null, failed = editError != null,
                keySelected = false, onChooseKey = {}, onClearKey = {}, onConfirm = {}, choices = emptyList(),
                secretLabel = reading.secretLabel, allowKeyFileSelection = false,
                onRead = { secret ->
                    if (ownsLinScpRead(reading.key)) {
                        val failure = reading.submit(secret)
                        if (linScpRead === reading) {
                            if (failure == null) clearEntryEditor() else editError = failure
                        }
                    }
                }, onDismiss = { if (linScpRead === reading) clearEntryEditor() })
        }
        val importing = linScpImport
        if (importing != null) ForestTheme {
            LinScpImportDialog(importing.key, busy = false, count = importing.choices.size, failed = editError != null,
                keySelected = false, onChooseKey = {}, onClearKey = {}, onRead = {}, choices = importing.choices,
                onConfirm = { selected ->
                    if (ownsLinScpImport(importing.key)) {
                        val failure = importing.submit(selected)
                        if (linScpImport === importing) {
                            if (failure == null) clearEntryEditor() else editError = failure
                        }
                    }
                }, onDismiss = { if (linScpImport === importing) clearEntryEditor() })
        }
        val export = linScpExport
        if (export != null) ForestTheme {
            LinScpExportDialog(export.key, export.choices, busy = false, error = editError,
                onSubmit = { selected, plainText, password, confirmation ->
                    if (ownsLinScpExport(export.key)) {
                        val failure = export.submit(selected, plainText, password, confirmation)
                        if (linScpExport === export) {
                            if (failure == null) clearEntryEditor() else editError = failure
                        }
                    }
                }, onDismiss = { if (linScpExport === export) clearEntryEditor() })
        }
        val creatingEmail = emailCreation
        if (creatingEmail != null) ForestTheme {
            EmailEntryDialog(
                inputKey = creatingEmail.key,
                error = editError,
                artwork = artwork,
                onSwitchToManual = creatingEmail.onSwitchToManual?.let { switchToManual ->
                    {
                        if (ownsEmailCreation(creatingEmail.key)) {
                            editError = null
                            switchToManual()
                        }
                    }
                },
                onSwitchToPassword = creatingEmail.onSwitchToPassword?.let { switchToPassword ->
                    {
                        if (ownsEmailCreation(creatingEmail.key)) {
                            editError = null
                            switchToPassword()
                        }
                    }
                },
                onSwitchToUriImport = onOpenUriImport?.let { open ->
                    {
                        if (ownsEmailCreation(creatingEmail.key)) {
                            clearEntryEditor()
                            open()
                        }
                    }
                },
                onSubmit = { input ->
                    if (ownsEmailCreation(creatingEmail.key)) {
                        val failure = creatingEmail.submit(input)
                        if (emailCreation === creatingEmail) {
                            if (failure == null) clearEntryEditor() else editError = failure
                        }
                    }
                },
                onDismiss = { clearEntryEditor() }
            )
        }
        val creatingPassword = passwordCreation
        if (creatingPassword != null) ForestTheme {
            PasswordEntryDialog(
                inputKey = creatingPassword.key,
                initial = PasswordEntryInput(),
                failed = editError != null,
                editing = false,
                errorText = editError,
                artwork = artwork,
                onSwitchToManual = creatingPassword.onSwitchToManual?.let { switchToManual ->
                    {
                        if (ownsPasswordCreation(creatingPassword.key)) {
                            editError = null
                            switchToManual()
                        }
                    }
                },
                onSwitchToEmail = creatingPassword.onSwitchToEmail?.let { switchToEmail ->
                    {
                        if (ownsPasswordCreation(creatingPassword.key)) {
                            editError = null
                            switchToEmail()
                        }
                    }
                },
                onSwitchToUriImport = onOpenUriImport?.let { open ->
                    {
                        if (ownsPasswordCreation(creatingPassword.key)) {
                            clearEntryEditor()
                            open()
                        }
                    }
                },
                onSubmit = { input ->
                    if (ownsPasswordCreation(creatingPassword.key)) {
                        val failure = creatingPassword.submit(input)
                        if (passwordCreation === creatingPassword) {
                            if (failure == null) clearEntryEditor() else editError = failure
                        }
                    }
                },
                onDismiss = { clearEntryEditor() }
            )
        }
        val email = emailEditor
        if (email != null) ForestTheme {
            EmailEditDialog(email, email.initial, editError, onSubmit = { input ->
                if (ownsEmailEditor(email.initial)) {
                    val failure = email.submit(input)
                    if (emailEditor === email) {
                        if (failure == null) clearEntryEditor() else editError = failure
                    }
                }
            }, onDismiss = { clearEntryEditor() }, artwork = artwork, onManageTags = email.onManageTags, onViewDetails = email.onViewDetails, onDelete = email.onDelete)
        }
        val password = passwordEditor
        if (password != null) ForestTheme {
            PasswordEntryDialog(password, password.initial, editError != null, onSubmit = { input ->
                if (ownsPasswordEditor(password.initial)) {
                    val failure = password.submit(input)
                    if (passwordEditor === password) {
                        if (failure == null) clearEntryEditor() else editError = failure
                    }
                }
            }, onDismiss = { clearEntryEditor() }, errorText = editError, artwork = artwork, onManageTags = password.onManageTags, onViewDetails = password.onViewDetails, onExportArchive = password.onExportArchive, onDelete = password.onDelete)
        }
        val request = editor
        if (request != null) ForestTheme {
            ManualEntryDialog(inputKey = request, initial = request.initial, editing = request.editing,
                artwork = artwork, error = editError, onDismiss = { clearEntryEditor() },
                onModeChanged = { editError = null },
                onShowQrCode = request.onShowQrCode,
                onShareKeystrokes = request.onShareKeystrokes,
                onManageTags = request.onManageTags,
                onViewDetails = request.onViewDetails,
                onDelete = request.onDelete,
                onSwitchToUriImport = if (!request.editing) onOpenUriImport?.let { open ->
                    {
                        if (!ended && editor === request) {
                            clearEntryEditor()
                            open()
                        }
                    }
                } else null,
                onPasswordSubmit = request.onPasswordSubmit?.let { submitPassword ->
                    { input ->
                        if (!ended && editor === request) {
                            val failure = submitPassword(input)
                            if (editor === request) {
                                if (failure == null) clearEntryEditor() else editError = failure
                            }
                        }
                    }
                },
                onEmailSubmit = request.onEmailSubmit?.let { submitEmail ->
                    { input ->
                        if (!ended && editor === request) {
                            val failure = submitEmail(input)
                            if (editor === request) {
                                if (failure == null) clearEntryEditor() else editError = failure
                            }
                        }
                    }
                },
                onSubmit = { input ->
                    if (!ended && editor === request) {
                        val failure = request.submit(input)
                        if (editor === request) {
                            if (failure == null) clearEntryEditor() else editError = failure
                        }
                    }
                })
        }
    }

    /** Publishes the next frame. Called from the UI thread, like any View mutation. */
    fun render(next: VaultUiState) {
        if (host.render(next)) frame = host.frame
    }

    /** Publish callbacks bound to this exact frame, not to a mutable latest-frame pointer. */
    fun render(next: VaultUiState, actions: VaultActions) {
        if (host.render(next)) {
            renderedActions = host.guard(actions)
            frame = host.frame
        }
    }

    /** End this view's session. Late frames/callbacks are ignored; create a new view on unlock. */
    fun clear() {
        ended = true
        notice = ""
        noticeRevision++
        clearEntryEditor()
        host.close()
        renderedActions = VaultActions()
        frame = host.frame
    }
}

/**
 * The same screen wrapped in the shared theme, for a host that builds its own `ComposeView` or hosts
 * the screen inside a Compose hierarchy (for example a future Fragment using `ComposeView` directly).
 */
@Composable
fun VaultScreenHost(
    state: VaultUiState,
    strings: VaultStrings,
    actions: VaultActions,
    appearance: VaultAppearance = VaultAppearance(),
    artwork: VaultArtwork? = null
) {
    ForestTheme {
        VaultScreen(
            state = state,
            strings = strings,
            actions = actions,
            appearance = appearance,
            artwork = artwork
        )
    }
}
