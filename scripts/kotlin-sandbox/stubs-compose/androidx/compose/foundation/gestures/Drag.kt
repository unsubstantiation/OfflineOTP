// Compile-only Compose signature subset. No gestures or layout execute in the sandbox.
package androidx.compose.foundation.gestures
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.lazy.LazyListState
suspend fun PointerInputScope.detectDragGesturesAfterLongPress(onDragStart: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {}, onDragCancel: () -> Unit = {}, onDrag: (PointerInputChange, Offset) -> Unit) {}
suspend fun LazyListState.scrollBy(value: Float): Float = value
