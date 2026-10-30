package org.shadowice.flocke.andotp.Dialogs

import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.EmailMaterial
import org.shadowice.flocke.andotp.Utilities.InputPolicy

/** Manual setup for IMAP/POP incoming and SMTP outgoing FairEmail-compatible credentials. */
object EmailEntryDialog {
    fun show(context: Context, save: (Entry) -> Unit) {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        val scroll = ScrollView(context).apply { addView(body) }
        val fields = LinkedHashMap<String, EditText>()
        fun section(title: String) {
            body.addView(android.widget.TextView(context).apply {
                setText(title); textSize = 16f; setPadding(0, 18, 0, 4)
            })
        }
        fun field(key: String, hint: String, value: String = "", secret: Boolean = false,
                  numeric: Boolean = false) {
            val input = EditText(context).apply {
                this.hint = hint
                setText(value)
                isSingleLine = true
                isSaveEnabled = false
                filters = arrayOf(InputFilter.LengthFilter(if (secret) EmailMaterial.MAX_CREDENTIAL_CHARS else InputPolicy.MAX_TEXT_CHARS))
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
                if (secret) transformationMethod = PasswordTransformationMethod.getInstance()
            }
            fields[key] = input
            body.addView(input)
        }
        section(context.getString(R.string.email_account_section))
        field("name", context.getString(R.string.email_name))
        field("address", context.getString(R.string.email_address), secret = false)
        section(context.getString(R.string.email_incoming_section))
        field("protocol", context.getString(R.string.email_protocol_hint, "0 = IMAP, 1 = POP"), "0", numeric = true)
        field("in_host", context.getString(R.string.email_host))
        field("in_port", context.getString(R.string.email_port), "993", numeric = true)
        field("in_encryption", context.getString(R.string.email_encryption_hint, "0 = SSL, 1 = STARTTLS, 2 = none"), "0", numeric = true)
        field("in_auth", context.getString(R.string.email_auth_type), "1", numeric = true)
        field("in_user", context.getString(R.string.email_username))
        field("in_password", context.getString(R.string.email_password), secret = true)
        section(context.getString(R.string.email_outgoing_section))
        field("out_host", context.getString(R.string.email_host))
        field("out_port", context.getString(R.string.email_port), "465", numeric = true)
        field("out_encryption", context.getString(R.string.email_encryption_hint, "0 = SSL, 1 = STARTTLS, 2 = none"), "0", numeric = true)
        field("out_auth", context.getString(R.string.email_auth_type), "1", numeric = true)
        field("out_user", context.getString(R.string.email_username))
        field("out_password", context.getString(R.string.email_password), secret = true)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.email_add_title)
            .setView(scroll)
            .setPositiveButton(R.string.button_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            requireNotNull(dialog.getButton(AlertDialog.BUTTON_POSITIVE)).setOnClickListener {
                try {
                    fun value(key: String) = fields.getValue(key).text.toString()
                    fun number(key: String) = value(key).toInt()
                    val email = EmailMaterial.create(
                        value("name").ifBlank { value("address") }, value("address"),
                        value("in_host"), number("in_port"), number("in_encryption"), number("protocol"),
                        value("in_user"), value("in_password"), number("in_auth"),
                        value("out_host"), number("out_port"), number("out_encryption"),
                        value("out_user"), value("out_password"), number("out_auth"))
                    save(Entry.email(email.account, email.identity))
                    fields.values.forEach { it.setText("") }
                    dialog.dismiss()
                } catch (_: Exception) {
                    Toast.makeText(context, R.string.email_invalid_fields, Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.setOnDismissListener { fields.values.forEach { it.setText("") } }
        dialog.show()
    }
}
