package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.InputPolicy
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopQrImageReadTest {
    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = tasks.removeAt(0).run()
    }
    @Test fun workerResultRequiresUiDeliveryAndExplicitOneTimeConsumption() {
        val worker = Queue(); val ui = Queue(); var decodes = 0
        val reader = DesktopQrImageRead(worker, ui::execute, {}, { decodes++; "secret-uri" })
        assertTrue(reader.submit(File("unused")) { true })
        assertTrue(reader.busy)
        assertFalse(reader.submit(File("duplicate")) { true })
        assertEquals(0, decodes)
        worker.run(); assertFalse(reader.ready)
        ui.run(); assertTrue(reader.ready); assertFalse(reader.busy)
        assertEquals("secret-uri", reader.take()); assertTrue(reader.take() == null)
        reader.close()
    }
    @Test fun cancellationBeforeWorkAndBeforeDeliveryDropsResults() {
        for (beforeWork in listOf(false, true)) {
            val worker = Queue(); val ui = Queue(); var decodes = 0
            val reader = DesktopQrImageRead(worker, ui::execute, {}, { decodes++; "secret" })
            reader.submit(File("unused")) { true }
            if (beforeWork) reader.clear()
            worker.run()
            if (!beforeWork) { reader.clear(); ui.run() }
            assertEquals(if (beforeWork) 0 else 1, decodes)
            assertFalse(reader.ready); assertFalse(reader.busy); assertTrue(reader.take() == null)
            reader.close()
        }
    }
    @Test fun closeDuringDecodeAndExpiredDeliveryNeverExposePayload() {
        for (duringDecode in listOf(false, true)) {
            val worker = Queue(); val ui = Queue(); var active = true
            lateinit var reader: DesktopQrImageRead
            reader = DesktopQrImageRead(worker, ui::execute, {}, {
                if (duringDecode) reader.close()
                "secret"
            })
            reader.submit(File("unused")) { active }
            worker.run(); active = false
            if (!duringDecode) ui.run()
            assertFalse(reader.ready); assertTrue(reader.take() == null)
            reader.close()
            assertFalse(reader.submit(File("unused")) { true })
        }
    }
    @Test fun staleDeliveryCannotOverwriteNewSelection() {
        val worker = Queue(); val ui = Queue()
        val reader = DesktopQrImageRead(worker, ui::execute, {}, { it.name })
        reader.submit(File("old")) { true }; worker.run(); reader.clear()
        reader.submit(File("new")) { true }; worker.run()
        ui.run(); assertFalse(reader.ready)
        ui.run(); assertEquals("new", reader.take())
        reader.close()
    }
    @Test fun invalidNullOversizedAndThrowingDecodersReportOnlyFailure() {
        for (result in listOf(null, "", " ", "x".repeat(InputPolicy.MAX_URI_CHARS + 1))) {
            val worker = Queue(); val ui = Queue()
            val reader = DesktopQrImageRead(worker, ui::execute, {}, { result })
            reader.submit(File("unused")) { true }; worker.run(); ui.run()
            assertTrue(reader.failed); assertFalse(reader.ready); assertFalse(reader.busy)
            reader.close()
        }
        val worker = Queue(); val ui = Queue()
        val reader = DesktopQrImageRead(worker, ui::execute, {}, { throw IllegalArgumentException("private") })
        reader.submit(File("unused")) { true }; worker.run(); ui.run()
        assertTrue(reader.failed); assertTrue(reader.take() == null); reader.close()
    }
    @Test fun rejectedWorkerAndDispatcherCannotPublishSecrets() {
        val reader = DesktopQrImageRead(Executor { throw RejectedExecutionException() }, {}, {}, { "secret" })
        reader.submit(File("unused")) { true }
        assertTrue(reader.failed); assertFalse(reader.busy); assertFalse(reader.ready); reader.close()
        val worker = Queue()
        val rejected = DesktopQrImageRead(worker, { throw RejectedExecutionException() }, {}, { "secret" })
        rejected.submit(File("unused")) { true }; worker.run()
        assertFalse(rejected.ready); assertTrue(rejected.take() == null); rejected.close()
    }
    @Test fun realReaderRejectsNonImageWithoutDependingOnZxing() {
        val file = Files.createTempFile("qr-invalid-", ".txt").toFile()
        val worker = Queue(); val ui = Queue()
        val reader = DesktopQrImageRead(worker, ui::execute, {})
        try {
            file.writeText("not an image")
            reader.submit(file) { true }; worker.run(); ui.run()
            assertTrue(reader.failed); assertFalse(reader.ready)
        } finally { reader.close(); file.delete() }
    }
}
