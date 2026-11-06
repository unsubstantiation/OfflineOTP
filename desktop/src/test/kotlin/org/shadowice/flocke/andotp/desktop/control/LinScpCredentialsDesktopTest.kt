package org.shadowice.flocke.andotp.desktop.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.LinScpCredentials
import org.shadowice.flocke.andotp.Utilities.SshConnection
import org.shadowice.flocke.andotp.desktop.storage.DesktopStorage
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class LinScpCredentialsDesktopTest {
    private val vaultPass = "desktop-vault-password"

    @Test
    fun desktopBackupRestoreAndEntryEditorRoundTripLinScpVaultAndSessionsIni() {
        val dir = Files.createTempDirectory("offlineotp-linscp-").toFile()
        val controller = VaultController(DesktopStorage.open(dir))
        val loaded = controller.createVault(vaultPass) as VaultController.Session.Loaded
        val state = DesktopVaultState(loaded.key)
        val editor = EntryEditor(controller, state)

        val ini = """
            [Core Router]
            protocol=ssh
            host=router.internal
            port=22
            username=admin
            password=router-pass
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val importedIni = LinScpCredentials.importBytes(ini)
        assertEquals(1, editor.importEntries(importedIni))

        val sshEntry = Entry.sshKey(
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIRouter admin@router.internal",
            "-----BEGIN OPENSSH PRIVATE KEY-----\ncm91dGVyLWtleQ==\n-----END OPENSSH PRIVATE KEY-----\n",
            "router.internal",
            "Router Key",
            arrayListOf("infra"),
            SshConnection.of("router.internal", 22, "admin", "router-pass")
        )
        assertTrue(editor.add(sshEntry))

        val exportedBytes = LinScpCredentials.exportVault(
            state.snapshot(),
            "linscp-interchange-pass"
        )

        // Standard Desktop encrypted backup restore opens LSCPVLT2 files directly too!
        val dir2 = Files.createTempDirectory("offlineotp-linscp-2-").toFile()
        val controller2 = VaultController(DesktopStorage.open(dir2))
        val loaded2 = controller2.createVault(vaultPass) as VaultController.Session.Loaded
        val state2 = DesktopVaultState(loaded2.key)
        assertEquals(
            2,
            BackupCoordinator(controller2, state2).restoreEncrypted(
                "linscp-interchange-pass",
                exportedBytes
            )
        )
        val restoredEntries = state2.snapshot()
        assertEquals(2, restoredEntries.size)
        assertTrue(restoredEntries.any { it.isSshKey() && it.getLabel() == "Router Key" })
        assertTrue(restoredEntries.any { it.isPassword() && it.getLabel() == "Core Router" })
    }
}
