// Compose Multiplatform dependency stub: androidx.compose.ui.window (Compose Desktop).
//
// Compile-only stand-ins for the window APIs `:desktop-compose` calls. The real Window creates an
// AWT frame and starts the Compose runtime; nothing here does anything at all, and the host is only
// type-checked, never run, in the sandbox.
package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

interface ApplicationScope {
    fun exitApplication()
}

interface FrameWindowScope { val window: java.awt.Window }

class WindowState(val width: Dp, val height: Dp)

@Composable
fun rememberWindowState(width: Dp = 800.dp, height: Dp = 600.dp): WindowState = WindowState(width, height)

@Composable
fun application(exitProcessOnExit: Boolean = true, content: @Composable ApplicationScope.() -> Unit) {
}

@Composable
fun Window(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "Untitled",
    content: @Composable FrameWindowScope.() -> Unit
) {
}
