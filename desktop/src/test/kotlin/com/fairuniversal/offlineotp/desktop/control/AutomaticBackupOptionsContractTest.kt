package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.ui.portable.PortableAutomaticBackupPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticBackupOptionsContractTest {
    @Test fun canonicalBoundaryVectorsMatchSessionBackupPolicy() {
        val document = javaClass.getResourceAsStream("/golden/automatic-backup-options.json")!!.bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(document.getLong("changeDelayMillis"), PortableAutomaticBackupPolicy.CHANGE_DELAY_MILLIS)
        assertEquals(document.getInt("defaultMinutes"), PortableAutomaticBackupPolicy.DEFAULT_MINUTES)
        assertEquals(document.getInt("defaultRetention"), PortableAutomaticBackupPolicy.DEFAULT_RETENTION)
        val cases = document.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val row = cases.getJSONObject(index)
            assertEquals(row.getBoolean("valid"), PortableAutomaticBackupPolicy.valid(row.getInt("minutes"), row.getInt("retention"), row.getInt("passwordUtf16Units"), row.getBoolean("passwordsMatch")))
        }
    }
}
