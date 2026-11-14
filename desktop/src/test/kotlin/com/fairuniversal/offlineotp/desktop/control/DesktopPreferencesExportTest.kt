package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.PreferencesExport
import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPreferencesExportTest {
    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = tasks.removeAt(0).run()
    }
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32) { 1 }, "AES"))
    @Test fun exportedFileContainsOnlyPortableCataloguedPreferences() {
        val dir = Files.createTempDirectory("compose-preferences-").toFile()
        val storage = DesktopStorage.open(dir); val controller = VaultController(storage)
        val loaded = controller.createVault("vault-password") as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key); val worker = Queue(); val ui = Queue()
        val exporter = DesktopPreferencesExport(state, worker, ui::execute, controller::exportPreferences, {})
        try {
            controller.saveSortMode(Constants.SortMode.LABEL)
            controller.trySaveInactivityLockSeconds(60); controller.trySaveCardLayout(Constants.CardLayouts.COMPACT)
            storage.preferences.putString("secret_backup_password", "must-not-export")
            storage.preferences.putString("unknown", "also-not-exported")
            val target = File(dir, "prefs.json")
            assertTrue(exporter.submit(target) { true }); assertFalse(target.exists())
            assertFalse(exporter.submit(target) { true })
            worker.run(); assertEquals(DesktopPreferencesExport.Status.WRITING, exporter.status)
            ui.run(); assertEquals(DesktopPreferencesExport.Status.SAVED, exporter.status)
            assertEquals(3, exporter.count)
            val text = target.readText()
            assertFalse(text.contains("auth_")); assertFalse(text.contains("must-not-export"))
            assertFalse(text.contains("vault-password")); assertFalse(text.contains("also-not-exported"))
            val keys = ArrayList<String>()
            val result = PreferencesExport.import(target.readBytes(), object : PreferencesExport.SettingsPort {
                override fun storedKeys(): Set<String> = emptySet()
                override fun read(key: String): PreferencesExport.Value? = null
                override fun readBackupPassword(): String? = null
                override fun write(key: String, value: PreferencesExport.Value) { keys.add(key) }
            }, PreferencesExport.PLATFORM_DESKTOP)
            assertEquals(3, result.applied)
            assertEquals(setOf(PreferencesExport.UI_CARD_LAYOUT, PreferencesExport.UI_SORT_MODE, PreferencesExport.UI_INACTIVITY_LOCK_SECONDS), keys.toSet())
            exporter.dismiss(); exporter.submit(target) { true }; worker.run(); ui.run()
            assertEquals(DesktopPreferencesExport.Status.EXISTS, exporter.status); assertEquals(text, target.readText())
        } finally { exporter.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun canceledQueuedAndLateWorkCannotPublishSuccessAndBuffersAreWiped() {
        val state = state(); val worker = Queue(); val ui = Queue(); var writes = 0
        var bytes = byteArrayOf(1, 2)
        val exporter = DesktopPreferencesExport(state, worker, ui::execute,
            { PreferencesExport.Export(bytes, 1, emptyList()) }, {}, { _, _ -> writes++ })
        try {
            exporter.submit(File("unused")) { true }; exporter.dismiss(); worker.run()
            assertEquals(0, writes); assertTrue(bytes.all { it == 0.toByte() })
            bytes = byteArrayOf(3, 4)
            exporter.submit(File("unused")) { true }; worker.run()
            assertEquals(1, writes); assertTrue(bytes.all { it == 0.toByte() })
            exporter.close(); ui.run(); assertTrue(exporter.status == null)
            assertFalse(exporter.submit(File("unused")) { true })
        } finally { exporter.close(); state.lock() }
    }
    @Test fun failedWritesAndExpiredSnapshotsAreCleanedWithoutSuccess() {
        val state = state(); val worker = Queue(); val ui = Queue(); var active = true; var expire = false
        var bytes = byteArrayOf(7)
        val exporter = DesktopPreferencesExport(state, worker, ui::execute, {
            if (expire) active = false
            PreferencesExport.Export(bytes, 1, emptyList())
        }, {}, { _, _ -> throw java.io.IOException("private path") })
        try {
            exporter.submit(File("unused")) { active }; worker.run(); ui.run()
            assertEquals(DesktopPreferencesExport.Status.FAILED, exporter.status)
            assertTrue(bytes.all { it == 0.toByte() })
            bytes = byteArrayOf(8); expire = true
            assertFalse(exporter.submit(File("unused")) { active })
            assertTrue(bytes.all { it == 0.toByte() }); assertTrue(worker.tasks.isEmpty())
        } finally { exporter.close(); state.lock() }
    }
}
