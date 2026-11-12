package com.fairuniversal.offlineotp.ui.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultToolsTest {
    private fun actions(calls: MutableList<String>) = VaultActions(
        onStorageLocation = { calls.add("storage") },
        onChangePassword = { calls.add("rekey") },
        onExportArchives = { calls.add("archive-export") },
        onExportPasswords = { calls.add("password-export") },
        onExportEmail = { calls.add("email-export") },
        onAddEntry = { calls.add("add") },
        onImportUri = { calls.add("uri") }, onImportPasswords = { calls.add("passwords") },
        onImportEmail = { calls.add("email") }, onImportLinScp = { calls.add("linscp") },
        onImportPreferences = { calls.add("preferences-import") }, onExportPreferences = { calls.add("preferences") }, onExportOpenPgp = { calls.add("pgp-export") }, onImportOpenPgp = { calls.add("pgp") }, onExportLinScp = { calls.add("linscp-export") }, onExportEncrypted = { calls.add("export") }, onRestoreEncrypted = { calls.add("restore") },
        onConfigureLayout = { calls.add("layout") }, onConfigureAutoLock = { calls.add("lock") })

    @Test fun unavailableToolsAndEmptyGroupsAreAbsent() {
        assertTrue(VaultTool.available(VaultActions()).isEmpty())
        assertTrue(VaultTool.available(VaultActions(onAddEntry = {})).isEmpty())
        val tools = VaultTool.available(VaultActions(onImportUri = {}, onConfigureAutoLock = {}))
        assertEquals(listOf(VaultTool.IMPORT_URI, VaultTool.AUTO_LOCK), tools)
        assertEquals(listOf(VaultTool.Group.IMPORT, VaultTool.Group.SETTINGS), tools.map { it.group })
    }
    @Test fun everyToolRoutesOnceInStableGroupedOrderWithoutInvokingAdd() {
        val calls = ArrayList<String>()
        val actions = actions(calls)
        val tools = VaultTool.available(actions)
        assertEquals(VaultTool.values().toList(), tools)
        tools.forEach { it.invoke(actions) }
        assertEquals(listOf("uri", "passwords", "email", "linscp", "archive-export", "password-export", "email-export", "export", "restore", "linscp-export", "pgp", "pgp-export", "storage", "rekey", "layout", "lock", "preferences", "preferences-import"), calls)
        assertTrue(tools.all { it.label(VaultStrings.English).isNotBlank() })
    }
    @Test fun storedToolIdsDoNotRetainCallbacksAndRespectTerminalGuards() {
        val old = ArrayList<String>(); val current = ArrayList<String>(); var active = true
        val ids = VaultTool.available(actions(old))
        val guarded = actions(current).guarded { active }
        ids.forEach { it.invoke(guarded) }
        assertEquals(18, current.size); assertTrue(old.isEmpty())
        active = false
        ids.forEach { it.invoke(guarded) }
        assertEquals(18, current.size)
        ids.forEach { it.invoke(VaultActions()) }
    }
    @Test fun labelsUseHostSuppliedStrings() {
        val strings = VaultStrings(tools = "Menu", importUri = "URI", cardLayout = "Layout")
        assertEquals("URI", VaultTool.IMPORT_URI.label(strings))
        assertEquals("Layout", VaultTool.CARD_LAYOUT.label(strings))
    }
}
