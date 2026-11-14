package com.fairuniversal.offlineotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesExportTest {

    /** A store with an explicit map, so a test can seed exactly what it means to seed. */
    private class FakePort(
        private val values: LinkedHashMap<String, PreferencesExport.Value> = LinkedHashMap(),
        private val extraStored: MutableSet<String> = LinkedHashSet(),
        private val backupPassword: String? = null
    ) : PreferencesExport.SettingsPort {
        val writes = LinkedHashMap<String, PreferencesExport.Value>()

        fun seed(key: String, value: PreferencesExport.Value): FakePort {
            values[key] = value
            return this
        }

        override fun storedKeys(): Set<String> = values.keys + extraStored

        override fun read(key: String): PreferencesExport.Value? = values[key]

        override fun readBackupPassword(): String? = backupPassword

        override fun write(key: String, value: PreferencesExport.Value) {
            writes[key] = value
        }

    }

    private fun full(): FakePort = FakePort(backupPassword = "backup-pw")
        .seed("pref_auth", PreferencesExport.Value.Text("password"))
        .seed("pref_auth_inactivity", PreferencesExport.Value.Bool(true))
        .seed("pref_auth_inactivity_timeout", PreferencesExport.Value.IntValue(30))
        .seed("pref_search_includes", PreferencesExport.Value.TextSet(listOf("label", "issuer")))

    @Test
    fun roundTripKeepsEveryTypeAndOrder() {
        val source = full()
        val export = PreferencesExport.export(source, includeSecrets = true)
        try {
            assertTrue(export.includesSecrets)
            assertEquals(5, export.keysWritten)
            val target = FakePort()
            val result = PreferencesExport.import(export.bytes, target)
            assertEquals(5, result.applied)
            assertTrue(result.includesSecrets)
            assertEquals(PreferencesExport.Value.Text("password"), target.writes["pref_auth"])
            assertEquals(PreferencesExport.Value.Bool(true), target.writes["pref_auth_inactivity"])
            assertEquals(PreferencesExport.Value.IntValue(30), target.writes["pref_auth_inactivity_timeout"])
            assertEquals(PreferencesExport.Value.TextSet(listOf("label", "issuer")),
                target.writes["pref_search_includes"])
            // The backup password is the one secret that travels, through its own port method.
            assertEquals(PreferencesExport.Value.Text("backup-pw"),
                target.writes[PreferencesExport.SECRET_BACKUP_PASSWORD])
        } finally { export.zero() }
    }

    @Test
    fun anAbsentSettingIsNotWrittenAsItsDefault() {
        val source = FakePort().seed("pref_auth_inactivity", PreferencesExport.Value.Bool(false))
        val export = PreferencesExport.export(source, includeSecrets = false)
        val target = FakePort()
        PreferencesExport.import(export.bytes, target)
        // Only what was stored is written: an import must not "helpfully" set the other 50 defaults.
        assertEquals(setOf("pref_auth_inactivity"), target.writes.keys)
    }

    @Test
    fun secretsAreOmittedUnlessAskedForAndTheFlagReflectsReality() {
        val source = full()
        val export = PreferencesExport.export(source, includeSecrets = false)
        assertFalse(export.includesSecrets)
        // The four non-secret settings in full(); the backup password is not among them.
        assertEquals(4, export.keysWritten)
        val target = FakePort()
        val result = PreferencesExport.import(export.bytes, target)
        assertFalse(result.includesSecrets)
        assertTrue(target.writes.keys.none { it.startsWith("secret_") })
    }

    @Test
    fun deviceBoundKeysNeverAppearAndAreRefusedOnImport() {
        // The Keystore-wrapped backup password, the vault KDF parameters and the SAF tree URI all
        // look like ordinary settings in the store; none of them may leave this device.
        val source = FakePort()
            .seed("pref_backup_password_enc", PreferencesExport.Value.Text("keystore-wrapped"))
            .seed("pref_auth_salt", PreferencesExport.Value.Text("c2FsdA=="))
            .seed("pref_auth_iterations", PreferencesExport.Value.IntValue(128000))
            .seed("pref_hardware_unlock", PreferencesExport.Value.Text("envelope"))
            .seed("pref_backup_location", PreferencesExport.Value.Text("content://tree/x"))
            .seed("pref_openpgp_secret_file", PreferencesExport.Value.Text("content://key/secret"))
            .seed("pref_openpgp_recipient_files", PreferencesExport.Value.TextSet(listOf("content://key/a.asc")))
            .seed("pref_auth", PreferencesExport.Value.Text("password"))
        val export = PreferencesExport.export(source, includeSecrets = true)
        val text = String(export.bytes, Charsets.UTF_8)
        for (key in listOf("pref_backup_password_enc", "pref_auth_salt", "pref_auth_iterations",
            "pref_hardware_unlock", "pref_backup_location", "pref_openpgp_secret_file",
            "pref_openpgp_recipient_files")) {
            assertFalse("$key leaked: $text", text.contains(key))
        }
        assertTrue(text, text.contains("pref_auth"))
        // Device-bound keys are not even named: a shareable file must not disclose which security
        // feature this device has configured. They are documented statically instead.
        for (key in listOf("pref_backup_password_enc", "pref_hardware_unlock")) {
            assertFalse("$key enumerated as skipped", export.skipped.contains(key))
            assertNotNull(PreferencesExport.whyNotExported(key))
        }
        // A hand-edited file cannot smuggle one back in.
        val doctored = text.replace("\"pref_auth\"", "\"pref_auth_salt\":\"c2FsdA==\",\"pref_auth\"")
        val target = FakePort()
        val result = PreferencesExport.import(doctored.toByteArray(), target)
        assertTrue(result.refusedKeys.contains("pref_auth_salt"))
        assertNull(target.writes["pref_auth_salt"])
    }

    @Test
    fun unknownKeysAreSkippedAndReportedRatherThanFailingTheImport() {
        // A file written by a newer release must still restore what this build understands.
        val source = full()
        val export = PreferencesExport.export(source, includeSecrets = false)
        val text = String(export.bytes, Charsets.UTF_8)
            .replace("\"pref_auth\"", "\"pref_from_the_future\":{\"x\":1},\"pref_auth\"")
        val target = FakePort()
        val result = PreferencesExport.import(text.toByteArray(), target)
        assertTrue(result.unknownKeys.contains("pref_from_the_future"))
        assertEquals(PreferencesExport.Value.Text("password"), target.writes["pref_auth"])
    }

    @Test
    fun aWrongTypeOrForeignDocumentIsRejectedBeforeAnythingIsWritten() {
        val source = full()
        val export = PreferencesExport.export(source, includeSecrets = false)
        val text = String(export.bytes, Charsets.UTF_8)
        val target = FakePort()

        // Type mismatch: an int where the catalog says boolean. Nothing may be applied at all.
        val badType = text.replace("\"pref_auth_inactivity\":true", "\"pref_auth_inactivity\":7")
        try {
            PreferencesExport.import(badType.toByteArray(), target)
            throw AssertionError("accepted a boolean key with an integer value")
        } catch (_: IllegalArgumentException) { }
        assertTrue(target.writes.isEmpty())

        // Not our format / wrong version / garbage / empty.
        for (document in listOf(
            "{\"format\":\"something.else\",\"version\":1,\"settings\":{}}",
            "{\"format\":\"${PreferencesExport.FORMAT}\",\"version\":99,\"settings\":{}}",
            "not json",
            "")) {
            try {
                PreferencesExport.import(document.toByteArray(), target)
                throw AssertionError("accepted $document")
            } catch (_: IllegalArgumentException) { }
        }
        assertTrue(target.writes.isEmpty())
        try {
            PreferencesExport.import(ByteArray(PreferencesExport.MAX_BYTES + 1), target)
            throw AssertionError("accepted an oversized document")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun anUnreadableBackupPasswordIsOmittedRatherThanExportedEmpty() {
        // A reinstall cannot unwrap the old Keystore key: there is no password to move, and writing
        // an empty one would silently disable the other device's backups.
        val source = FakePort(backupPassword = null)
            .seed("pref_auth", PreferencesExport.Value.Text("password"))
        val export = PreferencesExport.export(source, includeSecrets = true)
        val text = String(export.bytes, Charsets.UTF_8)
        assertFalse(text, text.contains(PreferencesExport.SECRET_BACKUP_PASSWORD))
        // Nothing secret was written, so a client must not warn about secrets.
        assertFalse(export.includesSecrets)
    }

    @Test
    fun aSecretValueNeverPrintsItself() {
        val secret = PreferencesExport.Value.Text("hunter2-a-long-password")
        assertFalse(secret.toString().contains("hunter2"))
        assertTrue(secret.toString().contains("chars"))
    }

    @Test
    fun theCatalogAndTheRefusalListAreConsistent() {
        // Every device-bound key must be documented, and no key may be both exportable and refused.
        assertTrue(PreferencesExport.neverExported.isNotEmpty())
        for ((key, reason) in PreferencesExport.neverExported) {
            assertTrue("empty reason for $key", reason.isNotBlank())
            assertTrue("$key is both catalogued and refused", PreferencesExport.setting(key) == null)
        }
        // The backup password is the one secret with a dedicated port; it is catalogued as SECRET.
        val backup = PreferencesExport.setting(PreferencesExport.SECRET_BACKUP_PASSWORD)
        assertNotNull(backup)
        assertEquals(PreferencesExport.Kind.SECRET, backup!!.kind)
        // No duplicate keys in the catalog.
        assertEquals(PreferencesExport.catalog.size, PreferencesExport.catalog.map { it.key }.toSet().size)
    }

    @Test
    fun aFileAddressedToAnotherClientIsRefusedInsteadOfHalfApplied() {
        // The desktop's stored keys are not Android's: applying an Android document there would
        // quietly restore the handful of keys that happen to collide and nothing else.
        val source = full()
        val androidDocument = PreferencesExport.export(source, includeSecrets = false)
        val desktopPort = FakePort()
        try {
            PreferencesExport.import(androidDocument.bytes, desktopPort, PreferencesExport.PLATFORM_DESKTOP)
            throw AssertionError("an Android document was accepted as a desktop one")
        } catch (error: PreferencesExport.WrongPlatformException) {
            assertEquals(PreferencesExport.PLATFORM_ANDROID, error.source)
            assertEquals(PreferencesExport.PLATFORM_DESKTOP, error.target)
        }
        assertTrue(desktopPort.writes.isEmpty())

        // The same document round-trips on its own platform, and states which one that is.
        val text = String(androidDocument.bytes, Charsets.UTF_8)
        assertTrue(text, text.contains("\"platform\":\"${PreferencesExport.PLATFORM_ANDROID}\""))
        val roundTrip = PreferencesExport.import(androidDocument.bytes, FakePort(), PreferencesExport.PLATFORM_ANDROID)
        assertEquals(4, roundTrip.applied)

        // A desktop document carries the desktop's own settings and restores on the desktop.
        val desktopSource = FakePort()
            .seed(PreferencesExport.UI_CARD_LAYOUT, PreferencesExport.Value.Text("COMPACT"))
            .seed(PreferencesExport.UI_SORT_MODE, PreferencesExport.Value.Text("AZ"))
            .seed(PreferencesExport.UI_INACTIVITY_LOCK_SECONDS, PreferencesExport.Value.IntValue(120))
        val desktopExport = PreferencesExport.export(
            desktopSource, includeSecrets = false, platform = PreferencesExport.PLATFORM_DESKTOP)
        assertEquals(3, desktopExport.keysWritten)
        val target = FakePort()
        val desktopResult = PreferencesExport.import(
            desktopExport.bytes, target, PreferencesExport.PLATFORM_DESKTOP)
        assertEquals(3, desktopResult.applied)
        assertEquals(PreferencesExport.Value.Text("COMPACT"), target.writes[PreferencesExport.UI_CARD_LAYOUT])
        assertEquals(PreferencesExport.Value.Text("AZ"), target.writes[PreferencesExport.UI_SORT_MODE])
        assertEquals(PreferencesExport.Value.IntValue(120),
            target.writes[PreferencesExport.UI_INACTIVITY_LOCK_SECONDS])
    }

    @Test
    fun theNewestSettingFromThisReleaseIsExportable() {
        // Retention (#925) is a plain string; if it were device-bound the setting could not move.
        val retention = PreferencesExport.setting("pref_backup_auto_keep")
        assertNotNull(retention)
        assertEquals(PreferencesExport.Kind.STRING, retention!!.kind)
        assertFalse(PreferencesExport.isDeviceBound("pref_backup_auto_keep"))
    }
}
