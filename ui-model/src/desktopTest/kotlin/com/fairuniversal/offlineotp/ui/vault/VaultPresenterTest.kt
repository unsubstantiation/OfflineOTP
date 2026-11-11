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

    @Test
    fun changingCardLayoutPreservesRevealAndFilterAndStopsAfterLock() {
        val entry = totp("Example", "alice")
        val list = EntryList().apply { addEntry(entry) }
        val presenter = VaultPresenter(VaultProjection(list, EntryListPresentation(list)),
            VaultFilter(combination = VaultFilterCombination.SEARCH_AND_TAGS))
        val card = presenter.frame(0L).cards.single()
        presenter.toggleReveal(card.id)
        val filter = presenter.filter
        assertNull(presenter.actions().onConfigureLayout)
        var opens = 0
        val actions = presenter.actions(configureLayout = { opens++ })
        actions.onConfigureLayout?.invoke()
        presenter.setCardLayout(Constants.CardLayouts.FULL)
        assertEquals(Constants.CardLayouts.FULL, presenter.appearance.cardLayout)
        assertEquals(filter, presenter.filter)
        assertTrue(presenter.frame(0L).cards.single().revealed)
        presenter.lock()
        presenter.setCardLayout(Constants.CardLayouts.COMPACT)
        actions.onConfigureLayout?.invoke()
        assertEquals(Constants.CardLayouts.FULL, presenter.appearance.cardLayout)
        assertEquals(1, opens)
        entry.destroy()
    }

    @Test
    fun emailDetailsRequiresLiveRevealAndIsTerminalAfterLock() {
        val entry = totp("Example", "alice")
        val list = EntryList().apply { addEntry(entry) }
        val presenter = VaultPresenter(VaultProjection(list, EntryListPresentation(list)), VaultFilter(combination = VaultFilterCombination.SEARCH_AND_TAGS))
        assertNull(presenter.actions().onViewEmailDetails)
        var calls = 0
        val actions = presenter.actions(viewEmailDetails = { calls++ })
        val card = presenter.frame(0L).cards.single().copy(kind = VaultEntryKind.EMAILS)
        actions.onViewEmailDetails?.invoke(card)
        assertEquals(0, calls)
        presenter.toggleReveal(card.id)
        actions.onViewEmailDetails?.invoke(card)
        assertEquals(1, calls)
        actions.onViewEmailDetails?.invoke(card.copy(kind = VaultEntryKind.OTP))
        assertEquals(1, calls)
        presenter.toggleReveal(card.id)
        actions.onViewEmailDetails?.invoke(card)
        assertEquals(1, calls)
        presenter.lock()
        actions.onViewEmailDetails?.invoke(card)
        assertEquals(1, calls)
        entry.destroy()
    }

    @Test
    fun emailImportIsOptionalAndTerminalAfterLock() {
        val list = EntryList()
        val presenter = VaultPresenter(VaultProjection(list, EntryListPresentation(list)), VaultFilter())
        assertNull(presenter.actions().onImportEmail)
        var calls = 0
        val actions = presenter.actions(importEmail = { calls++ })
        actions.onImportEmail?.invoke()
        assertEquals(1, calls)
        presenter.lock()
        actions.onImportEmail?.invoke()
        assertEquals(1, calls)
    }

    @Test
    fun passwordImportIsOptionalAndTerminalAfterLock() {
        val list = EntryList()
        val presenter = VaultPresenter(VaultProjection(list, EntryListPresentation(list)), VaultFilter())
        assertNull(presenter.actions().onImportPasswords)
        var calls = 0
        val actions = presenter.actions(importPasswords = { calls++ })
        actions.onImportPasswords?.invoke()
        assertEquals(1, calls)
        presenter.lock()
        actions.onImportPasswords?.invoke()
        assertEquals(1, calls)
    }

    @Test
    fun uriImportIsOptionalAndTerminalAfterLock() {
        val list = EntryList()
        val presenter = VaultPresenter(VaultProjection(list, EntryListPresentation(list)), VaultFilter())
        assertNull(presenter.actions().onImportUri)
        var calls = 0
        val actions = presenter.actions(importUri = { calls++ })
        actions.onImportUri?.invoke()
        assertEquals(1, calls)
        presenter.lock()
        actions.onImportUri?.invoke()
        assertEquals(1, calls)
    }

    @Test
    fun encryptedRestoreIsOptionalAndTerminalAfterLock() {
        val list = EntryList()
        val presenter = VaultPresenter(VaultProjection(list, EntryListPresentation(list)), VaultFilter())
        assertNull(presenter.actions().onRestoreEncrypted)
        var restores = 0
        val actions = presenter.actions(restoreEncrypted = { restores++ })
        actions.onRestoreEncrypted?.invoke()
        assertEquals(1, restores)
        presenter.lock()
        actions.onRestoreEncrypted?.invoke()
        assertEquals(1, restores)
    }

    @Test
    fun encryptedExportIsOptionalAndTerminalAfterLock() {
        val list = EntryList()
        val presenter = VaultPresenter(VaultProjection(list, EntryListPresentation(list)), VaultFilter())
        assertNull(presenter.actions().onExportEncrypted)
        var exports = 0
        val actions = presenter.actions(exportEncrypted = { exports++ })
        actions.onExportEncrypted?.invoke()
        assertEquals(1, exports)
        presenter.lock()
        actions.onExportEncrypted?.invoke()
        assertEquals(1, exports)
    }

    @Test
    fun autoLockSettingsActionIsOptionalAndTerminalAfterLock() {
        val list = EntryList()
        val presenter = VaultPresenter(VaultProjection(list, EntryListPresentation(list)), VaultFilter())
        assertNull(presenter.actions().onConfigureAutoLock)
        var opens = 0
        val actions = presenter.actions(configureAutoLock = { opens++ })
        actions.onConfigureAutoLock?.invoke()
        assertEquals(1, opens)
        presenter.lock()
        actions.onConfigureAutoLock?.invoke()
        assertEquals(1, opens)
    }

    @Test
    fun hostCopyOverridesDisplayStringCopyAndIsTerminalAfterLock() {
        val entry = totp("Example", "alice")
        val list = EntryList().apply { addEntry(entry) }
        var rawCopies = 0
        var boundCopies = 0
        val presenter = VaultPresenter(VaultProjection(list, EntryListPresentation(list)),
            VaultFilter(combination = VaultFilterCombination.SEARCH_AND_TAGS), copyCode = { rawCopies++ })
        val card = presenter.frame(0L).cards.single()
        val actions = presenter.actions(copyEntry = { assertTrue(it === card); boundCopies++ })
        actions.onCopyToken(card)
        assertEquals(1, boundCopies)
        assertEquals(0, rawCopies)
        presenter.lock()
        actions.onCopyToken(card)
        assertEquals(1, boundCopies)
        entry.destroy()
    }

    @Test
    fun editingRequiresCurrentRevealAndBecomesInertAfterLock() {
        val entry = totp("Example", "alice")
        val list = EntryList().apply { addEntry(entry) }
        var edits = 0
        val presenter = VaultPresenter(VaultProjection(list, EntryListPresentation(list)), VaultFilter(combination = VaultFilterCombination.SEARCH_AND_TAGS),
            editEntry = { edits++ })
        val actions = presenter.actions()
        val card = presenter.frame(0L).cards.single()
        actions.onEditEntry?.invoke(card)
        assertEquals(0, edits)
        presenter.toggleReveal(card.id)
        actions.onEditEntry?.invoke(card)
        assertEquals(1, edits)
        presenter.toggleReveal(card.id)
        actions.onEditEntry?.invoke(card)
        assertEquals(1, edits)
        presenter.toggleReveal(card.id)
        presenter.lock()
        actions.onEditEntry?.invoke(card)
        assertEquals(1, edits)
        entry.destroy()
    }

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
    fun callbacksFromOldFramesBecomeInertAfterLock() {
        val list = EntryList().apply { addEntry(totp("Bank", "alice"), false) }
        var calls = 0
        var locks = 0
        var changes = 0
        val presenter = VaultPresenter(
            VaultProjection(list, EntryListPresentation(list)),
            VaultFilter(combination = VaultFilterCombination.SEARCH_AND_TAGS),
            copyCode = { calls++ }, advanceCounter = { calls++ },
            entryActions = { calls++ }, enterPin = { calls++ }, deleteEntry = { calls++ },
            sortSelected = { calls++ }, lockVault = { locks++ }, onChange = { changes++ }
        )
        val card = presenter.frame(1_000L).cards.single()
        val old = presenter.actions()
        presenter.toggleReveal(card.id)
        presenter.applyFilter(presenter.filter.withQuery("alice"))
        old.onLock()
        val changesAtLock = changes
        // Exercise both already-captured callbacks and callbacks requested after locking.
        for (actions in listOf(old, presenter.actions())) {
            actions.onCopyToken(card)
            actions.onToggleReveal(card)
            actions.onNextCounter!!.invoke(card)
            actions.onEntryActions!!.invoke(card)
            actions.onEnterPin!!.invoke(card)
            actions.onDelete!!.invoke(card)
            actions.onQueryChange("bank")
            actions.onSelectCategory(EntryListPresentation.Category.OTP)
            actions.onToggleTag("work")
            actions.onToggleUntagged()
            actions.onClearFilters()
            actions.onSelectSort(Constants.SortMode.ISSUER)
            actions.onLock()
        }
        presenter.entryRemoved(card.id)
        assertEquals(0, calls)
        assertEquals(1, locks)
        assertEquals(changesAtLock, changes)
        assertFalse(presenter.isRevealed(card.id))
        assertEquals("", presenter.filter.query)
        assertTrue(presenter.frame(1_000L).cards.isEmpty())
        assertEquals(0, presenter.frame(1_000L).totalEntries)
    }

    @Test
    fun throwingHostCleanupStillLeavesPresenterLockedAndNotifiesTheView() {
        val list = EntryList().apply { addEntry(note(), false) }
        var changes = 0
        var copies = 0
        val presenter = VaultPresenter(
            VaultProjection(list, EntryListPresentation(list)),
            VaultFilter(combination = VaultFilterCombination.SEARCH_AND_TAGS),
            copyCode = { copies++ }, lockVault = { throw IllegalStateException("cleanup failed") },
            onChange = { changes++ }
        )
        val card = presenter.frame(0L).cards.single()
        assertTrue(runCatching { presenter.lock() }.isFailure)
        presenter.actions().onCopyToken(card)
        presenter.toggleReveal(card.id)
        presenter.lock()
        assertEquals(1, changes)
        assertEquals(0, copies)
        assertTrue(presenter.frame(0L).cards.isEmpty())
    }

    @Test
    fun deletionIsOptionalAndRefreshKeepsSelectionButForgetsReveal() {
        val list = EntryList().apply { addEntry(note(), false) }
        val projection = VaultProjection(list, EntryListPresentation(list))
        val filter = VaultFilter(query = "Recovery", combination = VaultFilterCombination.SEARCH_AND_TAGS)
        assertNull(VaultPresenter(projection, filter).actions().onDelete)
        var requested: Long? = null
        val presenter = VaultPresenter(projection, filter, deleteEntry = { requested = it.id })
        val card = presenter.frame(0L).cards.single()
        presenter.toggleReveal(card.id)
        presenter.actions().onDelete!!.invoke(card)
        assertEquals(card.id, requested)
        // Requesting confirmation alone does not remove the row.
        assertEquals(1, presenter.frame(0L).cards.size)
        list.removeEntry(0)
        presenter.entryRemoved(card.id)
        assertFalse(presenter.isRevealed(card.id))
        assertEquals(filter, presenter.filter)
        assertTrue(presenter.frame(0L).cards.isEmpty())
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
        assertTrue("a frame built after lock is empty", harness.presenter.frame(0L).cards.isEmpty())
    }

    @Test
    fun theLockCallbackAlreadySeesMaskedRows() {
        val list = EntryList().apply { addEntry(note(), false) }
        lateinit var presenter: VaultPresenter
        presenter = VaultPresenter(
            VaultProjection(list, EntryListPresentation(list)),
            VaultFilter(combination = VaultFilterCombination.SEARCH_AND_TAGS),
            lockVault = { assertTrue(presenter.frame(0L).cards.isEmpty()) }
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
