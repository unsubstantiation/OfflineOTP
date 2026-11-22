package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared Compose form for selecting an entry's tags and creating new bounded tags.
 *
 * Host adapters supply localized strings, [maxTagChars], live [tagCounts], and commit callbacks.
 * Canceling discards uncommitted edits in the form.
 */
@Composable
fun TagsDialog(
    inputKey: Any,
    initialStates: Map<String, Boolean>,
    onTagCreated: (String) -> Unit,
    onSelectionConfirmed: (Map<String, Boolean>) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Tags",
    newTagLabel: String = "New tag",
    addTagLabel: String = "Add tag",
    confirmLabel: String = "OK",
    dismissLabel: String = "Cancel",
    maxTagChars: Int = 64,
    tagCounts: Map<String, Int> = emptyMap(),
    errorText: String? = null,
    dismissOnConfirm: Boolean = true
) {
    var newTag by remember(inputKey) { mutableStateOf("") }
    var currentStates by remember(inputKey) { mutableStateOf(initialStates) }

    fun dismiss() {
        currentStates = initialStates
        newTag = ""
        onDismiss()
    }

    fun addTag() {
        val trimmed = newTag.trim()
        if (trimmed.isEmpty() || trimmed.length > maxTagChars) return
        val updated = LinkedHashMap(currentStates)
        updated[trimmed] = true
        currentStates = updated
        newTag = ""
        onTagCreated(trimmed)
    }

    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text(text = title) },
        text = {
            ScrollableDialogBody {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { if (it.length <= maxTagChars) newTag = it },
                    label = { Text(text = newTagLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                ForestTextAction(
                    text = addTagLabel,
                    enabled = newTag.trim().isNotEmpty(),
                    onClick = { addTag() }
                )
                for ((tag, isChecked) in currentStates.entries.sortedBy { it.key.lowercase() }) {
                    val count = tagCounts[tag]
                    val label = if (count != null) "$tag ($count)" else tag
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val updated = LinkedHashMap(currentStates)
                                updated[tag] = !isChecked
                                currentStates = updated
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                val updated = LinkedHashMap(currentStates)
                                updated[tag] = checked
                                currentStates = updated
                            }
                        )
                        Text(text = label)
                    }
                }
                if (errorText != null) Text(text = errorText)
            }
        },
        confirmButton = {
            ForestTextAction(text = confirmLabel, onClick = {
                val trimmed = newTag.trim()
                val finalStates = if (trimmed.isNotEmpty() && trimmed.length <= maxTagChars) {
                    LinkedHashMap(currentStates).also {
                        it[trimmed] = true
                        onTagCreated(trimmed)
                    }
                } else {
                    currentStates
                }
                newTag = ""
                onSelectionConfirmed(finalStates)
                if (dismissOnConfirm) onDismiss()
            })
        },
        dismissButton = {
            ForestTextAction(text = dismissLabel, onClick = { dismiss() })
        }
    )
}
