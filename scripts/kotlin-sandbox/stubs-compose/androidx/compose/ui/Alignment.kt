// Compose Multiplatform dependency stub: androidx.compose.ui.Alignment.
package androidx.compose.ui

interface Alignment {
    interface Vertical
    interface Horizontal

    companion object {
        val Top: Vertical = object : Vertical {}
        val CenterVertically: Vertical = object : Vertical {}
        val Bottom: Vertical = object : Vertical {}
        val Start: Horizontal = object : Horizontal {}
        val CenterHorizontally: Horizontal = object : Horizontal {}
        val End: Horizontal = object : Horizontal {}
        val Center: Alignment = object : Alignment {}
    }
}
