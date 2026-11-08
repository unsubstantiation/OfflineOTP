// Compose Multiplatform dependency stub: androidx.compose.ui.platform (Android artifacts).
//
// Compile-only stand-ins for the Android interop types `:ui`'s androidMain host uses. The real
// ComposeView subclasses AbstractComposeView and owns a composition; this one is an inert ViewGroup,
// and the ViewCompositionStrategy objects carry no behaviour. Nothing here is a runtime.
package androidx.compose.ui.platform

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

import androidx.compose.runtime.Composable

interface ViewCompositionStrategy {
    object DisposeOnDetachedFromWindow : ViewCompositionStrategy
    object DisposeOnDetachedFromWindowOrReleasedFromPool : ViewCompositionStrategy
    object DisposeOnViewTreeLifecycleDestroyed : ViewCompositionStrategy
}

// AbstractComposeView is the extension point; ComposeView itself is final in AndroidX.
abstract class AbstractComposeView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup() {
    @Composable
    abstract fun Content()

    fun setViewCompositionStrategy(strategy: ViewCompositionStrategy) {
    }

    fun disposeComposition() {
    }
}

class ComposeView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {
    @Composable
    override fun Content() {
    }

    fun setContent(content: @Composable () -> Unit) {
    }
}
