package com.fairuniversal.offlineotp.ui.unlock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fairuniversal.offlineotp.ui.theme.ForestType

/** Shared authentication layout. A host may supply an OS-integrated input adapter without copying secrets here. */
@Composable
fun SharedAuthenticationScreen(title: String, message: String, unlockLabel: String, busy: Boolean,
                               hardwareLabel: String?, biometricLabel: String?,
                               onUnlock: () -> Unit, onHardware: () -> Unit, onBiometric: () -> Unit,
                               credentialInput: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = ForestType.title)
        Text(message)
        credentialInput()
        if (busy) LinearProgressIndicator()
        com.fairuniversal.offlineotp.ui.vault.ForestTextAction(text = unlockLabel, enabled = !busy, onClick = onUnlock)
        hardwareLabel?.let { com.fairuniversal.offlineotp.ui.vault.ForestTextAction(text = it, enabled = !busy, onClick = onHardware) }
        biometricLabel?.let { com.fairuniversal.offlineotp.ui.vault.ForestTextAction(text = it, enabled = !busy, onClick = onBiometric) }
    }
}
