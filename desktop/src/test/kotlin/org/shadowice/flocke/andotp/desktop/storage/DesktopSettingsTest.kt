/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Headless vectors for the properties-backed desktop settings port: typed accessors, defaults,
 * removal and persistence across a freshly loaded instance.
 */
package org.shadowice.flocke.andotp.desktop.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Utilities.VaultSettingsKeys
import java.io.File
import java.nio.file.Files

class DesktopSettingsTest {

    private fun newSettings(): Pair<File, DesktopSettings> {
        val dir = Files.createTempDirectory("offlineotp-settings-").toFile()
        val file = File(dir, DesktopStorage.SETTINGS_FILE)
        return Pair(file, DesktopSettings(file))
    }

    @Test
    fun stringsAndIntsRoundTripAndUseDefaults() {
        val (file, settings) = newSettings()
        assertEquals("fallback", settings.getString("missing", "fallback"))
        assertEquals(42, settings.getInt("missing-int", 42))

        settings.putString(VaultSettingsKeys.AUTH_SALT, "AAECAwQFBgcICQoLDA0ODw==")
        settings.putInt(VaultSettingsKeys.AUTH_ITERATIONS, 4096)

        assertEquals("AAECAwQFBgcICQoLDA0ODw==",
            settings.getString(VaultSettingsKeys.AUTH_SALT, ""))
        assertEquals(4096, settings.getInt(VaultSettingsKeys.AUTH_ITERATIONS, 0))
        assertTrue(file.exists())
    }

    @Test
    fun valuesPersistAcrossANewInstanceAndCanBeRemoved() {
        val (file, settings) = newSettings()
        settings.putString(VaultSettingsKeys.AUTH_CREDENTIALS, "verifier==")
        settings.putInt(VaultSettingsKeys.AUTH_ITERATIONS, 30000)

        val reloaded = DesktopSettings(file)
        assertEquals("verifier==", reloaded.getString(VaultSettingsKeys.AUTH_CREDENTIALS, ""))
        assertEquals(30000, reloaded.getInt(VaultSettingsKeys.AUTH_ITERATIONS, 0))

        reloaded.remove(VaultSettingsKeys.AUTH_CREDENTIALS)
        assertEquals("", reloaded.getString(VaultSettingsKeys.AUTH_CREDENTIALS, ""))

        val afterRemoval = DesktopSettings(file)
        assertEquals("", afterRemoval.getString(VaultSettingsKeys.AUTH_CREDENTIALS, ""))
        assertEquals(30000, afterRemoval.getInt(VaultSettingsKeys.AUTH_ITERATIONS, 0))
    }
}
