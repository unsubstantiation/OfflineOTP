package org.shadowice.flocke.andotp.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.EntryList
import org.shadowice.flocke.andotp.View.EntryListPresentation
import org.shadowice.flocke.andotp.desktop.model.EntryTableModel

import javax.swing.JButton
import javax.swing.JTable

/**
 * The tab strip is derived from [EntryListPresentation.Category], so every vault kind the shared
 * projection knows about has a reachable tab. Before this guard the strip was a hard-coded list of
 * four labels: email entries imported from a FairEmail backup were filtered in by `:core` but no
 * tab could ever select them, so they were invisible in the desktop client.
 */
class MainScreenTabsTest {

    private fun screen(): MainScreen {
        val table = JTable(EntryTableModel(EntryListPresentation(EntryList()).apply {
            showReorderedEntries()
        }))
        return MainScreen(table, JButton("Copy selected"))
    }

    @Test
    fun everyProjectionCategoryHasOneTabInTheSameOrder() {
        val categories = screen().categories
        assertEquals(EntryListPresentation.Category.entries.size, categories.tabCount)
        EntryListPresentation.Category.entries.forEachIndexed { index, category ->
            assertEquals(categoryTitle(category), categories.getTitleAt(index))
        }
    }

    @Test
    fun theEmailTabIsPresentAndNamedLikeTheAndroidTab() {
        val categories = screen().categories
        val index = EntryListPresentation.Category.EMAILS.ordinal
        assertEquals("Email", categories.getTitleAt(index))
        // The last tab is the email tab: a new category must be appended, never inserted in a way
        // that shifts the index-to-category mapping MainWindow relies on.
        assertEquals(categories.tabCount - 1, index)
    }

    @Test
    fun theEmptyEmailTabPointsAtTheFairEmailFlow() {
        val screen = screen()
        screen.setUnlocked(true)
        screen.categories.selectedIndex = EntryListPresentation.Category.EMAILS.ordinal
        val descriptions = fields(screen.panel).filterIsInstance<javax.swing.JLabel>().map { it.text }
        assertTrue(descriptions.toString(),
            descriptions.any { it.orEmpty().contains("FairEmail") })
    }

    private fun fields(component: java.awt.Component): List<java.awt.Component> = listOf(component) +
        if (component is java.awt.Container) component.components.flatMap { fields(it) } else emptyList()
}
