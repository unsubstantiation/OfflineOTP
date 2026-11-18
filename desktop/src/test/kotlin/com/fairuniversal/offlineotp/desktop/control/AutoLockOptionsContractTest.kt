package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.ui.vault.AutoLockOptions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLockOptionsContractTest {
    @Test fun labelsAndCustomTimeoutsMatchSwiftContract() {
        val resource = requireNotNull(javaClass.getResourceAsStream("/golden/auto-lock-options.json"))
        val contract = resource.use { JSONObject(String(it.readBytes(), Charsets.UTF_8)) }
        val options = contract.getJSONArray("options")
        assertEquals((0 until options.length()).map { (options.get(it) as Number).toInt() }, AutoLockOptions.seconds)
        assertEquals(AutoLockOptions.seconds, DesktopAutoLockSettings.OPTIONS)
        val cases = contract.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val seconds = case.getInt("seconds")
            assertEquals(case.getString("label"), AutoLockOptions.label(seconds))
            val choices = AutoLockOptions.including(seconds)
            assertTrue(seconds in choices)
            assertEquals(1, choices.count { it == seconds })
        }
        assertEquals(AutoLockOptions.seconds, AutoLockOptions.including(-1))
    }
}
