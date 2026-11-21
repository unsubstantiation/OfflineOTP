package com.fairuniversal.offlineotp.View

import android.app.Activity
import android.content.Context
import com.fairuniversal.offlineotp.R
import com.fairuniversal.offlineotp.Utilities.EntryThumbnail
import com.fairuniversal.offlineotp.Utilities.Settings
import com.fairuniversal.offlineotp.ui.host.ComposeDialogHost
import com.fairuniversal.offlineotp.ui.host.CounterEntryComposeView
import com.fairuniversal.offlineotp.ui.host.DeleteConfirmationComposeView
import com.fairuniversal.offlineotp.ui.host.PinEntryComposeView
import com.fairuniversal.offlineotp.ui.host.ThumbnailPickerComposeView
import java.util.ArrayList
import java.util.Locale
import java.util.function.Consumer
import java.util.function.LongConsumer

/**
 * Screen-owned dialog rendering/input only, backed by shared Compose forms in `:ui-foundation`
 * and `:ui`. No direct Entry, position, key or vault access. The caller validates each delayed
 * command against the live session, and [dismissAll] detaches every tracked host on lock.
 */
class EntryActionDialogs(private val context: Context, @Suppress("UNUSED_PARAMETER") settings: Settings) {
    private val composeDialogs: ArrayList<ComposeDialogHost> = ArrayList()
    private val artwork by lazy(LazyThreadSafetyMode.NONE) { AndroidIssuerArtwork(context) }

    private fun track(host: ComposeDialogHost) {
        composeDialogs.add(host)
        host.show()
    }

    private fun untrack(host: ComposeDialogHost?) {
        if (host != null) {
            host.dismiss()
            composeDialogs.remove(host)
        }
    }

    fun dismissAll() {
        for (host in ArrayList(composeDialogs)) {
            host.dismiss()
        }
        composeDialogs.clear()
    }

    fun counter(counter: Long, save: LongConsumer) {
        val activity = context as? Activity ?: return
        val pending = object { var host: ComposeDialogHost? = null }
        val view = CounterEntryComposeView(
            activity = activity,
            initialCounter = String.format(Locale.ENGLISH, "%d", counter),
            title = context.getString(R.string.dialog_title_counter),
            inputLabel = context.getString(R.string.dialog_title_counter),
            confirmLabel = context.getString(R.string.button_save),
            dismissLabel = context.getString(android.R.string.cancel),
            isValid = { text -> isValidCounter(text) },
            onSubmit = { text ->
                if (isValidCounter(text)) {
                    save.accept(text.toLong())
                }
                untrack(pending.host)
            },
            onDismiss = { untrack(pending.host) }
        )
        pending.host = view
        track(view)
    }

    fun pin(current: String, save: Consumer<String>) {
        val activity = context as? Activity ?: return
        val pending = object { var host: ComposeDialogHost? = null }
        val view = PinEntryComposeView(
            activity = activity,
            title = context.getString(R.string.dialog_title_pin),
            inputLabel = context.getString(R.string.dialog_title_pin),
            confirmLabel = context.getString(R.string.button_accept),
            dismissLabel = context.getString(android.R.string.cancel),
            initialPin = current,
            onSubmit = { value ->
                save.accept(value)
                untrack(pending.host)
            },
            onDismiss = { untrack(pending.host) }
        )
        pending.host = view
        track(view)
    }

    fun delete(label: String, confirm: Runnable) {
        // The same confirmation form the desktop host and the iOS Compose host use. The host view
        // only attaches/detaches the composition; the controller re-resolves the pending entry
        // before it removes anything, so a late confirmation cannot hit a new row.
        val activity = context as? Activity ?: return
        // The callbacks run only after show(), so the holder is populated by then. It avoids
        // capturing a local in its own initializer.
        val pending = object { var host: ComposeDialogHost? = null }
        val view = DeleteConfirmationComposeView(
            activity = activity,
            title = context.getString(R.string.dialog_title_remove),
            message = context.getString(R.string.dialog_msg_confirm_delete, label),
            confirmLabel = context.getString(R.string.yes),
            dismissLabel = context.getString(R.string.no),
            onConfirm = {
                confirm.run()
                pending.host?.dismiss()
                composeDialogs.remove(pending.host)
            },
            onDismiss = {
                pending.host?.dismiss()
                composeDialogs.remove(pending.host)
            }
        )
        pending.host = view
        composeDialogs.add(view)
        view.show()
    }

    fun thumbnail(@Suppress("UNUSED_PARAMETER") issuer: String, @Suppress("UNUSED_PARAMETER") label: String,
                  select: Consumer<EntryThumbnail.EntryThumbnails>) {
        val activity = context as? Activity ?: return
        val pending = object { var host: ComposeDialogHost? = null }
        val view = ThumbnailPickerComposeView(
            activity = activity,
            title = context.getString(R.string.menu_popup_change_image),
            selected = null,
            unchangedLabel = EntryThumbnail.EntryThumbnails.Default.name,
            artworkLoader = artwork::load,
            onSelect = { chosen ->
                val resolved = EntryThumbnail.EntryThumbnails.fromName(chosen)
                select.accept(resolved)
                untrack(pending.host)
            },
            onDismiss = { untrack(pending.host) }
        )
        pending.host = view
        track(view)
    }

    companion object {
        /** Whether [text] may be saved as an HOTP counter; mirrors `InputPolicy.tokenParameters`. */
        @JvmStatic
        fun isValidCounter(text: String): Boolean {
            // Long.MAX_VALUE is rejected by the entry model, so offering it here only produced a
            // confusing "could not save" after the dialog closed.
            return try {
                text.isNotEmpty() && text.toLong() in 0 until Long.MAX_VALUE
            } catch (_: NumberFormatException) {
                false
            }
        }
    }
}
