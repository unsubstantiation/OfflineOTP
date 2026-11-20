package com.fairuniversal.offlineotp.View

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Rect
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import com.fairuniversal.offlineotp.Utilities.UIHelper
import com.fairuniversal.offlineotp.ui.host.ComposeDialogHost
import com.fairuniversal.offlineotp.ui.host.DeleteConfirmationComposeView
import java.util.ArrayList
import java.util.HashMap
import java.util.function.Consumer

/**
 * Entry action dialogs for Android. The native PIN prompt was removed; only the shared delete
 * confirmation form remains. The controller still re-resolves the entry before deleting it, so
 * late confirmations cannot hit a new row.
 */
class EntryActionDialogs(private val context: Context) {

    private val open: HashMap<Dialog, EditText?> = HashMap()
    private val composeDialogs: ArrayList<ComposeDialogHost> = ArrayList()

    fun counter(current: String, save: Consumer<String>, isValid: (String) -> Boolean) {
        val input = input()
        input.setText(current)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(text: Editable?) {
                val btn = dialog?.getButton(AlertDialog.BUTTON_POSITIVE)
                if (btn != null) btn.isEnabled = isValid(text.toString())
            }
        })
        val dialog = AlertDialog.Builder(context).setTitle(R.string.dialog_title_counter)
                .setCancelable(false).setView(container(input))
                .setPositiveButton(R.string.button_accept) { _, _ ->
                    save.accept(input.text.toString())
                    UIHelper.hideKeyboard(context, input)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> UIHelper.hideKeyboard(context, input) }
                .create()
        show(dialog, input)
        val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        if (btn != null) btn.isEnabled = isValid(input.text.toString())
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
            },
            onDismiss = { pending.host?.dismiss() }
        )
        pending.host = view
        composeDialogs.add(view)
        view.show()
    }

    fun thumbnail(issuer: String, label: String, select: Consumer<EntryThumbnail.EntryThumbnails>) {
        val thumbnails = ThumbnailSelectionAdapter(context, issuer, label)
        val input = input()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(text: Editable?) { thumbnails.filter(text.toString()) }
        })
        val dialog = AlertDialog.Builder(context).setTitle(R.string.dialog_title_thumbnail)
                .setCancelable(false).setView(container(input))
                .setPositiveButton(R.string.button_accept) { _, _ ->
                    select.accept(thumbnails.current())
                    UIHelper.hideKeyboard(context, input)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> UIHelper.hideKeyboard(context, input) }
                .create()
        show(dialog, input)
    }

    private fun input(): EditText {
        val input = EditText(context)
        input.gravity = Gravity.CENTER_VERTICAL
        input.maxLines = 1
        return input
    }

    private fun container(child: android.view.View): FrameLayout {
        val container = FrameLayout(context)
        val medium = context.resources.getDimensionPixelSize(R.dimen.activity_margin_medium)
        val small = context.resources.getDimensionPixelSize(R.dimen.activity_margin_small)
        container.setPaddingRelative(medium, small, medium, 0)
        container.addView(child)
        return container
    }

    private fun show(dialog: Dialog, input: EditText?) {
        open[dialog] = input
        dialog.setOnDismissListener {
            if (input != null) { UIHelper.hideKeyboard(context, input); input.setText("") }
            open.remove(dialog)
        }
        dialog.show()
    }

    fun dismissAll() {
        for (dialog in ArrayList(open.keys)) {
            val input = open[dialog]
            if (input != null) { UIHelper.hideKeyboard(context, input); input.setText("") }
            dialog.dismiss()
        }
        open.clear()
        for (host in composeDialogs) {
            host.dismiss()
        }
        composeDialogs.clear()
    }

    private fun Rect.contains(x: Int, y: Int): Boolean =
        left <= x && x <= right && top <= y && y <= bottom
}
