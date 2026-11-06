package com.fairuniversal.offlineotp.desktop.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import com.fairuniversal.offlineotp.Database.EntryList
import com.fairuniversal.offlineotp.View.EntryListPresentation
import com.fairuniversal.offlineotp.desktop.model.EntryTableModel

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JTable
import javax.swing.JTextField

/**
 * The search row carries the search field, the tag filter and the sort order. At the window's
 * minimum width all three must stay usable: the Android app keeps sort in the toolbar and tag
 * filtering in a drawer, so the desktop equivalent has to fit them beside the search field without
 * squeezing it away.
 */
class MainScreenLayoutTest {

    private fun screen(): MainScreen =
        MainScreen(JTable(EntryTableModel(EntryListPresentation(EntryList()).apply {
            showReorderedEntries()
        })), JButton("Copy selected"))

    private fun laidOut(width: Int, height: Int): MainScreen {
        val screen = screen()
        screen.setUnlocked(true)
        val panel = screen.panel
        panel.setSize(Dimension(width, height))
        layOut(panel)
        return screen
    }

    private fun layOut(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach { layOut(it) }
        }
    }

    private fun components(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap { components(it) } else emptyList()

    @Test
    fun theSearchFieldStaysWideEnoughBesideTheTagAndSortControls() {
        val screen = laidOut(720, 520)
        val search = screen.search as JTextField
        assertTrue("search field collapsed to ${search.width}px", search.width >= 240)
        assertTrue(screen.tagFilter.width > 0)
        assertTrue(screen.sortOrder.width > 0)
    }

    @Test
    fun theSortOrderOffersEveryModeWithTheAndroidLabels() {
        val screen = screen()
        val modes = (0 until screen.sortOrder.itemCount).map { screen.sortOrder.getItemAt(it).mode }
        assertTrue(modes.containsAll(
            com.fairuniversal.offlineotp.Utilities.Constants.SortMode.entries))
        assertTrue(screen.sortOrder.itemCount ==
            com.fairuniversal.offlineotp.Utilities.Constants.SortMode.entries.size)
        val labels = (0 until screen.sortOrder.itemCount).map { screen.sortOrder.getItemAt(it).toString() }
        assertTrue(labels.containsAll(listOf("Unsorted", "Issuer", "Label", "Last used", "Most used")))
        assertTrue(screen.sortOrder.accessibleContext.accessibleName == "Sort order")
    }

    @Test
    fun theControlsAreDisabledUntilAVaultIsOpen() {
        val screen = screen()
        assertTrue(!screen.sortOrder.isEnabled)
        screen.setUnlocked(true)
        assertTrue(screen.sortOrder.isEnabled)
        // The tag combo is a JComboBox as well; both are reachable for assistive technology.
        assertTrue(components(screen.panel).filterIsInstance<JComboBox<*>>().size >= 2)
        assertTrue(components(screen.panel).any {
            it === screen.sortOrder && it.accessibleContext.accessibleName == "Sort order"
        })
    }
    @Test
    fun theRemovalPromptNamesTheEntryLikeTheAndroidDialog() {
        // Android's dialog_msg_confirm_delete: Ask "Are you sure you want to remove the account
        // \"%1$s\"?" — the desktop asks the same question instead of deleting on first click.
        assertTrue(removalConfirmationMessage("alice", "GitHub")
            .startsWith("Are you sure you want to remove the account \"alice\"?"))
        // An entry without a label is named by its issuer rather than an empty pair of quotes.
        assertTrue(removalConfirmationMessage("", "GitHub").contains("\"GitHub\""))
        assertTrue(removalConfirmationMessage("", "").isNotBlank())
    }

}
