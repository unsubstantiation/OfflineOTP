package org.shadowice.flocke.andotp.View

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout

import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.EntryThumbnail
import org.shadowice.flocke.andotp.Utilities.InputPolicy
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.Tools
import org.shadowice.flocke.andotp.Utilities.UIHelper

import java.util.ArrayList
import java.util.HashMap
import java.util.Locale
import java.util.function.Consumer
import java.util.function.LongConsumer

/**
 * Screen-owned dialog rendering/input only. No direct Entry, position, key or vault access.
 * The caller validates each delayed command. Dismissal wipes editable text and drops tracked windows.
 */
class EntryActionDialogs(private val context: Context, private val settings: Settings) {
    private val open: HashMap<AlertDialog, EditText?> = HashMap()

    private fun input(): EditText {
        val input = EditText(context)
        input.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        input.isSingleLine = true
        input.isSaveEnabled = false
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

    private fun show(dialog: AlertDialog, input: EditText?) {
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
    }

    fun counter(counter: Long, save: LongConsumer) {
        val input = input()
        input.setText(String.format(Locale.ENGLISH, "%d", counter))
        input.inputType = InputType.TYPE_CLASS_NUMBER
        val dialog = AlertDialog.Builder(context).setTitle(R.string.dialog_title_counter)
                .setView(container(input))
                .setPositiveButton(R.string.button_save) { _, _ -> save.accept(input.text.toString().toLong()) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .create()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(text: Editable?) {
                val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                if (btn != null) btn.isEnabled = validCounter(text.toString())
            }
        })
        show(dialog, input)
        val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        if (btn != null) btn.isEnabled = validCounter(input.text.toString())
    }

    private fun validCounter(text: String): Boolean = isValidCounter(text)

    fun pin(current: String, save: Consumer<String>) {
        val input = input()
        input.setRawInputType(InputType.TYPE_NUMBER_VARIATION_PASSWORD or InputType.TYPE_CLASS_NUMBER)
        // Cap the text at the bound the entry model applies, so a PIN that would make the next
        // vault write reject the entry can never be typed in the first place.
        input.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(InputPolicy.MAX_TAG_CHARS))
        input.setText(current)
        input.transformationMethod = PasswordTransformationMethod.getInstance()
        input.requestFocus()
        UIHelper.showKeyboard(context, input, true)
        val dialog = AlertDialog.Builder(context).setTitle(R.string.dialog_title_pin)
                .setCancelable(false).setView(container(input))
                .setPositiveButton(R.string.button_accept) { _, _ ->
                    save.accept(input.text.toString())
                    UIHelper.hideKeyboard(context, input)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> UIHelper.hideKeyboard(context, input) }
                .create()
        show(dialog, input)
    }

    fun delete(label: String, confirm: Runnable) {
        val dialog = AlertDialog.Builder(context).setTitle(R.string.dialog_title_remove)
                .setMessage(context.getString(R.string.dialog_msg_confirm_delete, label))
                .setPositiveButton(R.string.yes) { _, _ -> confirm.run() }
                .setNegativeButton(R.string.no) { _, _ -> }
                .create()
        show(dialog, null)
    }

    fun thumbnail(issuer: String, label: String, select: Consumer<EntryThumbnail.EntryThumbnails>) {
        val thumbnails = ThumbnailSelectionAdapter(context, issuer, label)
        val input = input()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(text: Editable?) { thumbnails.filter(text.toString()) }
        })
        val grid = GridView(context)
        grid.adapter = thumbnails
        grid.setBackgroundColor(Tools.getThemeColor(context, R.attr.thumbnailBackground))
        val small = context.resources.getDimensionPixelSize(R.dimen.activity_margin_small)
        val medium = context.resources.getDimensionPixelSize(R.dimen.activity_margin_medium)
        grid.setPadding(small, small, small, small)
        grid.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        grid.columnWidth = settings.getThumbnailSize()
        grid.numColumns = GridView.AUTO_FIT
        grid.verticalSpacing = medium
        grid.horizontalSpacing = medium
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        layout.addView(input)
        layout.addView(grid)
        val dialog = AlertDialog.Builder(context).setTitle(R.string.menu_popup_change_image)
                .setView(container(layout)).setNegativeButton(android.R.string.cancel) { _, _ -> }
                .create()
        grid.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
            var value = EntryThumbnail.EntryThumbnails.Default
            try { value = EntryThumbnail.EntryThumbnails.values()[thumbnails.getRealIndex(position)] }
            catch (ignored: IndexOutOfBoundsException) { /* Retain the existing default-icon fallback. */ }
            select.accept(value)
            dialog.cancel()
        }
        show(dialog, input)
    }

    companion object {
        /** Whether [text] may be saved as an HOTP counter; mirrors `InputPolicy.tokenParameters`. */
        @JvmStatic
        fun isValidCounter(text: String): Boolean {
            // Long.MAX_VALUE is rejected by the entry model, so offering it here only produced a
            // confusing "could not save" after the dialog closed.
            return try {
                text.isNotEmpty() && text.toLong() in 0 until Long.MAX_VALUE
            } catch (e: NumberFormatException) {
                false
            }
        }
    }
}
