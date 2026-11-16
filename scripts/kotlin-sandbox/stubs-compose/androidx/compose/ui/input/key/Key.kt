// Compile-only physical keyboard API subset.
package androidx.compose.ui.input.key
import androidx.compose.ui.Modifier
enum class Key { Enter, NumPadEnter }
enum class KeyEventType { KeyDown, KeyUp }
class KeyEvent
val KeyEvent.key: Key get() = Key.Enter
val KeyEvent.type: KeyEventType get() = KeyEventType.KeyUp
fun Modifier.onPreviewKeyEvent(onPreviewKeyEvent: (KeyEvent) -> Boolean): Modifier = this
