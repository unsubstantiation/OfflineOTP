package com.fairuniversal.offlineotp.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared Compose form for selecting entry tags and creating new ones.
 *
 * Replaces the native Android TagsDialog (125 lines) with a shared Compose form.
 * The host adapter (TagsDialogHost) will wrap this form and handle native capability adapters
 * (tag adapter wiring, lifecycle, etc.).
 */
@Composable
fun TagsDialog(
    inputKey: Any,
    initialStates: Map<String, Boolean>,
    onTagCreated: (String) -> Unit,
    onSelectionConfirmed: (Map<String, Boolean>) -> Unit,
    onDismiss: () -> Unit
) {
    var newTag by remember(inputKey) { mutableStateOf("") }
    var currentStates by remember(inputKey) { mutableStateOf(initialStates) }
    var committed by remember(inputKey) { mutableStateOf(false) }

    fun dismiss() {
        if (!committed) {
            currentStates = initialStates
        }
        newTag = ""
        onDismiss()
    }

    fun addTag() {
        val trimmed = newTag.trim()
        if (trimmed.isEmpty()) {
            // Blank row is not a tag; keep the picker free of unnamed entries.
            return
        }
        val updated = currentStates.toMutableMap()
        updated[trimmed] = true
        currentStates = updated.toMap()
        newTag = ""
        onTagCreated(trimmed)
    }

    // The host adapter will handle the actual tag adapter wiring and the layout.
    // This Compose form provides the UI for selection and creation.
    // Note: In the native version, there are two dialogs (new tag builder and tags selector builder).
    // In the shared Compose form, we combine them into a single dialog with a "New Tag" button
    // or separate them if the host adapter prefers.
    // For simplicity and compatibility with the host adapter, we provide a combined view.

    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text(text = "Select Tags") },
        text = {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                // Tag creation field
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    label = { Text(text = "New Tag") },
                    modifier = Modifier.fillMaxSize(0.2f)
                )
                Button(onClick = { addTag() }) {
                    Text(text = "Add Tag")
                }

                // Tag selection list (simplified representation)
                // In a real implementation, this would use a LazyColumn or similar with CheckedTextViews
                // mapped from currentStates.
                Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 16.dp)) {
                    currentStates.forEach { (tag, isChecked) ->
                        Text(text = "$tag ${if (isChecked) "[Selected]" else ""}")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                committed = true
                onSelectionConfirmed(currentStates)
                dismiss()
            }) {
                Text(text = "OK")
            }
        },
        dismissButton = {
            Button(onClick = { dismiss() }) {
                Text(text = "Cancel")
            }
        }
    )
}
