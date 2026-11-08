package androidx.compose.ui.test.junit4

import androidx.activity.ComponentActivity

// Compile-only subset of the device test API. Every operation fails rather than fake a UI pass.
class AndroidComposeTestRule<A : ComponentActivity> {
    val activity: A get() = error("device-only compile stub")
    fun <T> runOnIdle(action: () -> T): T = error("device-only compile stub")
    fun waitForIdle(): Unit = error("device-only compile stub")
}
inline fun <reified A : ComponentActivity> createAndroidComposeRule(): AndroidComposeTestRule<A> =
    error("device-only compile stub")
