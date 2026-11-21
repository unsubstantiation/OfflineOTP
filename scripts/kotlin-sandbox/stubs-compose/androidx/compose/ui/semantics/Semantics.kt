// Compile stub for Compose Multiplatform semantics; real semantics come from the Compose runtime.
package androidx.compose.ui.semantics

import androidx.compose.ui.Modifier

class SemanticsPropertyReceiver

inline fun Modifier.semantics(
    mergeDescendants: Boolean = false,
    crossinline properties: SemanticsPropertyReceiver.() -> Unit
): Modifier = this

var SemanticsPropertyReceiver.contentDescription: String
    get() = ""
    set(value) {}
