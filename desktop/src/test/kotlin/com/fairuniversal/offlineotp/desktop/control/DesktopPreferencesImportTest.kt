package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.PreferencesExport
import com.fairuniversal.offlineotp.Utilities.ScreenTimerController
import com.fairuniversal.offlineotp.desktop.storage.DesktopPreferencesImportPlan
import com.fairuniversal.offlineotp.desktop.storage.DesktopStorage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPreferencesImportTest {
    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = tasks.removeAt(0).run()
    }
    private class Clock : ScreenTimerController.Scheduler {
        var now = 0L
        override fun elapsedRealtime() = now
        override fun postDelayed(runnable: Runnable, delayMillis: Long) {}
        override fun remove(runnable: Runnable) {}
    }
    private fun state() = DesktopVaultState(SecretKeySpec(ByteArray(32) { 1 }, "AES"))
    private fun document(): ByteArray {
        val dir = Files.createTempDirectory("preferences-source-").toFile()
        try {
            val controller = VaultController(DesktopStorage.open(dir))
            controller.trySaveInactivityLockSeconds(0)
            // Disabled is absent in ordinary exports; explicitly include it for the expiry test.
            val store = DesktopStorage.open(dir)
            store.preferences.putInt(PreferencesExport.UI_INACTIVITY_LOCK_SECONDS, 0)
            val c = VaultController(store); c.trySaveCardLayout(Constants.CardLayouts.FULL)
            return c.exportPreferences().bytes
        } finally { dir.deleteRecursively() }
    }
    @Test fun boundedReadPreviewsThenAppliesAndReopensWithoutTouchingOmittedSettings() {
        val dir = Files.createTempDirectory("preferences-import-").toFile()
        val controller = VaultController(DesktopStorage.open(dir)); controller.saveSortMode(Constants.SortMode.LABEL)
        val state = state(); val worker = Queue(); val ui = Queue()
        val importer = DesktopPreferencesImport(state, worker, ui::execute, {}, controller::importPreferences)
        try {
            val file = File(dir, "prefs.json").apply { writeBytes(document()) }
            assertTrue(importer.open(file) { true }); val request = importer.pending!!
            assertFalse(importer.open(file) { true })
            worker.run(); ui.run()
            assertEquals(2, request.plan!!.applied)
            assertEquals(Constants.CardLayouts.DEFAULT, controller.savedCardLayout())
            var refreshed = false
            assertTrue(importer.confirm(request, { true }) { plan ->
                assertTrue(plan.contains(PreferencesExport.UI_CARD_LAYOUT))
                assertFalse(plan.contains(PreferencesExport.UI_SORT_MODE)); refreshed = true
            })
            assertTrue(refreshed)
            val reopened = VaultController(DesktopStorage.open(dir))
            assertEquals(Constants.CardLayouts.FULL, reopened.savedCardLayout())
            assertEquals(Constants.SortMode.LABEL, reopened.savedSortMode())
            assertFalse(importer.confirm(request, { true }) {})
        } finally { importer.close(); state.lock(); dir.deleteRecursively() }
    }
    @Test fun slowCommitCannotDisableAnExpiredTimeout() {
        val state = state(); val worker = Queue(); val ui = Queue(); val clock = Clock(); var locks = 0
        val lifetime = DesktopSessionLifetime(clock, 30) { locks++; state.lock() }
        val plan = DesktopPreferencesImportPlan.prepare(document()); var commits = 0; var refreshes = 0
        val importer = DesktopPreferencesImport(state, worker, ui::execute, {}, { commits++; clock.now = 30_000; true }, { plan })
        try {
            importer.open(File("unused"), lifetime::checkActive); val request = importer.pending!!
            worker.run(); ui.run()
            assertFalse(importer.confirm(request, lifetime::checkActive) { refreshes++; lifetime.changeTimeout(0) })
            assertEquals(1, commits); assertEquals(0, refreshes); assertEquals(1, locks)
            assertTrue(importer.appliedCount == null)
        } finally { importer.close(); lifetime.close(); state.lock() }
    }
    @Test fun canceledAndExpiredWorkerResultsCannotBeConfirmed() {
        val state = state(); val worker = Queue(); val ui = Queue(); var reads = 0; var active = true
        val plan = DesktopPreferencesImportPlan.prepare(document())
        val importer = DesktopPreferencesImport(state, worker, ui::execute, {}, { error("commit") }, { reads++; plan })
        try {
            importer.open(File("unused")) { active }; val old = importer.pending!!
            importer.dismiss(old); worker.run(); assertEquals(0, reads)
            importer.open(File("unused")) { active }; val fresh = importer.pending!!
            worker.run(); active = false; ui.run()
            assertTrue(fresh.plan == null)
            assertFalse(importer.confirm(old, { true }) {})
            assertFalse(importer.confirm(fresh, { active }) {})
            importer.close(); assertFalse(importer.open(File("unused")) { true })
        } finally { importer.close(); state.lock() }
    }
    @Test fun invalidFilesAndFailedCommitDoNotRefreshLiveState() {
        val state = state(); val worker = Queue(); val ui = Queue(); var invalid = true; var refreshes = 0
        val plan = DesktopPreferencesImportPlan.prepare(document())
        val importer = DesktopPreferencesImport(state, worker, ui::execute, {}, { false }, {
            if (invalid) throw IllegalArgumentException("not a settings document") else plan
        })
        try {
            importer.open(File("unused")) { true }; val first = importer.pending!!; worker.run(); ui.run()
            assertTrue(first.failed); assertTrue(first.plan == null)
            importer.dismiss(first); invalid = false
            importer.open(File("unused")) { true }; val next = importer.pending!!; worker.run(); ui.run()
            assertFalse(importer.confirm(next, { true }) { refreshes++ })
            assertTrue(next.failed); assertEquals(0, refreshes)
        } finally { importer.close(); state.lock() }
    }
}
