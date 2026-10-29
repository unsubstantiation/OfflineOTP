/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Exercise the actual framework preference dialogs and the AppCompat password dialog. In
 * particular, inspect the window drawable itself: AppCompat's stock drawable is white when a
 * framework AlertDialog loads it without AppCompat's tint manager.
 */
package org.shadowice.flocke.andotp

import android.app.AlertDialog
import android.content.Context
import android.os.SystemClock
import android.preference.DialogPreference
import android.preference.EditTextPreference
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.NumberPicker
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Activities.IntroScreenActivity
import org.shadowice.flocke.andotp.Activities.SettingsActivity
import org.shadowice.flocke.andotp.Dialogs.PasswordEntryDialog
import org.shadowice.flocke.andotp.Preferences.NumberPickerPreference
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.SessionCommandResult
import org.shadowice.flocke.andotp.Utilities.VaultSession
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class AndroidThemeDeviceTest {
    @Test
    fun settingsDialogsRemainDarkAndReadableForEveryLegacyThemeHook() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("refusing to clear preferences beside an existing vault", DatabaseHelper.canInitialize(context))
        DeviceTestSupport.finishActivities()
        val preferences = DeviceTestSupport.freshPreferences(context)
        val generation = VaultSession.beginUnlock().generation
        assertTrue(VaultSession.activate(SecretKeySpec(ByteArray(32) { 0x5a }, "AES"), generation)
            is SessionCommandResult.Applied)
        try {
            for (theme in listOf("light", "dark", "black")) {
                preferences.edit()
                    .putString(context.getString(R.string.settings_key_theme_mode), "manual")
                    .putString(context.getString(R.string.settings_key_theme), theme)
                    .commit()
                ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    var canceledInput: EditText? = null
                    var canceledConfirmation: EditText? = null
                    scenario.onActivity { activity ->
                        assertFalse(activity.isFinishing())
                        val fragment = activity.getFragmentManager().findFragmentById(R.id.container_content)
                            as SettingsActivity.SettingsFragment
                        val surface = ThemeDeviceTestSupport.surface(activity)

                        // Framework single-choice ListPreference.
                        val single = openPreference(activity, R.string.settings_key_tap_single)
                        try {
                            assertDialog("$theme single choice", single, activity)
                            assertListReadable("single choice", requireNotNull(single.getListView()), surface)
                        } finally { single.cancel() }

                        // Framework MultiSelectListPreference: a canceled change must not persist.
                        val multiKey = activity.getString(R.string.settings_key_search_includes)
                        val before = preferences.getStringSet(multiKey, emptySet())!!.toSet()
                        val multi = openPreference(activity, R.string.settings_key_search_includes)
                        try {
                            assertDialog("$theme multi choice", multi, activity)
                            val list = requireNotNull(multi.getListView())
                            assertListReadable("multi choice", list, surface)
                            val adapter = requireNotNull(list.adapter)
                            val checked = list.isItemChecked(0)
                            assertTrue(list.performItemClick(adapter.getView(0, null, list), 0, adapter.getItemId(0)))
                            assertEquals(!checked, list.isItemChecked(0))
                        } finally { multi.cancel() }
                        assertEquals(before, preferences.getStringSet(multiKey, emptySet())!!.toSet())

                        // Custom number picker preference uses programmatically created widgets.
                        val numberPref = fragment.findPreference(activity.getString(R.string.settings_key_label_size))
                            as NumberPickerPreference
                        val oldValue = numberPref.getValue()
                        val number = openPreference(activity, R.string.settings_key_label_size)
                        try {
                            assertDialog("$theme number picker", number, activity)
                            val picker = requireNotNull(ThemeDeviceTestSupport.firstView(
                                requireNotNull(number.window).decorView, NumberPicker::class.java))
                            val input = requireNotNull(ThemeDeviceTestSupport.firstView(picker, EditText::class.java))
                            ThemeDeviceTestSupport.assertTextReadable("number picker text", input, surface)
                            picker.value = if (picker.value < picker.maxValue) picker.value + 1 else picker.minValue
                        } finally { number.cancel() }
                        assertEquals(oldValue, numberPref.getValue())

                        // The OpenPGP editor is normally disabled until a provider is selected. Enable
                        // it only in this test so the real EditTextPreference dialog can be inspected.
                        val editPref = fragment.findPreference(activity.getString(R.string.settings_key_openpgp_key_encrypt))
                            as EditTextPreference
                        editPref.isEnabled = false
                        val settingsList = requireNotNull(activity.findViewById<ListView>(android.R.id.list))
                        val settingsAdapter = requireNotNull(settingsList.adapter)
                        val editPosition = (0 until settingsAdapter.getCount()).first { settingsAdapter.getItem(it) === editPref }
                        val disabledRow = settingsAdapter.getView(editPosition, null, settingsList)
                        val disabledText = requireNotNull(ThemeDeviceTestSupport.firstText(disabledRow))
                        assertFalse(disabledText.isEnabled)
                        // The actual Settings background is darker than the elevated surface;
                        // using the latter here is the stricter contrast check.
                        ThemeDeviceTestSupport.assertTextReadable("disabled Settings row", disabledText, surface)
                        val oldText = editPref.text
                        val edit = openPreference(activity, R.string.settings_key_openpgp_key_encrypt)
                        try {
                            assertDialog("$theme edit text", edit, activity)
                            val input = requireNotNull(edit.findViewById<EditText>(android.R.id.edit))
                            ThemeDeviceTestSupport.assertTextReadable("edit preference", input, surface)
                            input.setText("not saved")
                        } finally { edit.cancel() }
                        assertEquals(oldText, editPref.text)

                        // Authentication selector and its password controls are a custom framework
                        // DialogPreference with its own list, labels, inputs and buttons.
                        val credentials = openPreference(activity, R.string.settings_key_auth)
                        try {
                            assertDialog("$theme credentials", credentials, activity, standardButtons = false)
                            val list = requireNotNull(credentials.findViewById<ListView>(R.id.credentialSelection))
                            assertListReadable("credential selection", list, surface)
                            val adapter = requireNotNull(list.adapter)
                            assertTrue(list.performItemClick(adapter.getView(1, null, list), 1, adapter.getItemId(1)))
                            val input = requireNotNull(credentials.findViewById<EditText>(R.id.passwordEdit))
                            ThemeDeviceTestSupport.assertTextReadable("credential input", input, surface)
                            ThemeDeviceTestSupport.assertTextReadable("credential hint", input, surface, hint = true)
                            val cancel = requireNotNull(credentials.findViewById<Button>(R.id.btnCancel))
                            ThemeDeviceTestSupport.assertTextReadable("credential cancel", cancel, surface)
                            assertTrue(cancel.performClick())
                            assertFalse(credentials.isShowing)
                        } finally { if (credentials.isShowing) credentials.cancel() }

                        // Encrypted backup-password preference: cancellation clears, not saves, input.
                        val password = openPreference(activity, R.string.settings_key_backup_password_enc)
                        try {
                            assertDialog("$theme backup password", password, activity, standardButtons = false)
                            val input = requireNotNull(password.findViewById<EditText>(R.id.passwordEdit))
                            val confirmation = requireNotNull(password.findViewById<EditText>(R.id.passwordConfirm))
                            ThemeDeviceTestSupport.assertTextReadable("backup password input", input, surface)
                            ThemeDeviceTestSupport.assertTextReadable("backup password hint", input, surface, hint = true)
                            input.setText("temporary test password")
                            confirmation.setText("temporary test password")
                            assertTrue(requireNotNull(password.findViewById<Button>(R.id.btnSave)).isEnabled)
                            canceledInput = input
                            canceledConfirmation = confirmation
                            assertTrue(requireNotNull(password.findViewById<Button>(R.id.btnCancel)).performClick())
                            assertFalse(password.isShowing)
                        } finally { if (password.isShowing) password.cancel() }
                    }
                    // Dialog's dismiss listener is posted to the main looper; wait for the
                    // preference's onDialogClosed before checking that it wiped both fields.
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity {
                        assertEquals("", requireNotNull(canceledInput).text.toString())
                        assertEquals("", requireNotNull(canceledConfirmation).text.toString())
                    }
                }
            }
        } finally {
            DeviceTestSupport.finishActivities()
            DeviceTestSupport.wipePreferences(context)
        }
    }

    @Test
    fun ordinaryFrameworkAlertAndAppCompatPasswordPromptUseTheSameSurface() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("refusing to clear preferences beside an existing vault", DatabaseHelper.canInitialize(context))
        DeviceTestSupport.finishActivities()
        DeviceTestSupport.freshPreferences(context)
        val generation = VaultSession.beginUnlock().generation
        assertTrue(VaultSession.activate(SecretKeySpec(ByteArray(32) { 0x41 }, "AES"), generation)
            is SessionCommandResult.Applied)
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val surface = ThemeDeviceTestSupport.surface(activity)
                    val alert = AlertDialog.Builder(activity)
                        .setTitle(R.string.settings_dialog_title_error)
                        .setMessage(R.string.settings_dialog_msg_encryption_invalid_with_auth)
                        .setPositiveButton(android.R.string.ok, null)
                        .create()
                    alert.show()
                    try {
                        assertDialog("ordinary alert", alert, activity, standardButtons = false)
                        val message = requireNotNull(alert.findViewById<android.widget.TextView>(android.R.id.message))
                        ThemeDeviceTestSupport.assertTextReadable("alert message", message, surface)
                        ThemeDeviceTestSupport.assertTextReadable("alert button",
                            requireNotNull(alert.getButton(AlertDialog.BUTTON_POSITIVE)), surface)
                    } finally { alert.dismiss() }

                    val prompt = PasswordEntryDialog(activity, PasswordEntryDialog.Mode.ENTER, false, false, null)
                    prompt.show()
                    try {
                        ThemeDeviceTestSupport.assertDialogSurface("AppCompat password prompt", prompt.window, activity)
                        val input = requireNotNull(prompt.findViewById<EditText>(R.id.passwordInput))
                        ThemeDeviceTestSupport.assertTextReadable("password prompt input", input, surface)
                        ThemeDeviceTestSupport.assertTextReadable("password prompt hint", input, surface, hint = true)
                        ThemeDeviceTestSupport.assertTextReadable("password prompt cancel",
                            requireNotNull(prompt.findViewById<Button>(R.id.buttonCancel)), surface)
                    } finally { prompt.dismiss() }
                }
            }
        } finally {
            DeviceTestSupport.finishActivities()
            DeviceTestSupport.wipePreferences(context)
        }
    }

    @Test
    fun onboardingSlidesAndDropdownsRemainLegibleIncludingDisabledChoices() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("refusing to clear preferences beside an existing vault", DatabaseHelper.canInitialize(context))
        DeviceTestSupport.finishActivities()
        DeviceTestSupport.freshPreferences(context)
        try {
            ActivityScenario.launch(IntroScreenActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(5, activity.getCount())
                    for (index in 0 until activity.getCount())
                        assertEquals(R.color.forest_surface, activity.getBackground(index))
                    assertTrue(activity.goToSlide(1))
                }
                awaitIntroSlide(scenario, 1, R.id.introEncryptionSelection)
                scenario.onActivity { activity ->
                    val background = ContextCompat.getColor(activity, R.color.forest_surface)
                    val description = requireNotNull(activity.findViewById<TextView>(R.id.introEncryptionDesc))
                    ThemeDeviceTestSupport.assertTextReadable("onboarding description", description, background)
                    val spinner = requireNotNull(activity.findViewById<Spinner>(R.id.introEncryptionSelection))
                    assertSpinnerReadable(activity, spinner, "onboarding encryption")
                    assertTrue(activity.goToSlide(2))
                }
                awaitIntroSlide(scenario, 2, R.id.introAuthSelection)
                scenario.onActivity { activity ->
                    val spinner = requireNotNull(activity.findViewById<Spinner>(R.id.introAuthSelection))
                    assertSpinnerReadable(activity, spinner, "onboarding authentication")
                    val adapter = spinner.adapter as ArrayAdapter<*>
                    // PASSWORD encryption disables the "none" option; it must stay legible too.
                    val disabled = adapter.getDropDownView(0, null, spinner)
                    assertFalse(disabled.isEnabled)
                    ThemeDeviceTestSupport.assertTextReadable("disabled onboarding choice",
                        requireNotNull(ThemeDeviceTestSupport.firstText(disabled)), ThemeDeviceTestSupport.surface(activity))
                }
            }
        } finally {
            DeviceTestSupport.finishActivities()
            DeviceTestSupport.wipePreferences(context)
        }
    }

    private fun awaitIntroSlide(scenario: ActivityScenario<IntroScreenActivity>, position: Int, id: Int) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            var ready = false
            scenario.onActivity { activity ->
                ready = activity.currentSlidePosition == position && activity.findViewById<Spinner>(id) != null
            }
            if (ready) return
            SystemClock.sleep(40)
        }
        throw AssertionError("onboarding slide $position did not become visible")
    }

    private fun assertSpinnerReadable(context: Context, spinner: Spinner, label: String) {
        val surface = ThemeDeviceTestSupport.surface(context)
        ThemeDeviceTestSupport.assertDrawableColor("$label popup", requireNotNull(spinner.getPopupBackground()),
            surface, context)
        val adapter = spinner.adapter as ArrayAdapter<*>
        val row = adapter.getDropDownView(1, null, spinner)
        ThemeDeviceTestSupport.assertTextReadable("$label choice", requireNotNull(ThemeDeviceTestSupport.firstText(row)), surface)
    }

    private fun openPreference(activity: SettingsActivity, keyId: Int): AlertDialog {
        val fragment = activity.getFragmentManager().findFragmentById(R.id.container_content)
            as SettingsActivity.SettingsFragment
        val preference = requireNotNull(fragment.findPreference(activity.getString(keyId)))
        preference.isEnabled = true
        val list = requireNotNull(activity.findViewById<ListView>(android.R.id.list))
        val adapter = requireNotNull(list.adapter)
        val position = (0 until adapter.getCount()).firstOrNull { adapter.getItem(it) === preference }
        assertNotNull("preference has no Settings row", position)
        val index = requireNotNull(position)
        assertTrue("preference row did not open", list.performItemClick(null, index, adapter.getItemId(index)))
        val dialog = (preference as DialogPreference).getDialog() as AlertDialog?
        assertNotNull("preference did not create a framework dialog", dialog)
        assertTrue(requireNotNull(dialog).isShowing)
        return dialog
    }

    private fun assertDialog(label: String, dialog: AlertDialog, context: Context, standardButtons: Boolean = true) {
        ThemeDeviceTestSupport.assertDialogSurface(label, dialog.window, context)
        if (standardButtons) {
            val cancel = requireNotNull(dialog.getButton(AlertDialog.BUTTON_NEGATIVE))
            ThemeDeviceTestSupport.assertTextReadable("$label cancel", cancel, ThemeDeviceTestSupport.surface(context))
        }
    }

    private fun assertListReadable(label: String, list: ListView, surface: Int) {
        val adapter = requireNotNull(list.adapter)
        assertTrue("$label is empty", adapter.getCount() > 0)
        val row = adapter.getView(0, null, list)
        val text = requireNotNull(ThemeDeviceTestSupport.firstText(row))
        ThemeDeviceTestSupport.assertTextReadable("$label row", text, surface)
    }
}
