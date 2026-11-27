package com.fairuniversal.offlineotp.desktop.qr

import com.fairuniversal.offlineotp.Utilities.BoundedInputReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Optional local FFmpeg camera adapter. No shell, network source, recording file or diagnostic payload. */
object DesktopQrCamera {
    private fun platform() = System.getProperty("os.name", "").lowercase()
    fun defaultDevice(): String = when {
        platform().contains("linux") -> "/dev/video0"
        platform().contains("mac") -> "0"
        else -> ""
    }

    internal fun command(os: String, device: String, executable: String = "ffmpeg"): List<String> {
        require(device.length in 1..256 && device.none { it.isISOControl() })
        val input = when {
            os.lowercase().contains("linux") -> {
                require(Regex("/dev/video[0-9]+").matches(device))
                listOf("-f", "v4l2", "-i", device)
            }
            os.lowercase().contains("mac") -> {
                require(Regex("[0-9]+").matches(device))
                listOf("-f", "avfoundation", "-i", "$device:none")
            }
            os.lowercase().contains("windows") -> {
                require(':' !in device && '"' !in device) // Do not allow a second DirectShow input.
                listOf("-f", "dshow", "-i", "video=$device")
            }
            else -> throw IllegalArgumentException("Camera transport is unavailable")
        }
        return listOf(executable, "-nostdin", "-hide_banner", "-loglevel", "quiet") + input +
            listOf("-an", "-frames:v", "1", "-vf", "scale=1280:720:force_original_aspect_ratio=decrease",
                "-f", "image2pipe", "-vcodec", "png", "pipe:1")
    }

    /** Capture one frame only after explicit consent. Bounded output and ten-second hard deadline. */
    fun capture(device: String, permitted: () -> Boolean): String? {
        if (!permitted()) return null
        val process = ProcessBuilder(command(platform(), device,
            System.getProperty("offlineotp.ffmpeg", "ffmpeg")))
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val timer = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "camera-cancellation").apply { isDaemon = true }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        timer.scheduleAtFixedRate({
            if (!permitted() || System.nanoTime() >= deadline) process.destroyForcibly()
        }, 0, 100, TimeUnit.MILLISECONDS)
        try {
            val bytes = process.inputStream.use { BoundedInputReader.read(it, BoundedInputReader.MAX_DOCUMENT_BYTES) }
            return try {
                if (!permitted() || !process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() != 0) null
                else ZxingQrDecoder().decode(bytes)
            } finally { bytes.fill(0) }
        } finally {
            process.destroyForcibly()
            timer.shutdownNow()
        }
    }
}
