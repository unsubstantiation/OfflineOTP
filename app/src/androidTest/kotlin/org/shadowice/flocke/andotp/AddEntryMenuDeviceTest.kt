/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Real-activity coverage for the direct add-menu actions. TalkBack and vendor-specific layout
 * behavior still require manual testing on devices.
 */
package org.shadowice.flocke.andotp

import android.app.AlertDialog
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leinardi.android.speeddial.FabWithLabelView
import com.leinardi.android.speeddial.SpeedDialView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Activities.MainActivity
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Dialogs.ManualEntryDialog
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper
import org.shadowice.flocke.andotp.Utilities.SessionCommandResult
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.VaultCommitDecision
import org.shadowice.flocke.andotp.Utilities.VaultReadResult
import org.shadowice.flocke.andotp.Utilities.VaultSession
import org.shadowice.flocke.andotp.Utilities.VaultWriteResult
import javax.crypto.SecretKey

@RunWith(AndroidJUnit4::class)
class AddEntryMenuDeviceTest {
    @Test
    fun directActionsValidateSaveAndRelockWithoutChangingManualEntry() = withMain { context, activity, key ->
        onMain {
            val dial = dial(activity)
            assertEquals(setOf(R.id.fabEnterDetails, R.id.fabAddSecureNote, R.id.fabAddSshKey,
                R.id.fabScanQRFromImage, R.id.fabScanQR), dial.getActionItems().map { it.getId() }.toSet())
            for (id in listOf(R.id.fabAddSecureNote, R.id.fabAddSshKey))
                assertTrue("the add action needs an accessibility label",
                    !action(activity, id).contentDescription.isNullOrBlank())
        }

        val note = showFromMenu(activity, R.id.fabAddSecureNote)
        val noteText = "🔐".repeat(198) + " \n" // exactly 200 Unicode code points
        onMain {
            assertEquals(Entry.OTPType.NOTE, field<Spinner>(note, R.id.manual_type).getSelectedItem())
            assertEquals(View.GONE, field<View>(note, R.id.manual_otp_uri).visibility)
            assertEquals(View.GONE, field<View>(note, R.id.manual_layout_ssh_public).visibility)
            assertEquals(View.GONE, field<View>(note, R.id.dialog_expand_button).visibility)
            val save = saveButton(note)
            val title = field<EditText>(note, R.id.manual_label)
            val body = field<EditText>(note, R.id.manual_secret)
            assertFalse(save.isEnabled)
            title.setText(" ")
            body.setText(noteText)
            assertFalse("a title is required", save.isEnabled)
            title.setText("Recovery note")
            assertTrue("200 characters should be accepted", save.isEnabled)
            body.setText(noteText + "x")
            assertFalse("201 characters should be rejected", save.isEnabled)
            body.setText(noteText)
            assertTrue(save.isEnabled)
            assertTrue(save.performClick())
        }
        idle()
        assertFalse(note.isShowing)

        val ssh = showFromMenu(activity, R.id.fabAddSshKey)
        val publicKey = "ssh-ed25519 example laptop\n"
        val privateKey = "-----BEGIN PRIVATE KEY-----\nexample\n-----END PRIVATE KEY-----\n"
        onMain {
            assertEquals(Entry.OTPType.SSH_KEY, field<Spinner>(ssh, R.id.manual_type).getSelectedItem())
            assertEquals(View.GONE, field<View>(ssh, R.id.manual_otp_uri).visibility)
            assertEquals(View.VISIBLE, field<View>(ssh, R.id.manual_layout_ssh_public).visibility)
            assertEquals(View.GONE, field<View>(ssh, R.id.dialog_expand_button).visibility)
            val save = saveButton(ssh)
            val title = field<EditText>(ssh, R.id.manual_label)
            val publicField = field<EditText>(ssh, R.id.manual_ssh_public)
            val privateField = field<EditText>(ssh, R.id.manual_secret)
            title.setText("Laptop SSH")
            assertFalse("at least one key is required", save.isEnabled)
            publicField.setText(publicKey)
            assertTrue("a public-only key is valid", save.isEnabled)
            publicField.setText("x".repeat(32 * 1024 + 1))
            assertFalse("the key byte limit must be enforced", save.isEnabled)
            publicField.setText(publicKey)
            privateField.setText(privateKey)
            assertTrue(save.isEnabled)
            assertTrue(save.performClick())
        }
        idle()
        assertFalse(ssh.isShowing)

        val stored = DatabaseHelper.readVault(context, key) as VaultReadResult.Loaded
        stored.use {
            val entries = stored.takeEntries()
            try {
                assertEquals(2, entries.size)
                val savedNote = entries.single { it.getType() == Entry.OTPType.NOTE }
                assertEquals("Recovery note", savedNote.getLabel())
                assertEquals(noteText, savedNote.getNote())
                val savedSsh = entries.single { it.getType() == Entry.OTPType.SSH_KEY }
                assertEquals("Laptop SSH", savedSsh.getLabel())
                assertEquals(publicKey, savedSsh.getSshKeys().publicKey)
                assertEquals(privateKey, savedSsh.getSshKeys().privateKey)
            } finally {
                entries.forEach { it.destroy() }
            }
        }

        val manual = showFromMenu(activity, R.id.fabEnterDetails)
        onMain {
            assertEquals(Entry.OTPType.TOTP, field<Spinner>(manual, R.id.manual_type).getSelectedItem())
            assertEquals(View.VISIBLE, field<View>(manual, R.id.manual_otp_uri).visibility)
            manual.dismiss()
        }
        idle()

        // A lock dismisses and wipes the same editor and prevents a stale Save or add action.
        val stale = showFromMenu(activity, R.id.fabAddSshKey)
        val staleSecret = field<EditText>(stale, R.id.manual_secret)
        val stalePublic = field<EditText>(stale, R.id.manual_ssh_public)
        val staleSave = saveButton(stale)
        onMain {
            field<EditText>(stale, R.id.manual_label).setText("Unsaved")
            staleSecret.setText("private material")
            stalePublic.setText("public material")
            assertTrue(staleSave.isEnabled)
            VaultSession.lock()
        }
        awaitUi("locking did not dismiss the editor") { !stale.isShowing && activeDialog() == null }
        onMain {
            assertFalse(stale.isShowing)
            assertEquals("", staleSecret.text.toString())
            assertEquals("", stalePublic.text.toString())
            assertNull(activeDialog())
            staleSave.performClick()
            dial(activity).open(false)
            action(activity, R.id.fabAddSecureNote).performClick()
        }
        idle()
        assertNull(activeDialog())
        val afterLock = DatabaseHelper.readVault(context, key) as VaultReadResult.Loaded
        afterLock.use {
            val entries = afterLock.takeEntries()
            try { assertEquals(2, entries.size) } finally { entries.forEach { it.destroy() } }
        }
    }

    @Test
    fun addLabelsAndManualEditorDropdownsUseReadableForestSurfaces() = withMain { _, activity, _ ->
        onMain { dial(activity).open(false) }
        idle()
        onMain {
            val surface = ThemeDeviceTestSupport.surface(activity)
            for (item in dial(activity).getActionItems()) {
                val row = requireNotNull(dial(activity).findViewById<FabWithLabelView>(item.getId()))
                val label = row.getLabelBackground()
                ThemeDeviceTestSupport.assertDrawableColor("add-menu label", requireNotNull(label.background),
                    surface, activity)
                ThemeDeviceTestSupport.assertTextReadable("add-menu label text",
                    requireNotNull(ThemeDeviceTestSupport.firstText(label)), surface)
            }
            dial(activity).close()
        }
        idle()

        val editor = showFromMenu(activity, R.id.fabEnterDetails)
        onMain {
            val surface = ThemeDeviceTestSupport.surface(activity)
            ThemeDeviceTestSupport.assertDialogSurface("manual entry", editor.window, activity)
            for (id in listOf(R.id.manual_type, R.id.manual_algorithm)) {
                val spinner = field<Spinner>(editor, id)
                ThemeDeviceTestSupport.assertDrawableColor("editor dropdown", requireNotNull(spinner.getPopupBackground()),
                    surface, activity)
                val adapter = spinner.adapter as ArrayAdapter<*>
                val row = adapter.getDropDownView(0, null, spinner)
                ThemeDeviceTestSupport.assertTextReadable("editor dropdown row",
                    requireNotNull(ThemeDeviceTestSupport.firstText(row)), surface)
            }
            assertTrue("the type dropdown did not open", field<Spinner>(editor, R.id.manual_type).performClick())
            editor.dismiss()
        }
        idle()
    }

    @Test
    fun shortAddMenuScrollsAllActionsAndReceivesTouchesAboveOverlay() = withMain { _, activity, _ ->
        // A 220dp viewport exercises the same overflow as short landscape/multi-window screens,
        // regardless of the emulator's physical screen size or orientation.
        onMain {
            val scroll = scroll(activity)
            val params = requireNotNull(scroll.layoutParams)
            params.height = (220 * activity.resources.displayMetrics.density).toInt()
            scroll.layoutParams = params
        }
        idle()
        onMain { dial(activity).open(false) }
        awaitUi("expanded menu did not lay out at the bottom") {
            dial(activity).height > scroll(activity).height && scroll(activity).scrollY > 0 &&
                isUsablyVisible(dial(activity).getMainFab())
        }
        onMain {
            assertTrue("the constrained menu should overflow", dial(activity).height > scroll(activity).height)
            assertTrue("opening should keep the main FAB at the bottom", scroll(activity).scrollY > 0)
            assertUsablyVisible(dial(activity).getMainFab())
        }

        // Keyboard/accessibility focus must be able to bring every action into the viewport.
        for (id in listOf(R.id.fabScanQR, R.id.fabScanQRFromImage, R.id.fabAddSshKey,
                          R.id.fabAddSecureNote, R.id.fabEnterDetails)) {
            onMain { assertTrue("action $id could not receive focus", action(activity, id).requestFocus()) }
            awaitUi("action $id did not scroll into view") { isUsablyVisible(action(activity, id)) }
            onMain { assertUsablyVisible(action(activity, id)) }
        }

        // At the top of the scroll, tap SSH through the actual view hierarchy (not performClick),
        // so an overlay accidentally drawn above the scroll container will fail this test.
        onMain { scroll(activity).scrollTo(0, 0) }
        idle()
        onMain {
            val sshButton = action(activity, R.id.fabAddSshKey)
            assertUsablyVisible(sshButton)
            tapView(activity, sshButton)
        }
        awaitUi("touch did not open the SSH editor") {
            val dialog = activeDialog()
            dialog != null && dialog.isShowing &&
                field<Spinner>(dialog, R.id.manual_type).getSelectedItem() == Entry.OTPType.SSH_KEY
        }
        val ssh = requireNotNull(activeDialog())
        onMain {
            assertEquals(Entry.OTPType.SSH_KEY, field<Spinner>(ssh, R.id.manual_type).getSelectedItem())
            ssh.dismiss()
        }
        idle()

        // The area outside the menu remains a working dismiss overlay.
        onMain { dial(activity).open(false) }
        idle()
        onMain {
            val overlay = requireNotNull(activity.findViewById<View>(R.id.speedDialOverlay))
            val scroll = scroll(activity)
            val overlayPos = IntArray(2)
            val scrollPos = IntArray(2)
            overlay.getLocationInWindow(overlayPos)
            scroll.getLocationInWindow(scrollPos)
            val margin = (4 * activity.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val leftGap = scrollPos[0] - overlayPos[0]
            val rightGap = overlayPos[0] + overlay.width - scrollPos[0] - scroll.width
            val topGap = scrollPos[1] - overlayPos[1]
            val point = when {
                leftGap > 2 * margin -> (overlayPos[0] + margin) to (overlayPos[1] + overlay.height / 2)
                rightGap > 2 * margin -> (overlayPos[0] + overlay.width - margin) to (overlayPos[1] + overlay.height / 2)
                topGap > 2 * margin -> (overlayPos[0] + overlay.width / 2) to (overlayPos[1] + margin)
                else -> throw AssertionError("no space outside the constrained menu")
            }
            tap(activity, point.first.toFloat(), point.second.toFloat())
            assertFalse("tapping outside should close the menu", dial(activity).isOpen())
            val params = requireNotNull(scroll.layoutParams)
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            scroll.layoutParams = params
        }
    }

    private fun withMain(block: (Context, MainActivity, SecretKey) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DeviceTestSupport.finishActivities()
        VaultSession.lock()
        assumeTrue("refusing to overwrite an existing vault", DatabaseHelper.canInitialize(context))
        DeviceTestSupport.freshPreferences(context)
        val settings = Settings(context)
        settings.setBoolean(R.string.settings_key_dialog_hide_auto_time, true)
        val key = EncryptionHelper.generateSymmetricKey(ByteArray(32) { 0x21 })
        try {
            val initialized = DatabaseHelper.changeVaultEncryption(context, null, key, true) { id ->
                VaultCommitDecision.fromCommit(settings.commitVaultSettings(id, Constants.EncryptionType.PASSWORD,
                    Constants.AuthMethod.NONE, null, true))
            }
            assertTrue("could not create a disposable vault", initialized is VaultWriteResult.Saved)
            val generation = VaultSession.beginUnlock().generation
            assertTrue(VaultSession.activate(key, generation) is SessionCommandResult.Applied)
            DeviceTestSupport.startActivity(context, MainActivity::class.java)
            val activity = requireNotNull(DeviceTestSupport.awaitResumed<MainActivity>())
            block(context, activity, key)
        } finally {
            DeviceTestSupport.finishActivities()
            VaultSession.lock()
            DatabaseHelper.wipeDatabase(context)
            DeviceTestSupport.wipePreferences(context)
        }
    }

    private fun showFromMenu(activity: MainActivity, id: Int): AlertDialog {
        onMain {
            assertFalse(dial(activity).isOpen())
            assertTrue("the add button did not handle a click", dial(activity).getMainFab().performClick())
        }
        idle()
        onMain { assertTrue("the add action did not handle a click", action(activity, id).performClick()) }
        val expected = when (id) {
            R.id.fabAddSecureNote -> Entry.OTPType.NOTE
            R.id.fabAddSshKey -> Entry.OTPType.SSH_KEY
            else -> Entry.OTPType.TOTP
        }
        awaitUi("the add action did not open its editor") {
            val dialog = activeDialog()
            dialog != null && dialog.isShowing &&
                field<Spinner>(dialog, R.id.manual_type).getSelectedItem() == expected &&
                field<View>(dialog, R.id.manual_otp_uri).visibility ==
                    (if (expected == Entry.OTPType.TOTP) View.VISIBLE else View.GONE)
        }
        return requireNotNull(activeDialog())
    }

    private fun dial(activity: MainActivity): SpeedDialView =
        requireNotNull(activity.findViewById(R.id.speedDial))

    private fun scroll(activity: MainActivity): ScrollView =
        requireNotNull(activity.findViewById(R.id.speedDialScroll))

    private fun action(activity: MainActivity, id: Int): View =
        requireNotNull(dial(activity).findViewById<FabWithLabelView>(id)).getFab()

    private fun activeDialog(): AlertDialog? = ManualEntryDialog::class.java.getDeclaredField("activeDialog")
        .apply { isAccessible = true }.get(null) as? AlertDialog

    private fun <T : View> field(dialog: AlertDialog, id: Int): T = requireNotNull(dialog.findViewById(id))

    private fun saveButton(dialog: AlertDialog): Button = requireNotNull(dialog.getButton(AlertDialog.BUTTON_POSITIVE))

    private fun isUsablyVisible(view: View): Boolean {
        val rect = Rect()
        // The library uses compat shadow padding and a negative FAB margin. Allow a few clipped
        // pixels while requiring the center and most of the touch target to remain visible.
        return view.width > 0 && view.height > 0 && view.getGlobalVisibleRect(rect) &&
            rect.width() * 4 >= view.width * 3 && rect.height() * 4 >= view.height * 3
    }

    private fun assertUsablyVisible(view: View) {
        assertTrue("view was invisible or mostly clipped", isUsablyVisible(view))
    }

    private fun awaitUi(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            idle()
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(40)
        }
        throw AssertionError(message)
    }

    private fun tapView(activity: MainActivity, view: View) {
        val position = IntArray(2)
        view.getLocationInWindow(position)
        tap(activity, position[0] + view.width / 2f, position[1] + view.height / 2f)
    }

    private fun tap(activity: MainActivity, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, x, y, 0)
        try {
            assertTrue("touch down was not handled", activity.dispatchTouchEvent(down))
            assertTrue("touch up was not handled", activity.dispatchTouchEvent(up))
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()
}
