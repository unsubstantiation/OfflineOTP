package com.fairuniversal.offlineotp.ui.unlock

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fairuniversal.offlineotp.ui.vault.VaultStrings

/** JVM host adapter only. The production UI lives in the native-compatible foundation module. */
@Composable
fun UnlockScreen(strings: VaultStrings, onSubmit: (String) -> Unit, modifier: Modifier = Modifier,
                 error: String? = null, busy: Boolean = false, hint: String? = null,
                 onHardwareUnlock: (() -> Unit)? = null,
                 onRestore: (() -> Unit)? = null,
                 creating: Boolean = false,
                 onCreate: (String, String) -> Unit = { _, _ -> }) {
    SharedUnlockScreen(
        UnlockStrings(strings.appTitle, strings.passwordLabel, strings.unlockAction, strings.unlocking),
        onSubmit, modifier, error, busy, hint, onHardwareUnlock,
        creating = creating, onCreate = onCreate, onRestore = onRestore)
}
