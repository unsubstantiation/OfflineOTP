// Compile-only keyboard API subset. No input events are generated.
package androidx.compose.foundation.text
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
class KeyboardOptions(
    val keyboardType: KeyboardType = KeyboardType.Text,
    val imeAction: ImeAction = ImeAction.Default
)
interface KeyboardActionScope
class KeyboardActions(
    val onDone: (KeyboardActionScope.() -> Unit)? = null,
    val onNext: (KeyboardActionScope.() -> Unit)? = null
)
