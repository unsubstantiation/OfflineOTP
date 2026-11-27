package com.fairuniversal.offlineotp.desktop.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class DesktopQrCameraTest {
    @Test fun cameraCommandsAcceptOnlyLocalPlatformInputsAndNeverInvokeShell() {
        val linux = DesktopQrCamera.command("Linux", "/dev/video0")
        assertEquals("ffmpeg", linux.first())
        assertTrue(linux.contains("v4l2"))
        assertTrue(linux.contains("pipe:1"))
        assertTrue(linux.contains("1"))
        assertTrue(DesktopQrCamera.command("Mac OS X", "0").contains("0:none"))
        val windows = DesktopQrCamera.command("Windows 11", "Camera & other name")
        assertTrue(windows.contains("video=Camera & other name"))
        assertFalse(windows.contains("cmd")); assertFalse(windows.contains("sh"))
        for (device in listOf("https://example.org/image", "file:///tmp/image", "/dev/video0\n", "-i", "")) {
            try { DesktopQrCamera.command("Linux", device); fail("invalid camera accepted") }
            catch (_: IllegalArgumentException) { }
        }
        try { DesktopQrCamera.command("Windows", "Camera:audio=Microphone"); fail("audio input accepted") }
        catch (_: IllegalArgumentException) { }
        try { DesktopQrCamera.command("Unknown", "0"); fail("unsupported platform accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun revokedCaptureDoesNotLaunchAProcess() {
        assertNull(DesktopQrCamera.capture("this would fail validation") { false })
    }
}
