/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Headless round-trip of the desktop preferences export through the real properties-backed store:
 * the two portable settings survive a file, the vault's own `auth_*` keys never appear in one, and
 * the file is addressed to the desktop so another client refuses it.
 */
package com.fairuniversal.offlineotp.desktop.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.fairuniversal.offlineotp.Utilities.PreferencesExport
import com.fairuniversal.offlineotp.Utilities.VaultSettingsKeys
import java.io.File
import java.nio.file.Files

class PreferencesExportDesktopTest {

    private fun newSettings(): DesktopSettings {
        val dir = Files.createTempDirectory("offlineotp-prefs-").toFile()
        return DesktopSettings(File(dir, DesktopStorage.SETTINGS_FILE))
    }

    @Test
    fun thePortableSettingsSurviveAFileAndTheVaultKeysDoNot() {
        val source = newSettings()
        source.putString(PreferencesExport.UI_SORT_MODE, "AZ")
        source.putInt(PreferencesExport.UI_INACTIVITY_LOCK_SECONDS, 120)
        // Vault material shares the same store but belongs to the vault, not to the app.
        source.putString(VaultSettingsKeys.AUTH_SALT, "AAECAwQFBgcICQoLDA0ODw==")
        source.putInt(VaultSettingsKeys.AUTH_ITERATIONS, 4096)
        source.putString(VaultSettingsKeys.AUTH_METHOD, "password")

        val export = PreferencesExport.export(
            DesktopPreferencesPort(source), includeSecrets = false,
            platform = PreferencesExport.PLATFORM_DESKTOP)
        try {
            assertEquals(2, export.keysWritten)
            assertFalse(export.includesSecrets)
            val text = String(export.bytes, Charsets.UTF_8)
            // The salt and the KDF parameters describe a particular vault and must not travel.
            for (key in listOf(VaultSettingsKeys.AUTH_SALT, VaultSettingsKeys.AUTH_ITERATIONS,
                VaultSettingsKeys.AUTH_METHOD)) {
                assertFalse("$key leaked: $text", text.contains("\"$key\""))
            }

            val target = newSettings()
            val result = PreferencesExport.import(
                export.bytes, DesktopPreferencesPort(target), PreferencesExport.PLATFORM_DESKTOP)
            assertEquals(2, result.applied)
            assertEquals("AZ", target.getString(PreferencesExport.UI_SORT_MODE, ""))
            assertEquals(120, target.getInt(PreferencesExport.UI_INACTIVITY_LOCK_SECONDS, 0))
            // The importing store's own vault keys are untouched: an import never half-converts a vault.
            assertEquals("", target.getString(VaultSettingsKeys.AUTH_SALT, ""))
        } finally {
            export.zero()
        }
    }

    @Test
    fun aDesktopFileIsRefusedByAndroidAndViceVersa() {
        val settings = newSettings()
        settings.putString(PreferencesExport.UI_SORT_MODE, "AZ")
        val desktopFile = PreferencesExport.export(
            DesktopPreferencesPort(settings), includeSecrets = false,
            platform = PreferencesExport.PLATFORM_DESKTOP)
        try {
            // The desktop adapter is the only thing that may apply it; the format says so itself.
            val text = String(desktopFile.bytes, Charsets.UTF_8)
            assertTrue(text, text.contains("\"platform\":\"${PreferencesExport.PLATFORM_DESKTOP}\""))
            try {
                PreferencesExport.import(desktopFile.bytes, DesktopPreferencesPort(newSettings()),
                    PreferencesExport.PLATFORM_ANDROID)
                throw AssertionError("a desktop file was accepted as an Android one")
            } catch (_: PreferencesExport.WrongPlatformException) { }
        } finally {
            desktopFile.zero()
        }
    }

    @Test
    fun theDesktopNeverClaimsToCarryASecret() {
        val settings = newSettings()
        settings.putString(PreferencesExport.UI_SORT_MODE, "AZ")
        // Asked for secrets, there is still none to give: this client stores no backup password.
        val export = PreferencesExport.export(
            DesktopPreferencesPort(settings), includeSecrets = true,
            platform = PreferencesExport.PLATFORM_DESKTOP)
        try {
            assertFalse(export.includesSecrets)
            val text = String(export.bytes, Charsets.UTF_8)
            assertFalse(text, text.contains(PreferencesExport.SECRET_BACKUP_PASSWORD))
        } finally {
            export.zero()
        }
    }
}
