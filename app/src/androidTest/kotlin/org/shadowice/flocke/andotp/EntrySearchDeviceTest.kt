package org.shadowice.flocke.andotp

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.EntryList
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.SessionCommandResult
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.Utilities.VaultSession
import org.shadowice.flocke.andotp.View.EntriesCardAdapter
import org.shadowice.flocke.andotp.View.TagsAdapter
import javax.crypto.spec.SecretKeySpec

/** Exercises the real Android Filter worker and main-thread publication without writing a vault. */
@RunWith(AndroidJUnit4::class)
class EntrySearchDeviceTest {
    @Test
    fun searchSurvivesTagAndSortChangesAndRestoresTagsWhenCleared() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DeviceTestSupport.freshPreferences(context)
        val first = entry("alice-work", "work")
        val second = entry("alice-home", "home")
        val third = entry("bob", "work")
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var adapter: EntriesCardAdapter? = null
        try {
            instrumentation.runOnMainSync {
                val unlock = VaultSession.beginUnlock()
                assertTrue(VaultSession.activate(key, unlock.generation) is SessionCommandResult.Applied)
                val tags = TagsAdapter(context, hashMapOf("work" to true, "home" to false))
                val created = EntriesCardAdapter(context, tags)
                adapter = created
                created.encryptionKey = key
                val field = EntriesCardAdapter::class.java.getDeclaredField("entries")
                field.isAccessible = true
                val source = field.get(created) as EntryList
                source.addEntry(first)
                source.addEntry(second)
                source.addEntry(third)
                created.filterByTags(listOf("work"))
                created.setSearchQuery("alice")
            }

            val current = requireNotNull(adapter)
            val expected = listOf(first.getListId(), second.getListId())
            val deadline = SystemClock.uptimeMillis() + 5_000
            var visible = emptyList<Long>()
            while (SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { visible = ids(current) }
                if (visible == expected) break
                SystemClock.sleep(25)
            }
            assertEquals(expected, visible)

            instrumentation.runOnMainSync {
                current.filterByTags(listOf("home"))
                assertEquals(expected, ids(current))
                third.setLabel("alice-new")
                current.setSortMode(Constants.SortMode.LABEL)
                assertEquals(listOf(second.getListId(), third.getListId(), first.getListId()), ids(current))
                current.setSearchQuery("")
                assertEquals(listOf(second.getListId()), ids(current))
            }
        } finally {
            instrumentation.runOnMainSync { adapter?.lockEntries() }
            first.destroy()
            second.destroy()
            third.destroy()
            DeviceTestSupport.wipePreferences(context)
        }
    }

    private fun entry(label: String, tag: String): Entry =
        Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, "", label,
            TokenCalculator.HashAlgorithm.SHA1, arrayListOf(tag))

    private fun ids(adapter: EntriesCardAdapter): List<Long> =
        (0 until adapter.getItemCount()).map { adapter.getItemId(it) }
}
