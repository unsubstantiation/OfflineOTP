package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.TokenCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One row at a time, without a projection.
 *
 * This is the path the Swing table takes: it owns its own row loop and asks for a card as it paints,
 * so the same rules have to hold when no [VaultUiState] is built. If these and [VaultProjectionTest]
 * ever disagree, one client would render a row the other does not.
 */
class VaultCardsTest {

    private val secret = "JBSWY3DPEHPK3PXP"

    private fun totp(revealed: Boolean = false, tags: List<String> = emptyList()): Entry =
        Entry(
            Entry.OTPType.TOTP, secret, 30, 6, "GitHub", "alice",
            TokenCalculator.HashAlgorithm.SHA1, ArrayList(tags)
        ).apply {
            updateOTPAt(0L, true)
            setDetailsVisible(revealed)
        }

    private fun hotp(): Entry =
        Entry(
            Entry.OTPType.HOTP, secret, 1L, 6, "Bank", "alice",
            TokenCalculator.HashAlgorithm.SHA1, ArrayList()
        ).apply {
            setCounter(2L)
            updateOTPAt(0L, true)
        }

    private fun note(revealed: Boolean = false): Entry =
        Entry(Entry.OTPType.NOTE, "backup codes", "Notes", "Recovery", ArrayList(), "")
            .apply { setDetailsVisible(revealed) }

    @Test
    fun aTimeEntryCarriesTheGroupedCodeAndItsCountdown() {
        val card = VaultCards.of(totp(), nowMillis = 1_000L)
        assertEquals(VaultEntryKind.OTP, card.kind)
        assertNotNull(card.token)
        assertTrue("codes are grouped into halves", card.token!!.contains(" "))
        assertEquals(6, card.token!!.replace(" ", "").length)
        assertEquals(29, card.remainingSeconds)
        assertTrue(card.progress!! > 0.9f)
        assertFalse(card.expiring)
        assertTrue(card.copyable)
        assertNull(card.counter)
    }

    @Test
    fun theClockDecidesWhenACodeWarns() {
        assertFalse("a fresh step does not warn", VaultCards.of(totp(), nowMillis = 2_000L).expiring)
        val late = VaultCards.of(totp(), nowMillis = 25_000L)
        assertTrue("five seconds left warns", late.expiring)
        assertTrue(late.showsExpiry)
        assertEquals(5, late.remainingSeconds)
        // A host that wants a different warning window says so, rather than re-deriving the rule.
        assertFalse(VaultCards.of(totp(), nowMillis = 25_000L, appearance = VaultAppearance(expiringSeconds = 2)).expiring)
    }

    @Test
    fun groupingCanBeTurnedOffForTheHostsThatDoNotGroup() {
        val plain = VaultCards.of(totp(), nowMillis = 1_000L, appearance = VaultAppearance(groupTokens = false))
        assertFalse(plain.token!!.contains(" "))
        assertEquals(6, plain.token!!.length)
    }

    @Test
    fun anHotpEntryHasACounterButNoCountdown() {
        val card = VaultCards.of(hotp(), nowMillis = 1_000L)
        assertEquals(2L, card.counter ?: -1L)
        assertNull("HOTP never expires on a clock", card.remainingSeconds)
        assertNull(card.progress)
        assertFalse(card.expiring)
    }

    @Test
    fun protectedContentAppearsOnlyWhenTheRowIsRevealed() {
        val hidden = VaultCards.of(note(revealed = false), nowMillis = 0L)
        assertNull(hidden.revealedContent)
        assertFalse(hidden.revealed)
        val shown = VaultCards.of(note(revealed = true), nowMillis = 0L, revealed = true)
        assertEquals("backup codes", shown.revealedContent)
        assertTrue(shown.revealed)
        // Revealing a note never turns it into a code row.
        assertNull(shown.token)
        assertFalse(shown.hasToken)
    }

    @Test
    fun aPasswordEntryRevealsItsSiteAndNeverItsStoredColumns() {
        val entry = Entry.passwordFields(
            "Login", "Site",
            listOf(
                "url" to "https://example.com",
                "username" to "alice",
                "password" to "correct horse"
            )
        )
        val card = VaultCards.of(entry, nowMillis = 0L, revealed = true)
        assertEquals(VaultEntryKind.PASSWORDS, card.kind)
        assertEquals("https://example.com", card.revealedContent)
        assertNull(card.token)
        assertFalse("only a stored site is revealed", card.revealedContent!!.contains("correct horse"))
    }

    @Test
    fun tagsTravelWithTheCardSoTheRowDoesNotReachIntoTheEntry() {
        val card = VaultCards.of(totp(tags = listOf("work", "private")), nowMillis = 0L)
        assertEquals(listOf("work", "private"), card.tags)
    }

    @Test
    fun aCardFromThePaintPathRedactsItsSecretsInToString() {
        val text = VaultCards.of(totp(revealed = true), nowMillis = 1_000L).toString()
        assertFalse(text.contains(VaultCards.of(totp(revealed = true), nowMillis = 1_000L).token!!))
        assertTrue(text.contains("redacted"))
    }

    @Test
    fun theKindComesFromTheSharedCategoryRule() {
        assertEquals(VaultEntryKind.NOTES, VaultCards.kindOf(note()))
        assertEquals(VaultEntryKind.OTP, VaultCards.kindOf(totp()))
        assertTrue(VaultCards.hasCode(totp()))
        assertFalse(VaultCards.hasCode(note()))
    }
}
