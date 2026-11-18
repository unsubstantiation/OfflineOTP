package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.BrowserPasswordCsv
import com.fairuniversal.offlineotp.Utilities.PasswordCsvExport
import com.fairuniversal.offlineotp.ui.vault.PasswordEntryInput
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Swift reads this exact checked-in corpus too; no platform-specific expected result copies. */
class PasswordEditContractTest {
    @Test fun commonFormEligibilityMatchesCrossPlatformContract() {
        val resource = requireNotNull(javaClass.getResourceAsStream("/golden/password-edit-contract.json"))
        val contract = resource.use { JSONObject(String(it.readBytes(), Charsets.UTF_8)) }
        assertEquals(contract.getInt("maxFieldChars"), BrowserPasswordCsv.MAX_FIELD_CHARS)
        val cases = contract.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val columns = case.getJSONArray("columns")
            val entry = try { Entry.passwordFields("Test", "site", (0 until columns.length()).map { columns.getString(it) to "fixture-value" }) }
                catch (_: IllegalArgumentException) { null }
            if (entry == null) { assertEquals(false, case.getBoolean("supported")); continue }
            try {
                assertEquals(case.getBoolean("supported"), PasswordCsvExport.supportsCommonEdit(entry))
                assertEquals(case.getBoolean("supported"), PasswordEntryInput.supports(entry))
            } finally { entry.destroy() }
        }
    }
}
