// Compile-only Compose signature subset. No gestures or layout execute in the sandbox.
package androidx.compose.ui.input.pointer
import androidx.compose.ui.Modifier
interface PointerInputScope
class PointerInputChange { fun consume() {} }
fun Modifier.pointerInput(key1: Any?, block: suspend PointerInputScope.() -> Unit): Modifier = this
