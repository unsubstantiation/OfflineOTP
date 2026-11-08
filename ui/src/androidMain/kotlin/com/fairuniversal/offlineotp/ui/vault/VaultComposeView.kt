package com.fairuniversal.offlineotp.ui.vault

import android.content.Context

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
 * session authority and re-checks it when an action arrives, exactly as the Swing client does.
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
    private val appearance: VaultAppearance = VaultAppearance()
) : AbstractComposeView(context) {

    // A Compose snapshot state owned by the view: platform code writes frames, the composition reads
    // them. The initial frame is empty, so nothing is disclosed before the host renders once.
    private val host = VaultFrameHost(actions)
    private var frame by mutableStateOf(host.frame)
    private var renderedActions by mutableStateOf(host.actions)

    init {
        // The Activity's lifecycle destroys the composition; a view left in a detached hierarchy
        // must not keep collecting state.
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    // ComposeView itself is final. Custom Compose-backed Views implement this base-class hook.
    @Composable
    override fun Content() {
        VaultScreenHost(
            state = frame,
            strings = strings,
            actions = renderedActions,
            appearance = appearance
        )
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
    appearance: VaultAppearance = VaultAppearance()
) {
    ForestTheme {
        VaultScreen(
            state = state,
            strings = strings,
            actions = actions,
            appearance = appearance
        )
    }
}
