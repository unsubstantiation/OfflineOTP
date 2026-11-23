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
    fun preferencesImportIsOptionalAndTerminalAfterLock() {
        val list = EntryList()
        val presenter = VaultPresenter(VaultProjection(list, EntryListPresentation(list)), VaultFilter())
        assertNull(presenter.actions().onImportPreferences)
        var calls = 0
        val actions = presenter.actions(importPreferences = { calls++ })
        actions.onImportPreferences?.invoke()
        assertEquals(1, calls)
        presenter.lock()
        actions.onImportPreferences?.invoke()
        assertEquals(1, calls)
    }

    @Test
    fun thunderbirdImportExportAndTagRuleCyclingRespectSessionAndSelectionBounds() {
        val first = totp("GitHub", "alice").apply { setTags(arrayListOf("work", "dev")) }
        val second = totp("GitLab", "bob").apply { setTags(arrayListOf("work")) }
        val list = EntryList().apply { addEntry(first); addEntry(second) }
        val presenter = VaultPresenter(
            VaultProjection(list, EntryListPresentation(list)),
            VaultFilter(combination = VaultFilterCombination.SEARCH_AND_TAGS)
        )
        assertNull(presenter.actions().onImportThunderbird)
        assertNull(presenter.actions().onExportThunderbird)
        var imports = 0
        var exports = 0
        val actions = presenter.actions(
            importThunderbird = { imports++ },
            exportThunderbird = { exports++ }
        )
        actions.onToggleTag("work")
        actions.onToggleTag("dev")
        assertEquals(listOf("work", "dev"), presenter.filter.selectedTags)
        assertEquals(2, presenter.frame(0L).cards.size)

        actions.onCycleTagRule?.invoke()
        assertEquals(Constants.TagFunctionality.AND, presenter.filter.tagFunctionality)
        assertEquals(1, presenter.frame(0L).cards.size)

        actions.onCycleTagRule?.invoke()
        assertEquals(Constants.TagFunctionality.SINGLE, presenter.filter.tagFunctionality)
        assertEquals(listOf("work"), presenter.filter.selectedTags)

        actions.onCycleTagRule?.invoke()
        assertEquals(Constants.TagFunctionality.OR, presenter.filter.tagFunctionality)

        actions.onImportThunderbird?.invoke()
        actions.onExportThunderbird?.invoke()
        assertEquals(1, imports)
        assertEquals(1, exports)

        presenter.lock()
        actions.onImportThunderbird?.invoke()
        actions.onExportThunderbird?.invoke()
        actions.onCycleTagRule?.invoke()
        assertEquals(1, imports)
        assertEquals(1, exports)
        first.destroy()
        second.destroy()
    }

    @Test
    fun preferencesExportIsOptionalAndTerminalAfterLock() {
        val list = EntryList()
        val presenter = VaultPresenter(VaultProjection(list, EntryListPresentation(list)), VaultFilter())
        assertNull(presenter.actions().onExportPreferences)
        var calls = 0
        val actions = presenter.actions(exportPreferences = { calls++ })
        actions.onExportPreferences?.invoke()
        assertEquals(1, calls)
        presenter.lock()
        actions.onExportPreferences?.invoke()
        assertEquals(1, calls)
    }

    @Test
    fun openPgpExportIsOptionalAndTerminalAfterLock() {
        val list = EntryList()
        val presenter = VaultPresenter(VaultProjection(list, EntryListPresentation(list)), VaultFilter())
        assertNull(presenter.actions().onExportOpenPgp)
        var calls = 0
        val actions = presenter.actions(exportOpenPgp = { calls++ })
        actions.onExportOpenPgp?.invoke()
        assertEquals(1, calls)
        presenter.lock()
        actions.onExportOpenPgp?.invoke()
        assertEquals(1, calls)
    }

    @Test
    fun openPgpImportIsOptionalAndTerminalAfterLock() {
        val list = EntryList()
        val presenter = VaultPresenter(VaultProjection(list, EntryListPresentation(list)), VaultFilter())
        assertNull(presenter.actions().onImportOpenPgp)
        var calls = 0
        val actions = presenter.actions(importOpenPgp = { calls++ })
        actions.onImportOpenPgp?.invoke()
        assertEquals(1, calls)
        presenter.lock()
        actions.onImportOpenPgp?.invoke()
        assertEquals(1, calls)
    }

    @Test
    fun linScpExportIsOptionalAndTerminalAfterLock() {
        val list = EntryList()
        val presenter = VaultPresenter(VaultProjection(list, EntryListPresentation(list)), VaultFilter())
        assertNull(presenter.actions().onExportLinScp)
        var calls = 0
        val actions = presenter.actions(exportLinScp = { calls++ })
        actions.onExportLinScp?.invoke()
        assertEquals(1, calls)
        presenter.lock()
        actions.onExportLinScp?.invoke()
        assertEquals(1, calls)
    }

    @Test
    fun linScpImportIsOptionalAndTerminalAfterLock() {
        val list = EntryList()
        val presenter = VaultPresenter(VaultProjection(list, EntryListPresentation(list)), VaultFilter())
        assertNull(presenter.actions().onImportLinScp)
        var calls = 0
        val actions = presenter.actions(importLinScp = { calls++ })
        actions.onImportLinScp?.invoke()
        assertEquals(1, calls)
        presenter.lock()
        actions.onImportLinScp?.invoke()
        assertEquals(1, calls)
    }

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

    @Test
    fun setHideIssuerAndSetAppearanceUpdateFramesUntilLocked() {
        val harness = harnessOf(totp("GitHub", "alice"))
        assertEquals("GitHub", harness.presenter.frame(1_000L).cards.single().issuer)
        harness.presenter.setHideIssuer(true)
        assertEquals("", harness.presenter.frame(1_000L).cards.single().issuer)
        harness.presenter.setAppearance(com.fairuniversal.offlineotp.Utilities.Constants.CardLayouts.COMPACT, false)
        assertEquals("GitHub", harness.presenter.frame(1_000L).cards.single().issuer)
        assertEquals(
            com.fairuniversal.offlineotp.Utilities.Constants.CardLayouts.COMPACT,
            harness.presenter.appearance.cardLayout
        )
        harness.presenter.lock()
        harness.presenter.setHideIssuer(true)
        assertTrue(harness.presenter.appearance.showIssuer)
    }

    @Test
    fun setDisplaySettingsControlsStandardIndividualCountdownsUntilLocked() {
        val harness = harnessOf(totp("GitHub", "alice"))
        assertTrue(harness.presenter.frame(1_000L).cards.single().showCountdown)
        harness.presenter.setDisplaySettings(
            layout = com.fairuniversal.offlineotp.Utilities.Constants.CardLayouts.DEFAULT,
            hideIssuer = false,
            showPreviousToken = true,
            tokenGroupSize = 3,
            showIndividualCountdowns = false
        )
        assertFalse(harness.presenter.appearance.showIndividualCountdowns)
        assertFalse(harness.presenter.frame(1_000L).cards.single().showCountdown)
        harness.presenter.lock()
        harness.presenter.setDisplaySettings(
            layout = com.fairuniversal.offlineotp.Utilities.Constants.CardLayouts.DEFAULT,
            hideIssuer = false,
            showPreviousToken = true,
            tokenGroupSize = 3,
            showIndividualCountdowns = true
        )
        assertFalse(harness.presenter.appearance.showIndividualCountdowns)
    }

    @Test
    fun revealStateSeedsFromInitialIdsPersistsThroughHookAndSyncsAfterRestore() {
        val list = EntryList()
        val first = totp("GitHub", "alice")
        val second = totp("GitLab", "bob")
        list.addEntry(first)
        list.addEntry(second)
        val persisted = LinkedHashMap<Long, Boolean>()
        var allowSaves = true
        val presenter = VaultPresenter(
            projection = VaultProjection(list, EntryListPresentation(list)),
            initialFilter = VaultFilter(combination = VaultFilterCombination.SEARCH_AND_TAGS),
            initialRevealedIds = setOf(first.getListId()),
            revealChanged = { id, visible ->
                if (!allowSaves && visible) false
                else {
                    persisted[id] = visible
                    true
                }
            }
        )
        assertTrue(presenter.frame(1_000L).cards.first { it.id == first.getListId() }.revealed)
        assertFalse(presenter.frame(1_000L).cards.first { it.id == second.getListId() }.revealed)

        presenter.toggleReveal(first.getListId())
        assertFalse(presenter.frame(1_000L).cards.first { it.id == first.getListId() }.revealed)
        assertEquals(false, persisted[first.getListId()])

        allowSaves = false
        presenter.toggleReveal(first.getListId())
        assertFalse("failed reveal save fails closed", presenter.frame(1_000L).cards.first { it.id == first.getListId() }.revealed)

        allowSaves = true
        presenter.toggleReveal(second.getListId())
        assertTrue(presenter.frame(1_000L).cards.first { it.id == second.getListId() }.revealed)
        assertEquals(true, persisted[second.getListId()])

        presenter.syncRevealedIds(setOf(first.getListId()))
        assertTrue(presenter.frame(1_000L).cards.first { it.id == first.getListId() }.revealed)
        assertFalse(presenter.frame(1_000L).cards.first { it.id == second.getListId() }.revealed)
    }

    @Test
    fun setCompleteDisplaySettingsWiresLabelSizeExpiringHighlightAndTapModes() {
        val harness = harnessOf(totp("GitHub", "alice"))
        // At t = 25_000ms on a 30s period, 5s remain: default expiringSeconds=8 marks expiring=true.
        assertTrue(harness.presenter.frame(25_000L).cards.single().expiring)
        harness.presenter.setDisplaySettings(
            layout = Constants.CardLayouts.COMPACT,
            hideIssuer = false,
            showPreviousToken = true,
            tokenGroupSize = 3,
            showIndividualCountdowns = true,
            labelDisplay = Constants.LabelDisplay.SCROLL,
            showThumbnails = false,
            labelSizeSp = 18,
            highlightExpiringTokens = false,
            tapSingle = com.fairuniversal.offlineotp.ui.portable.PortableTapMode.COPY,
            tapDouble = com.fairuniversal.offlineotp.ui.portable.PortableTapMode.REVEAL
        )
        assertEquals(18, harness.presenter.appearance.labelSizeSp)
        assertEquals(-1, harness.presenter.appearance.expiringSeconds)
        assertFalse(harness.presenter.frame(25_000L).cards.single().expiring)

        val actions = harness.presenter.actions()
        val card = harness.presenter.frame(25_000L).cards.single()
        assertFalse(card.revealed)
        // Single tap is COPY -> copies raw digits without revealing.
        actions.onCardTap?.invoke(card)
        assertEquals(1, harness.copied.size)
        assertFalse(harness.presenter.frame(25_000L).cards.single().revealed)
        // Double tap is REVEAL -> reveals the row.
        actions.onCardDoubleTap?.invoke(card)
        assertTrue(harness.presenter.frame(25_000L).cards.single().revealed)

        // NOTHING + NOTHING keeps a non-null no-op onCardTap so the card does not fall back to onToggleReveal.
        harness.presenter.setDisplaySettings(
            layout = Constants.CardLayouts.DEFAULT,
            hideIssuer = false,
            showPreviousToken = true,
            tokenGroupSize = 3,
            showIndividualCountdowns = true,
            labelDisplay = Constants.LabelDisplay.TRUNCATE,
            showThumbnails = true,
            labelSizeSp = 0,
            highlightExpiringTokens = true,
            tapSingle = com.fairuniversal.offlineotp.ui.portable.PortableTapMode.NOTHING,
            tapDouble = com.fairuniversal.offlineotp.ui.portable.PortableTapMode.NOTHING
        )
        assertNull(harness.presenter.appearance.labelSizeSp)
        val noTapActions = harness.presenter.actions()
        assertNull(noTapActions.onCardDoubleTap)
        noTapActions.onCardTap?.invoke(harness.presenter.frame(25_000L).cards.single())
        assertTrue("NOTHING single-tap leaves reveal unchanged", harness.presenter.frame(25_000L).cards.single().revealed)

        // Default REVEAL + NOTHING yields null onCardTap/onCardDoubleTap so default clickable reveal path runs.
        harness.presenter.setDisplaySettings(
            layout = Constants.CardLayouts.DEFAULT,
            hideIssuer = false,
            showPreviousToken = true,
            tokenGroupSize = 3,
            showIndividualCountdowns = true,
            labelDisplay = Constants.LabelDisplay.TRUNCATE,
            showThumbnails = true,
            labelSizeSp = 0,
            highlightExpiringTokens = true,
            tapSingle = com.fairuniversal.offlineotp.ui.portable.PortableTapMode.REVEAL,
            tapDouble = com.fairuniversal.offlineotp.ui.portable.PortableTapMode.NOTHING
        )
        val defaultTapActions = harness.presenter.actions()
        assertNull(defaultTapActions.onCardTap)
        assertNull(defaultTapActions.onCardDoubleTap)
    }

    @Test
    fun globalRolloverProgressRevealTimeoutAndEntryMetadataDetailsRespectSecurityBoundaries() {
        val emptyList = EntryList()
        val emptyPresenter = VaultPresenter(
            VaultProjection(emptyList, EntryListPresentation(emptyList)),
            VaultFilter(combination = VaultFilterCombination.SEARCH_AND_TAGS)
        )
        val emptyFrame = emptyPresenter.frame(10_000L)
        assertEquals(0f, emptyFrame.globalProgress)
        assertFalse(emptyFrame.globalExpiring)

        val persisted = HashMap<Long, Boolean>()
        val totpEntry = totp("GitHub", "alice@example.com").apply {
            setHiddenLabel("super-secret-hidden-pw")
            setTags(mutableListOf("work", "2fa"))
        }
        val list = EntryList().apply { addEntry(totpEntry) }
        val presenter = VaultPresenter(
            VaultProjection(list, EntryListPresentation(list)),
            VaultFilter(combination = VaultFilterCombination.SEARCH_AND_TAGS),
            revealChanged = { id, visible -> persisted[id] = visible; true }
        )

        val earlyFrame = presenter.frame(15_000L)
        assertEquals(0.5f, earlyFrame.globalProgress)
        assertFalse(earlyFrame.globalExpiring)
        val expiringFrame = presenter.frame(25_000L)
        assertTrue(expiringFrame.globalExpiring)

        presenter.setDisplaySelection(
            com.fairuniversal.offlineotp.ui.portable.PortableCardDisplaySelection(
                layout = com.fairuniversal.offlineotp.ui.portable.PortableCardLayout.COMPACT,
                hideIssuer = false,
                showPreviousToken = true,
                tokenGroupSize = 3,
                showIndividualCountdowns = false,
                labelDisplay = com.fairuniversal.offlineotp.ui.portable.PortableLabelDisplay.TRUNCATE,
                showThumbnails = true,
                labelSizeSp = 16,
                highlightExpiringTokens = true,
                tapSingle = com.fairuniversal.offlineotp.ui.portable.PortableTapMode.REVEAL,
                tapDouble = com.fairuniversal.offlineotp.ui.portable.PortableTapMode.COPY,
                hideGlobalTimeout = true,
                tapToRevealTimeoutSeconds = 15
            )
        )
        assertTrue(presenter.appearance.hideGlobalTimeout)
        assertEquals(15, presenter.tapToRevealTimeoutSeconds)

        val cardId = presenter.frame(1_000L).cards.single().id
        presenter.toggleReveal(cardId)
        assertTrue(presenter.frame(15_999L).cards.single().revealed)
        assertFalse("revealed card auto-hides once timeout elapses", presenter.frame(16_000L).cards.single().revealed)
        assertEquals(false, persisted[cardId])

        val otpDetails = com.fairuniversal.offlineotp.Utilities.EntryMetadataDetails.format(totpEntry)!!
        assertTrue(otpDetails.contains("Type: TOTP"))
        assertTrue(otpDetails.contains("Issuer: GitHub"))
        assertTrue(otpDetails.contains("Account: alice@example.com"))
        assertTrue(otpDetails.contains("Algorithm: SHA1"))
        assertTrue(otpDetails.contains("Digits: 6"))
        assertTrue(otpDetails.contains("Period: 30s"))
        assertTrue(otpDetails.contains("Tags: work, 2fa"))
        assertFalse(otpDetails.contains("super-secret-hidden-pw"))

        val sshEntry = com.fairuniversal.offlineotp.Database.Entry.sshKey(
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITestKey deploy@prod",
            "-----BEGIN OPENSSH PRIVATE KEY-----\nSECRET_PRIVATE_MATERIAL",
            "Prod Bastion",
            "deploy",
            mutableListOf(),
            com.fairuniversal.offlineotp.Utilities.SshConnection.of("bastion.example.com", 2222, "ubuntu", "top-secret-ssh-pass")
        )
        val sshDetails = com.fairuniversal.offlineotp.Utilities.EntryMetadataDetails.format(sshEntry)!!
        assertTrue(sshDetails.contains("Type: Keys"))
        assertTrue(sshDetails.contains("Key type: ssh-ed25519"))
        assertTrue(sshDetails.contains("Host: bastion.example.com"))
        assertTrue(sshDetails.contains("Port: 2222"))
        assertTrue(sshDetails.contains("Username: ubuntu"))
        assertTrue(sshDetails.contains("Private key: Stored"))
        assertFalse(sshDetails.contains("SECRET_PRIVATE_MATERIAL"))
        assertFalse(sshDetails.contains("top-secret-ssh-pass"))

        val noteEntry = com.fairuniversal.offlineotp.Database.Entry().apply {
            setType(com.fairuniversal.offlineotp.Database.Entry.OTPType.NOTE)
            setLabel("Vault recovery")
            setNote("recovery-words")
        }
        assertNull(com.fairuniversal.offlineotp.Utilities.EntryMetadataDetails.format(noteEntry))

        totpEntry.destroy()
        sshEntry.destroy()
        noteEntry.destroy()
    }
}
