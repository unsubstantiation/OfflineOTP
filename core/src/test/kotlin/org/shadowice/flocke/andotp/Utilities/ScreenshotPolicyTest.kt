package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotPolicyTest {
    @Test
    fun screenshotsStayBlockedWhileTheAppIsVisibleByDefault() {
        assertTrue(ScreenshotPolicy.secureWhileVisible(screenshotsEnabled = false))
        assertFalse(ScreenshotPolicy.secureWhileVisible(screenshotsEnabled = true))
    }

    @Test
    fun theAppSwitcherPreviewIsShownUnlessTheUserHidesIt() {
        // Default: screenshots blocked, preview visible.
        assertFalse(ScreenshotPolicy.secureWhileBackgrounded(
            screenshotsEnabled = false, hideInAppSwitcher = false))
        // Opt-in: the preview is blanked as well.
        assertTrue(ScreenshotPolicy.secureWhileBackgrounded(
            screenshotsEnabled = false, hideInAppSwitcher = true))
    }

    @Test
    fun allowingScreenshotsAlsoShowsTheSwitcherPreview() {
        assertFalse(ScreenshotPolicy.secureWhileBackgrounded(
            screenshotsEnabled = true, hideInAppSwitcher = false))
        assertFalse(ScreenshotPolicy.secureWhileBackgrounded(
            screenshotsEnabled = true, hideInAppSwitcher = true))
    }

    @Test
    fun hidingThePreviewNeverWeakensTheVisibleWindow() {
        // Whatever the switcher does, a visible window is still screenshot-blocked by default.
        for (hide in listOf(false, true)) {
            assertTrue(ScreenshotPolicy.secureWhileVisible(screenshotsEnabled = false))
            assertTrue(ScreenshotPolicy.secureWhileBackgrounded(false, hide) == hide)
        }
    }
}
