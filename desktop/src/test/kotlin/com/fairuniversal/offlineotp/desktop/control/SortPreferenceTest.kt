package com.fairuniversal.offlineotp.desktop.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.PasswordVault
import com.fairuniversal.offlineotp.Utilities.TokenCalculator
import com.fairuniversal.offlineotp.desktop.storage.DesktopSettings
import com.fairuniversal.offlineotp.desktop.storage.NioAtomicFileStore

import java.io.File
import java.nio.file.Files

/**
 * The desktop list order is a remembered preference, like Android's `getSortMode()`: it must
 * survive a restart, live in the client settings file rather than the encrypted vault, and fall
 * back to the vault order when the stored value is unusable.
 */
class SortPreferenceTest {

    private fun directory(): File = Files.createTempDirectory("offlineotp-sort-").toFile()

    private fun controller(directory: File): VaultController = VaultController(PasswordVault(
        NioAtomicFileStore(File(directory, "secrets.dat")),
        NioAtomicFileStore(File(directory, "secrets.dat.bck")),
        DesktopSettings(File(directory, "vault.properties"))))

    private fun entry(issuer: String, label: String): Entry =
        Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, issuer, label,
            TokenCalculator.HashAlgorithm.SHA1, ArrayList())

    @Test
    fun theChosenOrderIsRememberedAcrossSessions() {
        val directory = directory()
        try {
            val first = controller(directory)
            assertTrue(first.createVault("sort-test-password") is VaultController.Session.Loaded)
            // Nothing chosen yet: the vault order, exactly like a fresh Android install.
            assertEquals(Constants.SortMode.UNSORTED, first.savedSortMode())

            first.saveSortMode(Constants.SortMode.MOST_USED)
            assertEquals(Constants.SortMode.MOST_USED, first.savedSortMode())
            assertEquals(Constants.SortMode.MOST_USED, controller(directory).savedSortMode())

            // The order is a client preference, never part of the encrypted vault document.
            val settings = File(directory, "vault.properties").readText(Charsets.ISO_8859_1)
            assertTrue(settings, settings.contains("ui_sort_mode=MOST_USED"))
            val vault = File(directory, "secrets.dat").readBytes()
            assertTrue("the vault must not carry the preference in clear",
                vault.none { it.toInt() == 'M'.code } || String(vault, Charsets.ISO_8859_1)
                    .indexOf("MOST_USED") < 0)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun anUnreadableStoredOrderFallsBackInsteadOfThrowing() {
        val directory = directory()
        try {
            val controller = controller(directory)
            assertTrue(controller.createVault("sort-test-password") is VaultController.Session.Loaded)
            controller.saveSortMode(Constants.SortMode.LABEL)
            // Simulate a downgrade/corruption: the file names a mode this build does not know.
            val settings = File(directory, "vault.properties")
            settings.writeText(settings.readText(Charsets.ISO_8859_1)
                .replace("ui_sort_mode=LABEL", "ui_sort_mode=NOT_A_MODE"), Charsets.ISO_8859_1)
            assertEquals(Constants.SortMode.UNSORTED, controller(directory).savedSortMode())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun theStateAppliesTheSavedOrderOnLoad() {
        val key = javax.crypto.KeyGenerator.getInstance("AES").generateKey()
        val state = DesktopVaultState(key)
        state.presentation.setSortMode(Constants.SortMode.ISSUER)
        val beta = entry("Beta", "b")
        val alpha = entry("Alpha", "a")
        state.load(arrayListOf(beta, alpha))
        // load() rebuilds the projection through the presentation, which keeps the chosen order.
        assertEquals("Alpha", state.presentation.get(0).getIssuer())
        assertEquals("Beta", state.presentation.get(1).getIssuer())
    }
}
