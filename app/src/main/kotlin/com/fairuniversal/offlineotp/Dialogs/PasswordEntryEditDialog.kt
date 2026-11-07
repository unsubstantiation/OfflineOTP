package com.fairuniversal.offlineotp.Dialogs

import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.R
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import com.fairuniversal.offlineotp.Utilities.PasswordCsvExport

/**
 * Edits the URL, username and password of a stored browser-imported password.
 *
 * The three fields are the only ones every browser export shares, so they are the only ones a form
 * can change without guessing how the source file spelled everything else; the remaining columns
 * (notes, GUIDs, timestamps, OTPAuth) are carried over untouched by [PasswordCsvExport].
 *
 * The password fields are not pre-filled: revealing a stored password behind a dialog that also
 * edits it would put the secret on screen for anyone who can see the phone, which is exactly what
 * the reveal gesture exists to gate. Leaving them blank keeps the stored values.
 */
object PasswordEntryEditDialog {
    private var activeDialog: AlertDialog? = null

    fun dismissActive() {
        activeDialog?.dismiss()
        activeDialog = null
    }

    fun show(context: Context, entry: Entry, save: (Entry) -> Unit) {
        if (!entry.isPassword()) return
        val fields = try {
            entry.getPasswordFields()
        } catch (_: IllegalArgumentException) {
            Toast.makeText(context, R.string.password_edit_failed, Toast.LENGTH_LONG).show()
            return
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        val scroll = ScrollView(context).apply { addView(body) }
        val maxChars = com.fairuniversal.offlineotp.Utilities.BrowserPasswordCsv.MAX_FIELD_CHARS
        fun field(hint: String, value: String, secret: Boolean = false): EditText {
            val input = EditText(context).apply {
                this.hint = hint
                setText(value)
                isSingleLine = true
                isSaveEnabled = false
                filters = arrayOf(InputFilter.LengthFilter(maxChars))
                inputType = InputType.TYPE_CLASS_TEXT
                if (secret) transformationMethod = PasswordTransformationMethod.getInstance()
            }
            body.addView(input)
            return input
        }
        val urlInput = field(context.getString(R.string.password_edit_url), fields.value(PasswordCsvExport.FIELD_URL))
        val userInput = field(context.getString(R.string.password_edit_username), fields.value(PasswordCsvExport.FIELD_USERNAME))
        val passwordInput = field(context.getString(R.string.password_edit_password), "", secret = true)
        passwordInput.hint = context.getString(R.string.password_edit_password)
        val reveal = CheckBox(context).apply {
            setText(R.string.password_edit_show)
            isSaveEnabled = false
            setOnCheckedChangeListener { _, checked ->
                passwordInput.transformationMethod =
                    if (checked) null else PasswordTransformationMethod.getInstance()
            }
        }
        body.addView(reveal)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.password_edit_title)
            .setView(scroll)
            .setPositiveButton(R.string.button_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            requireNotNull(dialog.getButton(AlertDialog.BUTTON_POSITIVE)).setOnClickListener {
                val typedPassword = passwordInput.text.toString()
                val replacement = try {
                    PasswordCsvExport.withEditedFields(
                        entry,
                        url = urlInput.text.toString(),
                        username = userInput.text.toString(),
                        // Blank keeps the stored password; a typed one replaces it.
                        password = typedPassword.ifEmpty { null }
                    )
                } catch (_: Exception) {
                    Toast.makeText(context, R.string.password_edit_failed, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                passwordInput.setText("")
                save(replacement)
                dialog.dismiss()
            }
        }
        // Leaving an abandoned edit must not keep the typed password alive in the view hierarchy.
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
            urlInput.setText("")
            userInput.setText("")
            passwordInput.setText("")
            reveal.isChecked = false
        }
        activeDialog?.dismiss()
        activeDialog = dialog
        dialog.show()
    }
}
