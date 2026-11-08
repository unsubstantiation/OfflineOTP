package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.EntryList
import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.Utilities.TokenCalculator
import com.fairuniversal.offlineotp.View.EntryListPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-host state both clients used to keep themselves: the selection, the reveal set, the copy
 * rule and what a lock forgets. These assertions are what the Compose desktop host and, later, the
 * Android activity inherit instead of re-deciding.
 */
class VaultPresenterTest {

    private val secret = "JBSWY3DPEHPK3PXP"

    private fun totp(issuer: String, label: String, tags: List<String> = emptyList()) =
        Entry(
            Entry.OTPType.TOTP, secret, 30, 6, issuer, label,
            TokenCalculator.HashAlgorithm.SHA1, ArrayList(tags)
        )

    private fun note(label: String = "Recovery", body: String = "backup codes") =
        Entry(Entry.OTPType.NOTE, body, "Notes", label, ArrayList(), "")

    private class Harness(
        list: EntryList,
        initialFilter: VaultFilter = VaultFilter(combination = VaultFilterCombination.SEARCH_AND_TAGS),
        copy: ((String) -> Unit)? = null,
        advance: ((VaultCardState) -> Unit)? = null,
        sort: ((Constants.SortMode) -> Unit)? = null,
        lock: (() -> Unit)? = null
    ) {
        val copied = ArrayList<String>()
        val sorts = ArrayList<Constants.SortMode>()
        var locks = 0
        var changes = 0

        val presenter = VaultPresenter(
            projection = VaultProjection(list, EntryListPresentation(list)),
            initialFilter = initialFilter,
            copyCode = copy ?: { copied.add(it); Unit },
            advanceCounter = advance,
            sortSelected = sort ?: { sorts.add(it); Unit },
            lockVault = lock ?: { locks++; Unit },
            onChange = { changes++ }
        )

        val actions: VaultActions get() = presenter.actions()
    }

    private fun harnessOf(vararg entries: Entry, initialFilter: VaultFilter = VaultFilter(combination = VaultFilterCombination.SEARCH_AND_TAGS)): Harness {
        val list = EntryList()
        for (entry in entries) list.addEntry(entry, false)
        return Harness(list, initialFilter)
    }

    @Test
    fun pinEntryIsOptionalAndForwardsTheStableCardId() {
        val list = EntryList().apply { addEntry(totp("Router", "admin"), false) }
        val projection = VaultProjection(list, EntryListPresentation(list))
        val filter = VaultFilter(combination = VaultFilterCombination.SEARCH_AND_TAGS)
        assertNull(VaultPresenter(projection, filter).actions().onEnterPin)
        var requested: Long? = null
        val presenter = VaultPresenter(projection, filter, enterPin = { requested = it.id })
        val card = presenter.frame(0L).cards.single()
        presenter.actions().onEnterPin!!.invoke(card)
        assertEquals(card.id, requested)
    }

    @Test
    fun aRowIsMaskedUntilItIsRevealedAndOnlyThenCarriesItsContent() {
        val harness = harnessOf(note())
        val card = harness.presenter.frame(0L).cards.single()
        assertFalse("nothing starts revealed", card.revealed)
        assertNull(card.revealedContent)

        harness.actions.onToggleReveal(card)
        val revealed = harness.presenter.frame(0L).cards.single()
        assertTrue(revealed.revealed)
        assertEquals("backup codes", revealed.revealedContent)
        assertTrue(harness.presenter.isRevealed(revealed.id))

        harness.actions.onToggleReveal(revealed)
        assertNull("toggling again forgets the content", harness.presenter.frame(0L).cards.single().revealedContent)
        assertFalse(harness.presenter.isRevealed(revealed.id))
    }

    @Test
    fun oneRevealedRowLeavesItsNeighbourPrivate() {
        val harness = harnessOf(note("First"), note("Second", "second body"))
        val cards = harness.presenter.frame(0L).cards
        harness.actions.onToggleReveal(cards.first())
        val after = harness.presenter.frame(0L).cards
        assertEquals("backup codes", after.first().revealedContent)
        assertNull(after.last().revealedContent)
    }

    @Test
    fun copyTakesTheDigitsAndLeavesTheGroupingOnScreen() {
        val harness = harnessOf(totp("GitHub", "alice"))
        val card = harness.presenter.frame(1_000L).cards.single()
        val grouped = card.token!!
        assertTrue("the card groups the code", grouped.contains(" "))
        harness.actions.onCopyToken(card)
        assertEquals(listOf(grouped.replace(" ", "")), harness.copied)
        assertEquals(6, harness.copied.single().length)
    }

    @Test
    fun nothingIsCopiedForARowWithoutACopyableCode() {
        val harness = harnessOf(note())
        harness.actions.onCopyToken(harness.presenter.frame(0L).cards.single())
        assertTrue(harness.copied.isEmpty())
        // An mOTP entry with no PIN is the other case: it has a token slot but nothing copyable.
        val motp = Entry(Entry.OTPType.MOTP, secret, 30, 6, "mOTP", "pinned",
            TokenCalculator.HashAlgorithm.SHA1, ArrayList()).apply { updateOTPAt(0L, true) }
        val pinned = harnessOf(motp)
        val card = pinned.presenter.frame(0L).cards.single()
        assertTrue(card.pinRequired)
        pinned.actions.onCopyToken(card)
        assertTrue(pinned.copied.isEmpty())
    }

    @Test
    fun theFilterTransitionsComeFromTheSharedValueAndReshapeTheList() {
        val harness = harnessOf(totp("Service", "alice"), note())
        assertEquals(2, harness.presenter.frame(0L).cards.size)
        harness.actions.onSelectCategory(EntryListPresentation.Category.NOTES)
        assertEquals(listOf("Recovery"), harness.presenter.frame(0L).cards.map { it.label })
        assertEquals(EntryListPresentation.Category.NOTES, harness.presenter.filter.category)

        harness.actions.onQueryChange("nothing matches this")
        assertTrue(harness.presenter.frame(0L).cards.isEmpty())
        harness.actions.onClearFilters()
        assertEquals(1, harness.presenter.frame(0L).cards.size)
        assertEquals("the tab survives Clear", EntryListPresentation.Category.NOTES,
            harness.presenter.filter.category)
    }

    @Test
    fun tagsToggleThroughThePresenterAndTheHostHearsAboutEachChange() {
        val harness = harnessOf(totp("Service", "alice", listOf("work")), totp("Service", "bob", listOf("home")))
        harness.actions.onToggleTag("work")
        assertEquals(listOf("alice"), harness.presenter.frame(0L).cards.map { it.label })
        harness.actions.onToggleTag("work")
        assertEquals(2, harness.presenter.frame(0L).cards.size)
        harness.actions.onToggleUntagged()
        assertTrue(harness.presenter.filter.untaggedSelected)
        assertEquals("every change re-renders the host", 3, harness.changes)
    }

    @Test
    fun choosingAnOrderAlsoTellsTheHostWhatToRemember() {
        val harness = harnessOf(totp("Beta", "b"), totp("Alpha", "a"))
        harness.actions.onSelectSort(Constants.SortMode.ISSUER)
        assertEquals(listOf(Constants.SortMode.ISSUER), harness.sorts)
        assertEquals(listOf("Alpha", "Beta"), harness.presenter.frame(0L).cards.map { it.issuer })
        assertEquals(Constants.SortMode.ISSUER, harness.presenter.filter.sortMode)
    }

    @Test
    fun anHotpCounterIsForwardedOnlyWhenTheHostCanPersistIt() {
        val refusing = harnessOf(totp("Bank", "alice"))
        assertNull("a host that cannot save offers no counter action", refusing.actions.onNextCounter)

        var advancedCard: VaultCardState? = null
        val list = EntryList().apply { addEntry(totp("Bank", "alice"), false) }
        val saving = Harness(list, advance = { card -> advancedCard = card })
        val card = saving.presenter.frame(0L).cards.single()
        saving.actions.onNextCounter?.invoke(card)
        assertEquals("Bank", advancedCard?.issuer)
    }

    @Test
    fun lockingForgetsEveryRevealedRowBeforeTheHostLocks() {
        val harness = harnessOf(note())
        harness.actions.onToggleReveal(harness.presenter.frame(0L).cards.single())
        assertTrue(harness.presenter.isRevealed(harness.presenter.frame(0L).cards.single().id))
        harness.actions.onLock()
        assertEquals(1, harness.locks)
        assertNull("a frame built after the lock carries no content",
            harness.presenter.frame(0L).cards.single().revealedContent)
    }

    @Test
    fun theLockCallbackAlreadySeesMaskedRows() {
        val list = EntryList().apply { addEntry(note(), false) }
        lateinit var presenter: VaultPresenter
        presenter = VaultPresenter(
            VaultProjection(list, EntryListPresentation(list)),
            VaultFilter(combination = VaultFilterCombination.SEARCH_AND_TAGS),
            lockVault = { assertNull(presenter.frame(0L).cards.single().revealedContent) }
        )
        presenter.toggleReveal(presenter.frame(0L).cards.single().id)
        presenter.lock()
    }

    @Test
    fun aFrameIsASnapshotOfTheSecondItWasAsked() {
        val harness = harnessOf(totp("GitHub", "alice"))
        assertEquals(29, harness.presenter.frame(1_000L).cards.single().remainingSeconds)
        assertEquals(5, harness.presenter.frame(25_000L).cards.single().remainingSeconds)
        assertTrue(harness.presenter.frame(25_000L).cards.single().showsExpiry)
    }

    @Test
    fun thePresenterKeepsItsOwnTextAndSettingsOutOfDiagnostics() {
        val harness = harnessOf(note())
        val text = harness.presenter.toString()
        assertFalse(text.contains("backup codes"))
        assertTrue(text.contains("VaultPresenter"))
    }
}
