package androidx.compose.ui.test

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule

class SemanticsNodeInteraction {
    fun assertExists(): SemanticsNodeInteraction = error("device-only compile stub")
    fun assertDoesNotExist(): Unit = error("device-only compile stub")
}
fun <A : ComponentActivity> AndroidComposeTestRule<A>.onNodeWithText(
    text: String, substring: Boolean = false, ignoreCase: Boolean = false, useUnmergedTree: Boolean = false
): SemanticsNodeInteraction = error("device-only compile stub")
fun SemanticsNodeInteraction.performClick(): SemanticsNodeInteraction = error("device-only compile stub")
