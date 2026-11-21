// Compose Multiplatform dependency stub: androidx.compose.ui.Modifier.
package androidx.compose.ui

interface Modifier {
    infix fun then(other: Modifier): Modifier = this

    companion object : Modifier
}
