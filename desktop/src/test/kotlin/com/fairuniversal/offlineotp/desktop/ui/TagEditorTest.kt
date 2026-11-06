package com.fairuniversal.offlineotp.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.fairuniversal.offlineotp.Utilities.InputPolicy
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.SwingUtilities

class TagEditorTest {
    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait { block() }

    @Test fun editorAddsRemovesAndPreservesImportedNames() = onEdt {
        ForestTheme.install()
        val original = mutableListOf("team, west", "line\nbreak", "<html>not markup")
        val panel = TagEditorPanel(original, listOf("work", "team, west", "  imported  "))
        panel.input.editor.item = "  imported  "
        panel.addPending()
        panel.input.editor.item = "  work  "
        panel.addPending()
        panel.input.editor.item = "work"
        panel.addPending() // no duplicate
        assertEquals(listOf("team, west", "line\nbreak", "<html>not markup", "  imported  ", "work"), panel.tags())
        panel.list.selectedIndices = intArrayOf(0, 2)
        panel.removeSelected()
        assertEquals(listOf("line\nbreak", "  imported  ", "work"), panel.tags())
        assertEquals(listOf("team, west", "line\nbreak", "<html>not markup"), original)
    }

    @Test fun boundsAreEnforcedWithoutLosingCurrentTags() = onEdt {
        ForestTheme.install()
        val panel = TagEditorPanel(emptyList(), emptyList())
        panel.input.editor.item = "x".repeat(InputPolicy.MAX_TAG_CHARS + 1)
        try { panel.addPending(); throw AssertionError("long tag accepted") } catch (_: IllegalArgumentException) { }
        assertTrue(panel.tags().isEmpty())
        repeat(InputPolicy.MAX_TAGS) { panel.input.editor.item = "tag$it"; panel.addPending() }
        panel.input.editor.item = "one too many"
        try { panel.addPending(); throw AssertionError("too many tags accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(InputPolicy.MAX_TAGS, panel.tags().size)
    }

    @Test fun tagLabelsNeverEnableSwingHtml() = onEdt {
        val renderer = PlainTagRenderer()
        val text = "<html><img src='https://invalid.example/image'>"
        val label = renderer.getListCellRendererComponent(JList<String>(), text, 0, false, false) as JLabel
        assertEquals(text, label.text)
        assertEquals(true, label.getClientProperty("html.disable"))
        assertEquals(null, label.getClientProperty("html"))
        assertFalse(TagChoice(TagChoice.Kind.TAG, "All tags").sameFilter(TagChoice(TagChoice.Kind.ALL)))
    }

    @Test fun multiTagFilterPanelSupportsOrAndSingleModes() = onEdt {
        ForestTheme.install()
        val panel = MultiTagFilterPanel(
            counts = mapOf("work" to 2, "ops" to 1),
            untaggedCount = 3,
            initialTags = listOf("work", "ops"),
            initialIncludeUntagged = true,
            initialFunctionality = com.fairuniversal.offlineotp.Utilities.Constants.TagFunctionality.AND
        )
        val initial = panel.selection()
        assertEquals(listOf("ops", "work"), initial.selectedTags)
        assertTrue(initial.includeUntagged)
        assertEquals(com.fairuniversal.offlineotp.Utilities.Constants.TagFunctionality.AND, initial.functionality)

        // Switching to SINGLE enforces at most one selection.
        panel.modeBox.selectedIndex = 2
        val narrowed = panel.selection()
        assertEquals(com.fairuniversal.offlineotp.Utilities.Constants.TagFunctionality.SINGLE, narrowed.functionality)
        assertTrue(narrowed.selectedTags.size + (if (narrowed.includeUntagged) 1 else 0) <= 1)
    }
}
