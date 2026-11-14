package com.fairuniversal.offlineotp.desktop.storage

import com.fairuniversal.offlineotp.Utilities.PreferencesExport
import java.io.IOException
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPreferencesImportPlanTest {
    private fun document(): ByteArray {
        val dir = Files.createTempDirectory("prefs-source-").toFile()
        try {
            val source = DesktopSettings(java.io.File(dir, "prefs"))
            source.putString(PreferencesExport.UI_SORT_MODE, "LABEL")
            source.putInt(PreferencesExport.UI_INACTIVITY_LOCK_SECONDS, 60)
            source.putString(PreferencesExport.UI_CARD_LAYOUT, "COMPACT")
            return PreferencesExport.export(DesktopPreferencesPort(source), false, PreferencesExport.PLATFORM_DESKTOP).bytes
        } finally { dir.deleteRecursively() }
    }
    @Test fun preparationDoesNotWriteAndApplyPromotesExactlyOncePreservingUnmentionedKeys() {
        val dir = Files.createTempDirectory("prefs-target-").toFile()
        val file = java.io.File(dir, "prefs"); var writes = 0
        val target = DesktopSettings(file) { path, bytes -> writes++; AtomicFiles.writeAtomic(path, bytes) }
        try {
            target.putString("auth_verifier", "keep-private")
            target.putString("future_setting", "keep")
            val before = file.readBytes(); val count = writes
            val bytes = document(); val plan = DesktopPreferencesImportPlan.prepare(bytes); bytes.fill(0)
            assertEquals(count, writes); assertTrue(before.contentEquals(file.readBytes()))
            target.putString("future_setting", "changed after preview")
            val priorWrites = writes
            plan.applyTo(target)
            assertEquals(priorWrites + 1, writes)
            val reopened = DesktopSettings(file)
            assertEquals("LABEL", reopened.getString(PreferencesExport.UI_SORT_MODE, ""))
            assertEquals(60, reopened.getInt(PreferencesExport.UI_INACTIVITY_LOCK_SECONDS, 0))
            assertEquals("COMPACT", reopened.getString(PreferencesExport.UI_CARD_LAYOUT, ""))
            assertEquals("keep-private", reopened.getString("auth_verifier", ""))
            assertEquals("changed after preview", reopened.getString("future_setting", ""))
            assertFalse(plan.toString().contains("COMPACT"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun failedSingleWriteLeavesEntireDiskAndCacheUnchangedAndRetrySucceeds() {
        val dir = Files.createTempDirectory("prefs-failure-").toFile()
        val file = java.io.File(dir, "prefs"); var fail = false; var writes = 0
        val target = DesktopSettings(file) { path, bytes ->
            writes++
            if (fail) throw IOException("disk full before promotion")
            AtomicFiles.writeAtomic(path, bytes)
        }
        try {
            target.putString(PreferencesExport.UI_SORT_MODE, "UNSORTED")
            target.putInt(PreferencesExport.UI_INACTIVITY_LOCK_SECONDS, 900)
            val before = file.readBytes(); val beforeKeys = target.keys(); val count = writes
            val plan = DesktopPreferencesImportPlan.prepare(document()); fail = true
            var rejected = false
            try { plan.applyTo(target) } catch (_: IOException) { rejected = true }
            assertTrue(rejected); assertEquals(count + 1, writes)
            assertEquals(beforeKeys, target.keys()); assertTrue(before.contentEquals(file.readBytes()))
            assertEquals("UNSORTED", target.getString(PreferencesExport.UI_SORT_MODE, ""))
            assertEquals(900, target.getInt(PreferencesExport.UI_INACTIVITY_LOCK_SECONDS, 0))
            assertEquals("absent", target.getString(PreferencesExport.UI_CARD_LAYOUT, "absent"))
            fail = false; plan.applyTo(target)
            assertEquals(60, DesktopSettings(file).getInt(PreferencesExport.UI_INACTIVITY_LOCK_SECONDS, 0))
        } finally { dir.deleteRecursively() }
    }
    @Test fun invalidWrongPlatformAndWrongTypesNeverProduceApplicablePlan() {
        val source = JSONObject(String(document(), Charsets.UTF_8))
        for (bytes in listOf("invalid".toByteArray(),
            JSONObject(source.toString()).put("platform", "android").toString().toByteArray(),
            JSONObject(source.toString()).apply { getJSONObject("settings").put(PreferencesExport.UI_INACTIVITY_LOCK_SECONDS, "wrong-type") }.toString().toByteArray())) {
            var rejected = false
            try { DesktopPreferencesImportPlan.prepare(bytes) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
    }
    @Test fun unknownAndDeviceBoundKeysAreNotAppliedAndEmptyPlanDoesNotWrite() {
        val doc = JSONObject(String(document(), Charsets.UTF_8))
        doc.put("settings", JSONObject().put("auth_verifier", "attack").put("future_key", "ignored"))
        val plan = DesktopPreferencesImportPlan.prepare(doc.toString().toByteArray())
        assertEquals(0, plan.applied)
        assertEquals(2, plan.unknownCount + plan.refusedCount)
        val dir = Files.createTempDirectory("prefs-empty-").toFile()
        var writes = 0
        try {
            val target = DesktopSettings(java.io.File(dir, "prefs")) { _, _ -> writes++ }
            plan.applyTo(target); assertEquals(0, writes); assertTrue(target.keys().isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
