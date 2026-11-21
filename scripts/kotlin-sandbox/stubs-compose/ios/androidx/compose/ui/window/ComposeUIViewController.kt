// Compile-only Compose Multiplatform UIKit window stub for type-checking ui-foundation/src/iosMain.
package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import platform.UIKit.UIViewController

fun ComposeUIViewController(content: @Composable () -> Unit): UIViewController = UIViewController()
