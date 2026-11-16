// Compile-only keyboard API subset. No input events are generated.
package androidx.compose.foundation.text
import androidx.compose.ui.text.input.ImeAction
class KeyboardOptions(val imeAction: ImeAction = ImeAction.Default)
interface KeyboardActionScope
class KeyboardActions(val onDone: (KeyboardActionScope.() -> Unit)? = null)
