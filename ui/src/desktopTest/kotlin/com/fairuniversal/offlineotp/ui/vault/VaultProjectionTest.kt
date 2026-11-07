package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.EntryList
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.TokenCalculator
import com.fairuniversal.offlineotp.View.EntryListPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared frame builder: what a card shows, what it hides, and which empty panel appears.
 * These assertions encode the behaviour the Android card and the Swing table already had, so the
 * Compose screen can replace both without inventing a third set of rules.
 */
class VaultProjectionTest {

    private val secret = "JBSWY3DPEHPK3PXP"

    // EntryList assigns the stable list ids when an entry is added, so tests read them back from
    // the frame instead of inventing positions or ids of their own.
    private fun totp(issuer: String = "GitHub", label: String = "alice", tags: List<String> = emptyList()) =
        Entry(
            Entry.OTPType.TOTP, secret, 30, 6, issuer, label,
            TokenCalculator.HashAlgorithm.SHA1, ArrayList(tags)
        )

    private fun hotp(issuer: String = "Bank", label: String = "alice") =
        Entry(
            Entry.OTPType.HOTP, secret, 1L, 6, issuer, label,
            TokenCalculator.HashAlgorithm.SHA1, ArrayList()
        ).apply { setCounter(1L) }

    private fun note(label: String = "Recovery", body: String = "backup codes") =
        Entry(Entry.OTPType.NOTE, body, label, label, ArrayList(), "")

    private fun projectionOf(vararg entries: Entry): VaultProjection {
        val list = EntryList()
        for (entry in entries) list.addEntry(entry, false)
        val presentation = EntryListPresentation(list).apply { showReorderedEntries() }
        return VaultProjection(list, presentation)
    }

    @Test
    fun aTimeEntryShowsAGroupedCodeAndItsCountdown() {
        val state = projectionOf(totp()).snapshot(VaultFilter(), nowMillis = 1_000L)
        val card = state.cards.single()
        assertEquals(VaultEntryKind.OTP, card.kind)
        assertEquals("GitHub", card.title)
        assertEquals("alice", card.subtitle)
        assertNotNull(card.token)
        assertEquals(6, card.token!!.replace(" ", "").length)
        assertTrue("codes are grouped into halves", card.token!!.contains(" "))
        assertEquals(29, card.remainingSeconds)
        assertTrue(card.copyable)
        assertNull(card.counter)
        assertTrue("the id is the vault's stable list id", card.id > 0L)
    }

    @Test
    fun aCodeNearRolloverIsFlaggedExpiring() {
        val nearRollover = projectionOf(totp()).snapshot(VaultFilter(), nowMillis = 25_000L)
        assertTrue(nearRollover.cards.single().expiring)
        assertTrue(nearRollover.cards.single().showsExpiry)
        val early = projectionOf(totp()).snapshot(VaultFilter(), nowMillis = 2_000L)
        assertFalse(early.cards.single().expiring)
        assertFalse(early.cards.single().showsExpiry)
    }

    @Test
    fun noCountdownIsShownWhenExpiryIsDisabled() {
        val state = projectionOf(totp())
            .snapshot(VaultFilter(), nowMillis = 25_000L, appearance = VaultAppearance(expiringSeconds = 0))
        assertFalse(state.cards.single().expiring)
    }

    @Test
    fun aCounterEntryShowsItsCounterInsteadOfACountdown() {
        val card = projectionOf(hotp()).snapshot(VaultFilter(), nowMillis = 0L).cards.single()
        assertEquals(1L, card.counter)
        assertNull(card.remainingSeconds)
        assertNull(card.progress)
        assertFalse(card.hasCountdown)
        assertNotNull(card.token)
    }

    @Test
    fun aMotpEntryWithoutAPinAsksForOneInsteadOfShowingAPlaceholder() {
        val motp = Entry(
            Entry.OTPType.MOTP, secret, 30, 6, "Router", "admin",
            TokenCalculator.HashAlgorithm.SHA1, ArrayList()
        )
        val card = projectionOf(motp).snapshot(VaultFilter(), nowMillis = 0L).cards.single()
        assertTrue(card.pinRequired)
        assertNull(card.token)
        assertFalse(card.copyable)
    }

    @Test
    fun aNoteStaysPrivateUntilItIsRevealed() {
        val hidden = projectionOf(note()).snapshot(VaultFilter(), nowMillis = 0L).cards.single()
        assertNull(hidden.revealedContent)
        assertFalse(hidden.revealed)
        val noteId = hidden.id
        val shown = projectionOf(note()).snapshot(
            VaultFilter(), nowMillis = 0L, revealedIds = setOf(noteId)
        ).cards.single()
        assertTrue(shown.revealed)
        assertEquals("backup codes", shown.revealedContent)
    }

    @Test
    fun revealingOneEntryLeavesItsNeighbourPrivate() {
        val plain = projectionOf(note(), totp()).snapshot(VaultFilter(), nowMillis = 0L)
        val noteId = plain.cards.single { it.kind == VaultEntryKind.NOTES }.id
        val state = projectionOf(note(), totp())
            .snapshot(VaultFilter(), nowMillis = 0L, revealedIds = setOf(noteId))
        assertEquals("backup codes", state.cards.single { it.kind == VaultEntryKind.NOTES }.revealedContent)
        assertNull(state.cards.single { it.kind == VaultEntryKind.OTP }.revealedContent)
    }

    @Test
    fun tabsCountEveryKindAndMarkTheSelection() {
        val state = projectionOf(totp(), note()).snapshot(VaultFilter(), nowMillis = 0L)
        assertEquals(EntryListPresentation.Category.entries.size, state.categories.size)
        val counts = state.categories.associate { it.category to it.count }
        assertEquals(1, counts[EntryListPresentation.Category.OTP])
        assertEquals(1, counts[EntryListPresentation.Category.NOTES])
        assertEquals(0, counts[EntryListPresentation.Category.PASSWORDS])
        assertTrue(state.categories.none { it.selected })
        val selected = projectionOf(totp(), note()).refresh(
            VaultFilter(category = EntryListPresentation.Category.NOTES), nowMillis = 0L
        )
        assertEquals(
            EntryListPresentation.Category.NOTES,
            selected.categories.single { it.selected }.category
        )
    }

    @Test
    fun tagChipsCarryCountsAndFollowTheSelection() {
        val state = projectionOf(
            totp(tags = listOf("work", "2fa")),
            totp(issuer = "GitLab", tags = listOf("work")),
            note()
        ).refresh(VaultFilter(selectedTags = listOf("work")), nowMillis = 0L)
        val byName = state.tags.associateBy { it.name }
        assertEquals(2, byName.getValue("work").count)
        assertTrue(byName.getValue("work").selected)
        assertEquals(1, byName.getValue("2fa").count)
        assertFalse(byName.getValue("2fa").selected)
        assertEquals(1, byName.getValue("Untagged").count)
        assertFalse(byName.getValue("Untagged").selected)
    }

    @Test
    fun theUntaggedChipIsAbsentWhenEverythingIsTagged() {
        val state = projectionOf(totp(tags = listOf("work"))).snapshot(VaultFilter(), nowMillis = 0L)
        assertTrue(state.tags.none { it.name == "Untagged" })
    }

    @Test
    fun anEmptyVaultExplainsHowToStart() {
        val state = projectionOf().snapshot(VaultFilter(), nowMillis = 0L)
        assertEquals(0, state.totalEntries)
        assertTrue(state.isEmpty)
        assertEquals("No entries yet", state.emptyTitle)
    }

    @Test
    fun aFilteredMissIsNotDescribedAsAnEmptyVault() {
        val query = projectionOf(totp()).refresh(VaultFilter(query = "nothing-matches"), nowMillis = 0L)
        assertEquals(0, query.cards.size)
        assertEquals("No matches", query.emptyTitle)
        val tags = projectionOf(totp()).refresh(VaultFilter(selectedTags = listOf("missing")), nowMillis = 0L)
        assertEquals("No matches", tags.emptyTitle)
    }

    @Test
    fun anEmptyTabIsDescribedSeparately() {
        val state = projectionOf(totp()).refresh(
            VaultFilter(category = EntryListPresentation.Category.NOTES), nowMillis = 0L
        )
        assertEquals("Nothing in this tab", state.emptyTitle)
    }

    @Test
    fun sortOptionsCoverEveryModeAndMarkTheSelection() {
        val state = projectionOf(totp()).snapshot(
            VaultFilter(sortMode = Constants.SortMode.MOST_USED), nowMillis = 0L
        )
        assertEquals(Constants.SortMode.entries.size, state.sortOptions.size)
        assertEquals(Constants.SortMode.MOST_USED, state.sortOptions.single { it.selected }.mode)
    }

    @Test
    fun codesAndNoteBodiesNeverAppearInDiagnostics() {
        val plain = projectionOf(note(), totp()).snapshot(VaultFilter(), nowMillis = 0L)
        val noteId = plain.cards.single { it.kind == VaultEntryKind.NOTES }.id
        val state = projectionOf(note(), totp()).snapshot(
            VaultFilter(), nowMillis = 0L, revealedIds = setOf(noteId)
        )
        val text = state.toString() + state.cards.joinToString(" ") { it.toString() }
        val token = state.cards.single { it.kind == VaultEntryKind.OTP }.token!!.replace(" ", "")
        assertFalse(text, text.contains(token))
        assertFalse(text, text.contains("backup codes"))
    }

    @Test
    fun androidSemanticsLetASearchReplaceTheTagSelection() {
        val state = projectionOf(
            totp(issuer = "GitHub", label = "tagged", tags = listOf("work")),
            totp(issuer = "GitHub", label = "untagged")
        ).refresh(
            VaultFilter(
                query = "GitHub",
                selectedTags = listOf("work"),
                combination = VaultFilterCombination.SEARCH_OVERRIDES_TAGS
            ),
            nowMillis = 0L
        )
        assertEquals(2, state.cards.size)
    }

    @Test
    fun desktopSemanticsKeepSearchAndTagsTogether() {
        val state = projectionOf(
            totp(issuer = "GitHub", label = "tagged", tags = listOf("work")),
            totp(issuer = "GitHub", label = "untagged")
        ).refresh(
            VaultFilter(
                query = "GitHub",
                selectedTags = listOf("work"),
                combination = VaultFilterCombination.SEARCH_AND_TAGS
            ),
            nowMillis = 0L
        )
        assertEquals(1, state.cards.size)
        assertEquals("tagged", state.cards.single().subtitle)
    }

    @Test
    fun aTagFilterAloneStillNarrowsUnderDesktopSemantics() {
        val state = projectionOf(
            totp(issuer = "GitHub", label = "tagged", tags = listOf("work")),
            totp(issuer = "GitLab", label = "untagged")
        ).refresh(
            VaultFilter(
                selectedTags = listOf("work"),
                combination = VaultFilterCombination.SEARCH_AND_TAGS
            ),
            nowMillis = 0L
        )
        assertEquals(1, state.cards.size)
        assertEquals("GitHub", state.cards.single().title)
    }

    @Test
    fun aBackwardsClockStillProducesACountedDownCard() {
        val state = projectionOf(totp()).snapshot(VaultFilter(), nowMillis = -1_000L)
        assertEquals(1, state.cards.single().remainingSeconds)
    }
}
