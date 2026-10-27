package org.shadowice.flocke.andotp

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.View.EntryViewHolder

/** Uses the real card layouts to check the countdown shown for mOTP and HOTP. */
@RunWith(AndroidJUnit4::class)
class TokenCardDeviceTest {
    @Test
    fun motpShowsItsTenSecondCountdownOnEveryCardLayout() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DeviceTestSupport.freshPreferences(context)
        val motp = Entry(Entry.OTPType.MOTP, "0001020304050607", "Example", "alice", arrayListOf())
        val hotp = Entry(Entry.OTPType.HOTP, "JBSWY3DPEHPK3PXP", 1L, 6, "Example", "alice",
            TokenCalculator.HashAlgorithm.SHA1, arrayListOf())
        try {
            motp.updateOTP(false)
            hotp.updateOTP(false)
            hotp.setPeriod(60) // a stored period is not an expiry for counter-based entries
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val settings = Settings(context)
                for (layout in listOf(R.layout.component_card_compact, R.layout.component_card_default,
                        R.layout.component_card_full)) {
                    val card = LayoutInflater.from(context).inflate(layout, FrameLayout(context), false)
                    val holder = EntryViewHolder(context, card, false)
                    try {
                        holder.updateValues(motp, settings)
                        assertEquals(View.VISIBLE, card.findViewById<View>(R.id.customPeriodLayout)!!.visibility)
                        assertEquals(String.format(context.getString(R.string.format_custom_period), 10),
                            card.findViewById<TextView>(R.id.customPeriod)!!.text.toString())
                        assertEquals(View.VISIBLE, card.findViewById<View>(R.id.cardProgressBar)!!.visibility)

                        holder.updateValues(hotp, settings)
                        assertEquals(View.GONE, card.findViewById<View>(R.id.customPeriodLayout)!!.visibility)
                        assertEquals(View.GONE, card.findViewById<View>(R.id.cardProgressBar)!!.visibility)
                    } finally {
                        holder.cancelPendingClicks()
                    }
                }
            }
        } finally {
            motp.destroy()
            hotp.destroy()
            DeviceTestSupport.wipePreferences(context)
        }
    }
}
