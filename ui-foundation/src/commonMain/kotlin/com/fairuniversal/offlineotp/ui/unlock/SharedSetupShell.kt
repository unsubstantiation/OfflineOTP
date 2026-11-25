package com.fairuniversal.offlineotp.ui.unlock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Common wizard chrome. Hosts enforce credential readiness and own platform result delivery. */
@Composable
fun SharedSetupShell(step: Int, count: Int, backLabel: String, nextLabel: String,
                     busy: Boolean, completed: Boolean, onBack: () -> Unit, onNext: () -> Unit,
                     content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("${step + 1} / $count", modifier = Modifier.padding(12.dp))
        Box(Modifier.weight(1f)) { content() }
        Row(Modifier.padding(12.dp)) {
            com.fairuniversal.offlineotp.ui.vault.ForestTextAction(backLabel, onClick = onBack, enabled = step > 0 && !busy && !completed)
            com.fairuniversal.offlineotp.ui.vault.ForestTextAction(nextLabel, onClick = onNext, enabled = !busy && (step < count - 1 || completed))
        }
    }
}
