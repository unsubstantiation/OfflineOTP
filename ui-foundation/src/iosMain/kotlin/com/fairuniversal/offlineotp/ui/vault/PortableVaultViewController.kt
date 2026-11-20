@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import com.fairuniversal.offlineotp.ui.theme.ForestTheme
import com.fairuniversal.offlineotp.ui.portable.*
import platform.UIKit.UIViewController

/** Native vault actions. Every value here is re-checked by the host against its live session. */
interface PortableVaultActions {
    fun selectTab(position: Int)
    fun search(text: String)
    fun toggleTag(tag: String)
    fun clearTags()
    fun toggleTagRule()
    fun tool(action: String)
    fun card(index: Int, action: String)
}

/**
 * Native dialog actions. [action] returns null on success, or a safe user-facing message when the
 * host refused the request; the shared form keeps the draft and shows that message.
 */
interface PortableSurfaceActions {
    fun change(key: String, value: String)
    fun action(key: String): String?
}

/**
 * The main-thread bridge for the whole unlocked iOS UI, including its dialogs. One Compose
 * composition owns the screen; SwiftUI is reduced to a host and native file/camera pickers.
 */
class PortableVaultPresentation {
    private var active by mutableStateOf(true)
    private var vault by mutableStateOf(PortableVaultState.EMPTY)
    private var surface by mutableStateOf<PortableSurfaceState?>(null)

    /** Replaces the frame without replacing the composition. */
    fun updateVault(state: PortableVaultState) {
        vault = state
    }

    /** Opens or replaces the visible dialog. A null state closes it. */
    fun updateSurface(state: PortableSurfaceState?) {
        val current = surface
        // The host rebuilds its frame every second; a validation message from the last action must
        // survive that rebuild until the draft changes or the dialog closes.
        surface = if (state != null && current?.key == state.key && state.message.isEmpty()) {
            state.copy(message = current.message)
        } else {
            state
        }
    }

    fun close() {
        active = false
        surface = null
    }

    fun viewController(
        vaultActions: PortableVaultActions,
        surfaceActions: PortableSurfaceActions
    ): UIViewController = ComposeUIViewController {
        ForestTheme {
            if (active) {
                PortableVaultScreen(
                    state = vault,
                    onTab = { position -> vaultActions.selectTab(position) },
                    onQuery = { text -> vaultActions.search(text) },
                    onTag = { tag -> vaultActions.toggleTag(tag) },
                    onClearTags = { vaultActions.clearTags() },
                    onTool = { action -> vaultActions.tool(action) },
                    onCard = { index, action -> vaultActions.card(index, action) },
                    onDismissToast = { vaultActions.tool("dismissToast") }
                )
                val dialog = surface
                if (dialog != null) {
                    PortableSurfaceDialog(
                        state = dialog,
                        onChange = { key, value -> surfaceActions.change(key, value) },
                        onAction = { key ->
                            val failure = try {
                                surfaceActions.action(key)
                            } catch (_: Exception) {
                                "The action could not be completed. Nothing was changed."
                            }
                            if (active && failure != null) {
                                surface = surface?.copy(message = failure)
                            }
                        },
                        onDismiss = {
                            val failure = try {
                                surfaceActions.action("dismiss")
                            } catch (_: Exception) {
                                null
                            }
                            if (active && failure != null) {
                                surface = surface?.copy(message = failure)
                            }
                        }
                    )
                }
            }
        }
    }
}
