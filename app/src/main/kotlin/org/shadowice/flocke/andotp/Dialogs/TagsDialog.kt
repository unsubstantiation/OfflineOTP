package org.shadowice.flocke.andotp.Dialogs

import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.InputPolicy
import org.shadowice.flocke.andotp.View.TagsAdapter
import java.util.concurrent.Callable

/** Dialog for selecting an entry's tags and creating new ones. */
object TagsDialog {
    /** Shows the tag picker; the callables run after a tag was created and after the selection was confirmed. */
    @JvmStatic
    fun show(
        context: Context,
        tagsAdapter: TagsAdapter,
        newTagCallable: Callable<*>?,
        selectedTagsCallable: Callable<*>?
    ) {
        val margin = context.resources.getDimensionPixelSize(R.dimen.activity_margin)
        val marginSmall = context.resources.getDimensionPixelSize(R.dimen.activity_margin_small)
        val marginMedium = context.resources.getDimensionPixelSize(R.dimen.activity_margin_medium)

        val input = EditText(context)
        // Tags are stored in entries, so type them within the bound Entry.validate()/EntryJson
        // accept; otherwise the tag would be accepted here and reject the entry on the next write.
        input.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(InputPolicy.MAX_TAG_CHARS))
        input.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val inputLayout = FrameLayout(context)
        inputLayout.setPaddingRelative(marginMedium, marginSmall, marginMedium, 0)
        inputLayout.addView(input)

        val newTagBuilder = AlertDialog.Builder(context)
        newTagBuilder.setTitle(R.string.button_new_tag)
            .setView(inputLayout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newTag = input.text.toString().trim()
                // A blank row is not a tag; keep the picker free of unnamed entries.
                if (newTag.isEmpty()) return@setPositiveButton
                val allTags = tagsAdapter.getTagsWithState()
                allTags[newTag] = true
                tagsAdapter.setTags(allTags)
                if (newTagCallable != null) {
                    try {
                        newTagCallable.call()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }

        val tagsSelectionView = ListView(context)
        tagsSelectionView.divider = null
        tagsSelectionView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        tagsSelectionView.adapter = tagsAdapter
        tagsSelectionView.onItemClickListener =
            AdapterView.OnItemClickListener { _, view, position, _ ->
                val checkedTextView = view as? CheckedTextView ?: return@OnItemClickListener
                // The row text can carry the entry count ("tag (3)", see TagsAdapter.setTagCounts),
                // so the clicked tag is resolved by position, never by parsing the display text.
                val tag = tagsAdapter.getItem(position) ?: return@OnItemClickListener
                checkedTextView.isChecked = !checkedTextView.isChecked

                tagsAdapter.setTagState(tag, checkedTextView.isChecked)
            }

        val tagsSelectionLayout = FrameLayout(context)
        tagsSelectionLayout.setPaddingRelative(margin, marginSmall, margin, 0)
        tagsSelectionLayout.addView(tagsSelectionView)

        val tagsSelectorBuilder = AlertDialog.Builder(context)
        tagsSelectorBuilder.setTitle(R.string.label_tags)
            .setView(tagsSelectionLayout)
            .setNegativeButton(android.R.string.cancel) { dialogInterface, _ ->
                dialogInterface.dismiss()
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (selectedTagsCallable != null) {
                    try {
                        selectedTagsCallable.call()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            .setNeutralButton(R.string.button_new_tag) { _, _ ->
                newTagBuilder.create().show()
            }
            .create().show()
    }
}
