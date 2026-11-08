package com.fairuniversal.offlineotp.View

import android.view.View
import com.fairuniversal.offlineotp.ui.vault.VaultUiState

/** Existing Android card capabilities, independent of gestures and row positions. */
enum class AndroidCardCommand {
    PRIVACY, COPY, REVEAL_TOKEN, NEXT_COUNTER, EDIT_COUNTER, ENTER_PIN, DELETE, MENU,
    SINGLE_TAP, DOUBLE_TAP
}

/**
 * Shared display state plus commands bound to the identities and generation that produced it.
 * [perform] reports whether a command was dispatched, not whether a later dialog/save succeeded.
 * The optional View is only the anchor required by the existing Android popup menu.
 * Hosts must discard frames on lock; immutable strings already issued cannot be zeroized here.
 */
class AndroidVaultFrame internal constructor(
    val state: VaultUiState,
    private val dispatch: (Long, AndroidCardCommand, View?) -> Boolean
) {
    fun perform(id: Long, command: AndroidCardCommand, anchor: View? = null): Boolean =
        dispatch(id, command, anchor)

    override fun toString(): String = "AndroidVaultFrame(redacted)"
}
