package org.shadowice.flocke.andotp.Dialogs

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import org.shadowice.flocke.andotp.R
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
                val newTag = input.text.toString()
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
            AdapterView.OnItemClickListener { _, view, _, _ ->
                val checkedTextView = view as CheckedTextView
                checkedTextView.isChecked = !checkedTextView.isChecked

                tagsAdapter.setTagState(checkedTextView.text.toString(), checkedTextView.isChecked)
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
