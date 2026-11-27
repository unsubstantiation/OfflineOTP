package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.HardwareOathClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class HardwareOathCopyPolicyContractTest {
    @Test fun sharedSwiftJvmRenameNamingContract() {
        val root = javaClass.getResourceAsStream("/golden/hardware-oath-rename.json")!!.bufferedReader().use { JSONObject(it.readText()) }
        val rows = root.getJSONArray("vectors")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val names = com.fairuniversal.offlineotp.Utilities.HardwareOathRenamePolicy.names(row.getBoolean("counterBased"),
                row.getInt("period"), row.getString("issuer"), row.getString("account"))
            val expected = row.opt("identifier") as? String
            assertEquals(row.getString("name"), expected, names?.identifier)
        }
    }

    @Test fun sharedSwiftJvmAdmissionContract() {
        val root = javaClass.getResourceAsStream("/golden/hardware-oath-copy.json")!!.bufferedReader().use { JSONObject(it.readText()) }
        val rows = root.getJSONArray("vectors")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val entry = Entry(row.getJSONObject("entry"))
            try { assertEquals(row.getString("name"), row.opt("identifier") is String, HardwareOathClient.canCopy(entry)) }
            finally { entry.destroy() }
        }
    }
}
