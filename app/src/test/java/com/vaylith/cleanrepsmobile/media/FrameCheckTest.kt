package com.vaylith.cleanrepsmobile.media

import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticOutcome
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticStep
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticsLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Frame check's delivery rules with a string standing in for the bitmap.
 * Whether `takePhoto` draws the whole upright transmitted frame on the phone is
 * a device fact, not proved here.
 */
class FrameCheckTest {
    private val log = DiagnosticsLog(clock = { 0L }, sink = { _, _, _ -> })
    private val mainThread = mutableListOf<() -> Unit>()
    private val timeouts = mutableListOf<() -> Unit>()
    private val answers = mutableListOf<String?>()

    private fun frameCheck() = FrameCheck<String>(log, { mainThread += it }, { timeouts += it }) { answers += it }

    private fun runMainThread() {
        val due = mainThread.toList()
        mainThread.clear()
        due.forEach { it() }
    }

    private fun fireTimeouts() {
        val due = timeouts.toList()
        timeouts.clear()
        due.forEach { it() }
    }

    private fun failures() = log.entries().filter { it.outcome == DiagnosticOutcome.FAIL }

    @Test fun `a drawn frame is answered once, on the main thread`() {
        var deliver: ((String?) -> Unit)? = null
        frameCheck().start(drawing = true) { deliver = it }
        assertEquals("one timeout is armed", 1, timeouts.size)

        deliver!!("frame 720x1280")
        assertTrue("not before the main thread runs", answers.isEmpty())
        runMainThread()
        assertEquals(listOf<String?>("frame 720x1280"), answers)

        deliver!!("a second frame")
        fireTimeouts()
        runMainThread()
        assertEquals(listOf<String?>("frame 720x1280"), answers)
        assertTrue(failures().isEmpty())
    }

    @Test fun `nothing being drawn answers null at once and never asks for a frame`() {
        frameCheck().start(drawing = false) { error("no frame may be requested while nothing is drawn") }
        runMainThread()
        assertEquals(listOf<String?>(null), answers)
        assertTrue(timeouts.isEmpty())
        assertEquals("frame check: no frame is being drawn", log.entries().single().redactedMessage)
    }

    @Test fun `no frame within the timeout answers null and is logged`() {
        var deliver: ((String?) -> Unit)? = null
        frameCheck().start(drawing = true) { deliver = it }
        fireTimeouts()
        runMainThread()
        assertEquals(listOf<String?>(null), answers)
        val entry = failures().single()
        assertEquals(DiagnosticStep.PREVIEW_START, entry.step)
        assertEquals("frame check: no frame within ${FrameCheck.TIMEOUT_MS} ms", entry.redactedMessage)

        // A frame that arrives late is ignored.
        deliver!!("late frame")
        runMainThread()
        assertEquals(listOf<String?>(null), answers)
    }

    @Test fun `a failing request answers null and logs the exception class`() {
        frameCheck().start(drawing = true) { throw IllegalStateException("GL context not ready") }
        runMainThread()
        assertEquals(listOf<String?>(null), answers)
        assertTrue("no timeout after an immediate answer", timeouts.isEmpty())
        val entry = failures().single()
        assertEquals(DiagnosticStep.PREVIEW_START, entry.step)
        assertEquals("IllegalStateException", entry.errorClass)
        assertTrue(entry.redactedMessage.contains("GL context not ready"))
    }

    @Test fun `a timeout after the answer does not log a failure`() {
        var deliver: ((String?) -> Unit)? = null
        frameCheck().start(drawing = true) { deliver = it }
        deliver!!("frame")
        fireTimeouts()
        runMainThread()
        assertEquals(listOf<String?>("frame"), answers)
        assertFalse(failures().any())
    }
}
