// Compile-only Compose signature subset. No gestures or layout execute in the sandbox.
package androidx.compose.ui.graphics
import androidx.compose.ui.Modifier
class GraphicsLayerScope {
    var translationY: Float = 0f
    var shadowElevation: Float = 0f
}
fun Modifier.graphicsLayer(block: GraphicsLayerScope.() -> Unit): Modifier = this
