package org.shadowice.flocke.andotp.Dialogs

import android.app.AlertDialog
import android.content.DialogInterface
import android.text.Editable
import android.text.InputFilter
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

import com.github.aakira.expandablelayout.ExpandableLayoutListenerAdapter
import com.github.aakira.expandablelayout.ExpandableLinearLayout

import org.shadowice.flocke.andotp.Activities.MainActivity
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.InputPolicy
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.SshKeyMaterial
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.Utilities.VaultSession
import org.shadowice.flocke.andotp.View.EntriesCardAdapter
import org.shadowice.flocke.andotp.View.EntryActionDialogs
import org.shadowice.flocke.andotp.View.TagsAdapter

import java.util.HashMap
import java.util.Locale
import java.util.concurrent.Callable

/** Dialog for adding a new OTP entry or editing an existing one. */
object ManualEntryDialog {
    @JvmSynthetic
    internal var activeDialog: AlertDialog? = null

    @JvmSynthetic
    internal fun dismissActive() {
        activeDialog?.dismiss()
        activeDialog = null
    }

    /** Opens the dialog to add a new entry. */
    @JvmStatic
    fun show(callingActivity: MainActivity, settings: Settings, adapter: EntriesCardAdapter) {
        show(callingActivity, settings, adapter, null, null)
    }

    /** Opens the dialog for a new entry, or to edit [oldEntry] if it is not null. */
    @JvmStatic
    fun show(callingActivity: MainActivity, settings: Settings, adapter: EntriesCardAdapter,
             oldEntry: Entry?, updateCallback: UpdateCallback?) {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        val isNewEntry = oldEntry == null

        val container: ViewGroup = requireNotNull(callingActivity.findViewById(R.id.main_content))
        val inputView = callingActivity.getLayoutInflater().inflate(R.layout.dialog_manual_entry, container, false)

        if (settings.getBlockAccessibility())
            inputView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)

        val typeInput = requireNotNull(inputView.findViewById<Spinner>(R.id.manual_type))
        val otpUriInput = requireNotNull(inputView.findViewById<EditText>(R.id.manual_otp_uri))
        val applyOtpUriButton = requireNotNull(inputView.findViewById<Button>(R.id.manual_apply_otp_uri))
        otpUriInput.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(InputPolicy.MAX_URI_CHARS))
        otpUriInput.isSaveEnabled = false
        val issuerInput = requireNotNull(inputView.findViewById<EditText>(R.id.manual_issuer))
        val labelInput = requireNotNull(inputView.findViewById<EditText>(R.id.manual_label))
        val secretInput = requireNotNull(inputView.findViewById<EditText>(R.id.manual_secret))
        val sshPublicInput = requireNotNull(inputView.findViewById<EditText>(R.id.manual_ssh_public))
        val sshPublicLayout = requireNotNull(inputView.findViewById<LinearLayout>(R.id.manual_layout_ssh_public))
        sshPublicInput.isSaveEnabled = false
        secretInput.isSaveEnabled = false
        val secretView = requireNotNull(inputView.findViewById<TextView>(R.id.manual_secret_view))
        secretView.isSaveEnabled = false
        val counterInput = requireNotNull(inputView.findViewById<EditText>(R.id.manual_counter))
        val periodInput = requireNotNull(inputView.findViewById<EditText>(R.id.manual_period))
        val digitsInput = requireNotNull(inputView.findViewById<EditText>(R.id.manual_digits))
        val counterLayout = requireNotNull(inputView.findViewById<LinearLayout>(R.id.manual_layout_counter))
        val periodLayout = requireNotNull(inputView.findViewById<LinearLayout>(R.id.manual_layout_period))
        val algorithmInput = requireNotNull(inputView.findViewById<Spinner>(R.id.manual_algorithm))
        val tagsInput = requireNotNull(inputView.findViewById<Button>(R.id.manual_tags))
        val expandButton = requireNotNull(inputView.findViewById<Button>(R.id.dialog_expand_button))
        val expandLayout = requireNotNull(inputView.findViewById<ExpandableLinearLayout>(R.id.dialog_expand_layout))
        var validationWatcher: TextWatcher? = null

        val algorithmAdapter = ArrayAdapter<TokenCalculator.HashAlgorithm>(callingActivity,
            android.R.layout.simple_expandable_list_item_1, TokenCalculator.HashAlgorithm.values().toMutableList())
        val typeAdapter = ArrayAdapter<Entry.OTPType>(callingActivity,
            android.R.layout.simple_expandable_list_item_1, Entry.OTPType.values().toMutableList())

        typeInput.adapter = typeAdapter
        algorithmInput.adapter = algorithmAdapter

        periodInput.setText(String.format(Locale.US, "%d", TokenCalculator.TOTP_DEFAULT_PERIOD))
        digitsInput.setText(String.format(Locale.US, "%d", TokenCalculator.TOTP_DEFAULT_DIGITS))
        counterInput.setText(String.format(Locale.US, "%d", TokenCalculator.HOTP_INITIAL_COUNTER))

        // Once a URI supplies parameters, changing the type must not silently replace those values
        // with manual-entry defaults (the user can still edit each field explicitly).
        var importedUri = false
        typeInput.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val type = typeInput.getSelectedItem() as? Entry.OTPType

                val ssh = type == Entry.OTPType.SSH_KEY
                val nonOtp = type == Entry.OTPType.NOTE || ssh
                secretInput.setHint(when (type) {
                    Entry.OTPType.NOTE -> R.string.hint_secure_note
                    Entry.OTPType.SSH_KEY -> R.string.hint_ssh_private
                    else -> R.string.hint_secret
                })
                sshPublicLayout.visibility = if (ssh) View.VISIBLE else View.GONE
                otpUriInput.visibility = if (isNewEntry && !nonOtp) View.VISIBLE else View.GONE
                applyOtpUriButton.visibility = if (isNewEntry && !nonOtp) View.VISIBLE else View.GONE
                if (nonOtp) {
                    counterLayout.visibility = View.GONE
                    periodLayout.visibility = View.GONE
                    expandButton.visibility = View.GONE
                    expandLayout.visibility = View.GONE
                } else if (type == Entry.OTPType.STEAM) {
                    counterLayout.visibility = View.GONE
                    periodLayout.visibility = View.VISIBLE
                    expandButton.visibility = View.VISIBLE
                    expandLayout.visibility = View.VISIBLE

                    if (isNewEntry && !importedUri) {
                        digitsInput.setText(String.format(Locale.US, "%d", TokenCalculator.STEAM_DEFAULT_DIGITS))
                        periodInput.setText(String.format(Locale.US, "%d", TokenCalculator.TOTP_DEFAULT_PERIOD))
                        algorithmInput.setSelection(algorithmAdapter.getPosition(TokenCalculator.HashAlgorithm.SHA1))
                    }

                    digitsInput.isEnabled = false
                    periodInput.isEnabled = false
                    algorithmInput.isEnabled = false
                } else if (type == Entry.OTPType.TOTP) {
                    counterLayout.visibility = View.GONE
                    periodLayout.visibility = View.VISIBLE
                    expandButton.visibility = View.VISIBLE
                    expandLayout.visibility = View.VISIBLE

                    if (isNewEntry && !importedUri)
                        digitsInput.setText(String.format(Locale.US, "%d", TokenCalculator.TOTP_DEFAULT_DIGITS))

                    digitsInput.isEnabled = true
                    periodInput.isEnabled = true
                    algorithmInput.isEnabled = isNewEntry
                } else if (type == Entry.OTPType.HOTP) {
                    counterLayout.visibility = View.VISIBLE
                    periodLayout.visibility = View.GONE
                    expandButton.visibility = View.VISIBLE
                    expandLayout.visibility = View.VISIBLE

                    if (isNewEntry && !importedUri)
                        digitsInput.setText(String.format(Locale.US, "%d", TokenCalculator.TOTP_DEFAULT_DIGITS))

                    digitsInput.isEnabled = true
                    periodInput.isEnabled = true
                    algorithmInput.isEnabled = isNewEntry
                } else if (type == Entry.OTPType.MOTP) {
                    counterLayout.visibility = View.GONE
                    periodLayout.visibility = View.VISIBLE

                    if (isNewEntry && !importedUri)
                        digitsInput.setText(String.format(Locale.US, "%d", TokenCalculator.TOTP_DEFAULT_DIGITS))

                    expandButton.visibility = View.GONE
                    expandLayout.visibility = View.GONE
                    digitsInput.isEnabled = true
                    periodInput.isEnabled = true
                    algorithmInput.isEnabled = isNewEntry
                }
                validationWatcher?.afterTextChanged(null)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val allTags = adapter.getTags()
        val tagsHashMap = HashMap<String, Boolean?>()
        for (tag in allTags) {
            tagsHashMap[tag] = false
        }
        val tagsAdapter = TagsAdapter(callingActivity, tagsHashMap)
        // Show how many saved entries use each tag in the selection list as well as the drawer.
        tagsAdapter.setTagCounts(adapter.getTagCounts())

        val tagsCallable = Callable<Any?> {
            val selectedTags = tagsAdapter.getActiveTags()
            val stringBuilder = StringBuilder()
            for (j in selectedTags.indices) {
                stringBuilder.append(selectedTags[j])
                if (j < selectedTags.size - 1) {
                    stringBuilder.append(", ")
                }
            }
            tagsInput.setText(stringBuilder.toString())
            null
        }

        tagsInput.setOnClickListener {
            TagsDialog.show(callingActivity, tagsAdapter, tagsCallable, tagsCallable)
        }

        applyOtpUriButton.setOnClickListener {
            var parsedEntry: Entry? = null
            try {
                // The URI carries the seed. Keep it out of logs, saved view state and any message.
                val entry = Entry(otpUriInput.text?.toString()?.trim())
                parsedEntry = entry
                importedUri = true
                typeInput.setSelection(typeAdapter.getPosition(entry.getType()))
                issuerInput.setText(entry.getIssuer())
                labelInput.setText(entry.getLabel())
                secretInput.setText(entry.getSecretEncoded())
                periodInput.setText(String.format(Locale.US, "%d", entry.getPeriod()))
                digitsInput.setText(String.format(Locale.US, "%d", entry.getDigits()))
                counterInput.setText(String.format(Locale.US, "%d", entry.getCounter()))
                algorithmInput.setSelection(algorithmAdapter.getPosition(entry.getAlgorithm()))

                val importedTags = HashMap(tagsAdapter.getTagsWithState())
                for (tag in importedTags.keys.toList()) importedTags[tag] = false
                for (tag in entry.getTags()) importedTags[tag] = true
                tagsAdapter.setTags(importedTags)
                tagsCallable.call()
                // The decoded fields remain available for confirmation; drop the redundant URI copy.
                otpUriInput.setText("")
                validationWatcher?.afterTextChanged(null)
            } catch (_: Exception) {
                Toast.makeText(callingActivity, R.string.error_invalid_otp_uri, Toast.LENGTH_LONG).show()
            } finally {
                parsedEntry?.destroy()
            }
        }

        // Dirty fix for the compound drawable to avoid crashes on older Android versions
        expandButton.setCompoundDrawablesWithIntrinsicBounds(null, null,
            ResourcesCompat.getDrawable(callingActivity.resources, R.drawable.ic_arrow_down_accent, null), null)

        expandButton.setOnClickListener { expandLayout.toggle() }

        expandLayout.setListener(object : ExpandableLayoutListenerAdapter() {
            override fun onOpened() {
                super.onOpened()
                expandButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_arrow_up_accent, 0)
            }

            override fun onClosed() {
                super.onClosed()
                expandButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_arrow_down_accent, 0)
            }
        })

        val builder = AlertDialog.Builder(callingActivity)
        builder.setTitle(R.string.dialog_title_manual_entry)
            .setView(inputView)
            .setPositiveButton(R.string.button_save, null)
            .setNegativeButton(android.R.string.cancel, DialogInterface.OnClickListener { _, _ -> })

        activeDialog?.dismiss()
        val dialog = builder.create()
        activeDialog = dialog
        // Keep [Save]/[Cancel] reachable while the IME is open: resize the dialog window and let
        // the ScrollView in dialog_manual_entry scroll instead of panning the buttons off-screen.
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
            secretInput.setText("")
            sshPublicInput.setText("")
            secretView.setText("")
            otpUriInput.setText("")
        }
        dialog.show()

        val positiveButton = requireNotNull(dialog.getButton(AlertDialog.BUTTON_POSITIVE))

        positiveButton.setOnClickListener {
            if (VaultSession.getKey() == null) {
                dialog.dismiss()
                return@setOnClickListener
            }
            try {
                val type = typeInput.getSelectedItem() as? Entry.OTPType
                val rawSecret = secretInput.text?.toString() ?: ""
                // Notes must retain spaces and newlines verbatim.
                val secret = if (type == Entry.OTPType.NOTE || type == Entry.OTPType.SSH_KEY)
                    rawSecret else rawSecret.replace("\\s+".toRegex(), "")

                // Only validate the typed secret for new entries; editing keeps the existing secret.
                if (isNewEntry && type != Entry.OTPType.NOTE && type != Entry.OTPType.SSH_KEY &&
                        !Entry.validateSecret(secret, type)) {
                    secretInput.setError(callingActivity.getString(R.string.error_invalid_secret))
                    return@setOnClickListener
                }
                if (isNewEntry && type == Entry.OTPType.MOTP &&
                        (secret.isEmpty() ||
                            secret.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size > InputPolicy.MAX_SECRET_BYTES)) {
                    secretInput.setError(callingActivity.getString(R.string.error_invalid_secret))
                    return@setOnClickListener
                }

                val algorithm = algorithmInput.getSelectedItem() as? TokenCalculator.HashAlgorithm
                    ?: TokenCalculator.HashAlgorithm.SHA1
                val digits = if (type == Entry.OTPType.NOTE || type == Entry.OTPType.SSH_KEY) TokenCalculator.TOTP_DEFAULT_DIGITS
                    else java.lang.Integer.parseInt(digitsInput.text?.toString() ?: "0")

                val issuer = issuerInput.text?.toString() ?: ""
                val label = labelInput.text?.toString() ?: ""

                if (type == Entry.OTPType.SSH_KEY) {
                    val publicKey = sshPublicInput.text?.toString() ?: ""
                    if (label.isBlank()) throw IllegalArgumentException("An SSH key needs a title")
                    if (isNewEntry) {
                        adapter.addEntry(Entry.sshKey(publicKey, secret, issuer, label, tagsAdapter.getActiveTags()))
                    } else {
                        oldEntry!!.applyEdit { edited ->
                            edited.setLabel(label)
                            edited.setIssuer(issuer, true)
                            edited.setTags(ArrayList(tagsAdapter.getActiveTags()))
                            edited.setSshKeys(publicKey, secret)
                        }
                        updateCallback?.onUpdate()
                    }
                    callingActivity.refreshTags()
                } else if (type == Entry.OTPType.NOTE) {
                    InputPolicy.note(secret)
                    if (label.isBlank()) throw IllegalArgumentException("A note needs a title")
                    if (isNewEntry) {
                        adapter.addEntry(Entry(type, secret, issuer, label, tagsAdapter.getActiveTags()))
                    } else {
                        oldEntry!!.applyEdit { edited ->
                            edited.setLabel(label)
                            edited.setIssuer(issuer, true)
                            edited.setTags(ArrayList(tagsAdapter.getActiveTags()))
                            edited.setNote(secret)
                        }
                        updateCallback?.onUpdate()
                    }
                    callingActivity.refreshTags()
                } else if (type == Entry.OTPType.TOTP || type == Entry.OTPType.STEAM) {
                    val period = java.lang.Integer.parseInt(periodInput.text?.toString() ?: "0")

                    if (isNewEntry) {
                        val validated = Entry(type, secret, period, digits, issuer, label, algorithm, tagsAdapter.getActiveTags())
                        validated.destroy()
                        val e = Entry(type, secret, period, digits, issuer, label, algorithm, tagsAdapter.getActiveTags())
                        e.updateOTP(false)
                        e.setLastUsed(System.currentTimeMillis())

                        adapter.addEntry(e)
                    } else {
                        // applyEdit() rolls the live entry back when the model rejects the new
                        // values; otherwise a too-large digits/period stayed on the entry and the
                        // next token refresh or vault write threw again.
                        oldEntry!!.applyEdit { edited ->
                            edited.setIssuer(issuer, true)
                            edited.setLabel(label)
                            edited.setDigits(digits)
                            edited.setPeriod(period)
                            edited.setTags(ArrayList(tagsAdapter.getActiveTags()))
                        }

                        oldEntry.updateOTP(true)

                        updateCallback?.onUpdate()
                    }

                    callingActivity.refreshTags()
                } else if (type == Entry.OTPType.HOTP) {
                    val counter = java.lang.Long.parseLong(counterInput.text?.toString() ?: "0")

                    if (isNewEntry) {
                        val validated = Entry(type, secret, counter, digits, issuer, label, algorithm, tagsAdapter.getActiveTags())
                        validated.destroy()
                        val e = Entry(type, secret, counter, digits, issuer, label, algorithm, tagsAdapter.getActiveTags())
                        e.updateOTP(false)
                        e.setLastUsed(System.currentTimeMillis())

                        adapter.addEntry(e)
                    } else {
                        oldEntry!!.applyEdit { edited ->
                            edited.setIssuer(issuer, true)
                            edited.setLabel(label)
                            edited.setDigits(digits)
                            edited.setCounter(counter)
                            edited.setTags(ArrayList(tagsAdapter.getActiveTags()))
                        }

                        oldEntry.updateOTP(true)

                        updateCallback?.onUpdate()
                    }

                    callingActivity.refreshTags()
                } else if (type == Entry.OTPType.MOTP) {
                    if (isNewEntry) {
                        val validated = Entry(type, secret, issuer, label, tagsAdapter.getActiveTags())
                        validated.destroy()
                        val newEntry = Entry(type, secret, issuer, label, tagsAdapter.getActiveTags())
                        newEntry.updateOTP(false)
                        newEntry.setLastUsed(System.currentTimeMillis())

                        adapter.addEntry(newEntry)
                    } else {
                        oldEntry!!.applyEdit { edited ->
                            edited.setIssuer(issuer, true)
                            edited.setLabel(label)
                            edited.setTags(ArrayList(tagsAdapter.getActiveTags()))
                        }

                        oldEntry.updateOTP(false)

                        updateCallback?.onUpdate()
                    }

                    callingActivity.refreshTags()
                }

                dialog.dismiss()
            } catch (error: RuntimeException) {
                // The secret field is hidden in the edit path, so its error would never be seen;
                // a rejected period/digits/counter needs a visible message.
                secretInput.setError(callingActivity.getString(R.string.error_invalid_entry))
                Toast.makeText(callingActivity, R.string.error_invalid_entry, Toast.LENGTH_LONG).show()
            }
        }

        positiveButton.isEnabled = false

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(charSequence: CharSequence?, i: Int, i1: Int, i2: Int) {}

            override fun onTextChanged(charSequence: CharSequence?, i: Int, i1: Int, i2: Int) {}

            override fun afterTextChanged(editable: Editable?) {
                if (typeInput.getSelectedItem() == Entry.OTPType.SSH_KEY) {
                    positiveButton.isEnabled = !labelInput.text.isNullOrBlank() && try {
                        SshKeyMaterial.encode(sshPublicInput.text?.toString() ?: "",
                            secretInput.text?.toString() ?: "").fill(0)
                        true
                    } catch (_: IllegalArgumentException) { false }
                    return
                }
                if (typeInput.getSelectedItem() == Entry.OTPType.NOTE) {
                    val note = secretInput.text?.toString() ?: ""
                    positiveButton.isEnabled = !labelInput.text.isNullOrBlank() && try {
                        InputPolicy.note(note)
                        true
                    } catch (_: IllegalArgumentException) { false }
                    return
                }
                if ((TextUtils.isEmpty(labelInput.text) && TextUtils.isEmpty(issuerInput.text)) ||
                        (TextUtils.isEmpty(secretInput.text) && isNewEntry) ||
                        !isNonZeroIntegerInput(digitsInput)) {
                    positiveButton.isEnabled = false
                } else {
                    val type = typeInput.getSelectedItem() as? Entry.OTPType
                    if (type == Entry.OTPType.HOTP) {
                        // Same bound the entry model applies (Long.MAX_VALUE is rejected), so Save
                        // is disabled instead of failing after the dialog closes.
                        positiveButton.isEnabled =
                            EntryActionDialogs.isValidCounter(counterInput.text?.toString() ?: "")
                    } else if (type == Entry.OTPType.TOTP || type == Entry.OTPType.STEAM) {
                        positiveButton.isEnabled = isNonZeroIntegerInput(periodInput)
                    } else {
                        positiveButton.isEnabled = true
                    }
                }
            }

            /** True for a non-empty, non-zero integer input. */
            private fun isNonZeroIntegerInput(editText: EditText): Boolean {
                try {
                    val text = editText.text
                    return !TextUtils.isEmpty(text) && (Integer.parseInt(text.toString()) != 0)
                } catch (e: NumberFormatException) {
                    return false
                }
            }

        }

        validationWatcher = watcher
        labelInput.addTextChangedListener(watcher)
        issuerInput.addTextChangedListener(watcher)
        secretInput.addTextChangedListener(watcher)
        sshPublicInput.addTextChangedListener(watcher)
        periodInput.addTextChangedListener(watcher)
        digitsInput.addTextChangedListener(watcher)
        counterInput.addTextChangedListener(watcher)

        if (!isNewEntry) {
            otpUriInput.visibility = View.GONE
            applyOtpUriButton.visibility = View.GONE
            val oldType = oldEntry!!.getType()

            typeInput.setSelection(typeAdapter.getPosition(oldType))
            issuerInput.setText(oldEntry.getIssuer())
            labelInput.setText(oldEntry.getLabel())
            if (oldType != Entry.OTPType.NOTE && oldType != Entry.OTPType.SSH_KEY)
                secretView.setText(oldEntry.getSecretEncoded())
            digitsInput.setText(String.format(Locale.ENGLISH, "%d", oldEntry.getDigits()))
            algorithmInput.setSelection(algorithmAdapter.getPosition(oldEntry.getAlgorithm()))

            if (oldType == Entry.OTPType.TOTP || oldType == Entry.OTPType.STEAM) {
                periodInput.setText(String.format(Locale.ENGLISH, "%d", oldEntry.getPeriod()))
            } else if (oldType == Entry.OTPType.HOTP) {
                counterInput.setText(String.format(Locale.ENGLISH, "%d", oldEntry.getCounter()))
            }

            for (tag in oldEntry.getTags()) {
                tagsAdapter.setTagState(tag, true)
            }
            try {
                tagsCallable.call()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            secretInput.visibility = View.GONE
            secretView.visibility = View.VISIBLE

            // Little hack: match the color and background of the TextView to that of a disabled EditText
            secretInput.isEnabled = false
            secretView.background = secretInput.background
            secretView.setTextColor(secretInput.getTextColors()
                .getColorForState(secretInput.drawableState,
                    ContextCompat.getColor(callingActivity, R.color.colorPrimary)))

            if (oldType == Entry.OTPType.NOTE || oldType == Entry.OTPType.SSH_KEY) {
                secretView.visibility = View.GONE
                secretInput.visibility = View.VISIBLE
                secretInput.isEnabled = true
                if (oldType == Entry.OTPType.SSH_KEY) {
                    val keys = oldEntry.getSshKeys()
                    secretInput.setText(keys.privateKey)
                    sshPublicInput.setText(keys.publicKey)
                    sshPublicLayout.visibility = View.VISIBLE
                } else secretInput.setText(oldEntry.getNote())
            }
            typeInput.isEnabled = false
            algorithmInput.isEnabled = false
            digitsInput.isEnabled = oldType != Entry.OTPType.STEAM && oldType != Entry.OTPType.NOTE && oldType != Entry.OTPType.SSH_KEY
            periodInput.isEnabled = oldType != Entry.OTPType.STEAM
        }
    }

    /** Notifies the caller that an edited entry was saved. */
    fun interface UpdateCallback {
        fun onUpdate()
    }
}
