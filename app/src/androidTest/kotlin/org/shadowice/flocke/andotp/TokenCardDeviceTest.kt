package org.shadowice.flocke.andotp

import android.content.Context
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.View.EntryViewHolder

import java.util.Locale

/** Checks countdown and privacy binding against all three real Android card layouts. */
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
            motp.setDetailsVisible(true)
            hotp.setDetailsVisible(true)
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

    @Test
    fun hiddenEntriesShowIssuerAndLabelWhileRecycledCardsKeepDetailsPrivate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = DeviceTestSupport.freshPreferences(context)
        val hotp = Entry(Entry.OTPType.HOTP, "JBSWY3DPEHPK3PXP", 1L, 6,
            "Issuer", "Account", TokenCalculator.HashAlgorithm.SHA1, arrayListOf("private tag"))
        val totp = Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6,
            "Service", "alice", TokenCalculator.HashAlgorithm.SHA1, arrayListOf())
        val motp = Entry(Entry.OTPType.MOTP, "0001020304050607", "Mobile", "phone", arrayListOf())
        val steam = Entry(Entry.OTPType.STEAM, "JBSWY3DPEHPK3PXP", 30, 5,
            "Steam", "player", TokenCalculator.HashAlgorithm.SHA1, arrayListOf())
        val note = Entry(Entry.OTPType.NOTE, "note secret", "Notes", "Recovery", arrayListOf())
        val ssh = Entry.sshKey("public key", "private key", "Servers", "Host", arrayListOf())
        val issuerOnly = Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6,
            "Issuer only", "", TokenCalculator.HashAlgorithm.SHA1, arrayListOf())
        val labelOnly = Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6,
            "", "Label only", TokenCalculator.HashAlgorithm.SHA1, arrayListOf())
        val unnamed = Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6,
            "", "", TokenCalculator.HashAlgorithm.SHA1, arrayListOf())
        val entries = listOf(hotp, totp, motp, steam, note, ssh, issuerOnly, labelOnly, unnamed)
        try {
            hotp.updateOTP(false)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val settings = Settings(context)
                val layouts = listOf(
                    R.layout.component_card_compact to Constants.CardLayouts.COMPACT,
                    R.layout.component_card_default to Constants.CardLayouts.DEFAULT,
                    R.layout.component_card_full to Constants.CardLayouts.FULL
                )
                for ((layout, cardLayout) in layouts) {
                    preferences.edit().putString(context.getString(R.string.settings_key_card_layout),
                        cardLayout.name.lowercase(Locale.ENGLISH)).commit()
                    val separator = if (cardLayout == Constants.CardLayouts.FULL) "\n" else "\u00a0-\u00a0"
                    val card = LayoutInflater.from(context).inflate(layout, FrameLayout(context), false)
                    val holder = EntryViewHolder(context, card, false)
                    val label = card.findViewById<TextView>(R.id.textViewLabel)!!
                    try {
                        holder.setLabelScroll(Constants.LabelDisplay.TRUNCATE, cardLayout)
                        assertEquals(if (cardLayout == Constants.CardLayouts.FULL) 2 else 1, label.maxLines)
                        hotp.setDetailsVisible(true)
                        holder.updateValues(hotp, settings)
                        assertEquals("Issuer${separator}Account", label.text.toString())
                        assertEquals(View.VISIBLE, card.findViewById<View>(R.id.valueLayout)!!.visibility)
                        for (entry in entries) {
                            entry.setDetailsVisible(false)
                            holder.updateValues(entry, settings)
                            val issuer = entry.getIssuer()
                            val name = entry.getLabel()
                            val expected = if (issuer.isNotEmpty() && name.isNotEmpty())
                                issuer + separator + name else issuer + name
                            assertEquals(expected, label.text.toString())
                            if (issuer.isNotEmpty()) {
                                val styled = label.text as Spanned
                                val bold = styled.getSpans(0, issuer.length, StyleSpan::class.java)
                                    .first { it.getStyle() == Typeface.BOLD }
                                assertEquals(0, styled.getSpanStart(bold))
                                assertEquals(issuer.length, styled.getSpanEnd(bold))
                            }
                            for (id in listOf(R.id.valueLayout, R.id.coverLayout, R.id.valueTextPrev,
                                    R.id.textViewTags, R.id.counterLayout, R.id.customPeriodLayout,
                                    R.id.thumbnailFrame, R.id.cardProgressBar, R.id.copyButton, R.id.menuButton)) {
                                assertEquals(View.GONE, card.findViewById<View>(id)!!.visibility)
                            }
                            for (id in listOf(R.id.valueText, R.id.valueTextPrev, R.id.textViewTags,
                                    R.id.counter, R.id.customPeriod)) {
                                assertEquals("", card.findViewById<TextView>(id)!!.text.toString())
                            }
                            assertEquals(null, card.findViewById<View>(R.id.valueText)!!.tag)
                            assertEquals(null, card.findViewById<ImageView>(R.id.thumbnailImg)!!.getDrawable())
                            val toggle = card.findViewById<View>(R.id.privacyButton)!!
                            assertEquals(View.VISIBLE, toggle.visibility)
                            assertEquals(context.getString(R.string.privacy_reveal), toggle.contentDescription.toString())
                        }
                        hotp.setDetailsVisible(true)
                        holder.updateValues(hotp, settings)
                        assertEquals("Issuer${separator}Account", label.text.toString())
                        assertEquals(View.VISIBLE, card.findViewById<View>(R.id.valueLayout)!!.visibility)
                        assertEquals(View.VISIBLE, card.findViewById<View>(R.id.counterLayout)!!.visibility)
                        assertEquals(View.VISIBLE, card.findViewById<View>(R.id.menuButton)!!.visibility)
                        assertEquals(context.getString(R.string.privacy_hide),
                            card.findViewById<View>(R.id.privacyButton)!!.contentDescription.toString())

                        // Hide issuer is a separate global preference and applies in both states,
                        // even for an issuer-only entry; do not reveal it as a fallback.
                        settings.setBoolean(R.string.settings_key_hide_issuer, true)
                        for (entry in listOf(hotp, issuerOnly, labelOnly)) {
                            for (shown in listOf(false, true)) {
                                entry.setDetailsVisible(shown)
                                holder.updateValues(entry, settings)
                                assertEquals(entry.getLabel(), label.text.toString())
                            }
                        }
                    } finally {
                        settings.setBoolean(R.string.settings_key_hide_issuer, false)
                        holder.cancelPendingClicks()
                    }
                }
            }
        } finally {
            entries.forEach { it.destroy() }
            DeviceTestSupport.wipePreferences(context)
        }
    }

}
