package org.shadowice.flocke.andotp.Utilities

/**
 * Window `FLAG_SECURE` policy: what each lifecycle phase should do with screenshots and the app
 * switcher preview.
 *
 * Android has a single flag for both. While the app is in the foreground, `FLAG_SECURE` blocks
 * screenshots and screen recording. While it is in the background, the same flag also decides
 * whether the app switcher keeps a screenshot of the last screen or shows a blank card. Users
 * asked to hide the vault from the switcher, but the blank card is not what everyone wants when
 * they hand the phone to someone, so it is a separate opt-in: screenshots stay blocked by
 * default, while the switcher preview is shown by default.
 *
 * The flag is cleared only while the activity is paused, i.e. when the app is not on screen, so
 * nothing becomes capturable while the vault is visible. `Tools` applies this policy to a window;
 * this object only decides, which keeps the rules unit-testable without Android.
 */
object ScreenshotPolicy {
    /**
     * Whether the window is secured while the app is in the foreground (and can be photographed).
     * Blocking by default is the app's long-standing behaviour; only the special-feature setting
     * turns it off.
     */
    @JvmStatic
    fun secureWhileVisible(screenshotsEnabled: Boolean): Boolean = !screenshotsEnabled

    /**
     * Whether the window is secured while the app is in the background, which is the state the app
     * switcher records. Only an explicit opt-in hides the preview, and it is pointless when the
     * user already allowed screenshots.
     */
    @JvmStatic
    fun secureWhileBackgrounded(screenshotsEnabled: Boolean, hideInAppSwitcher: Boolean): Boolean =
        !screenshotsEnabled && hideInAppSwitcher
}
