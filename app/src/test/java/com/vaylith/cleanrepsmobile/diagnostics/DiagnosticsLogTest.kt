package com.vaylith.cleanrepsmobile.diagnostics

import com.vaylith.cleanrepsmobile.model.ConnectionSettings
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLogTest {
    private class FakeSink : LogSink {
        val writes = mutableListOf<Triple<LogPriority, String, String>>()
        override fun write(priority: LogPriority, tag: String, line: String) {
            writes += Triple(priority, tag, line)
        }
    }

    private val settings = ConnectionSettings(
        apiBaseUrl = "http://192.0.2.20:8080",
        srtHost = "video.example.test:8890",
        srtPassphrase = "Zq7!long-passphrase",
        publishPassword = "publisher-secret",
    )
    private var now = 1_790_318_645_205L
    private val sink = FakeSink()
    private val log = DiagnosticsLog(clock = { now }, sink = sink, redaction = Redaction.forSettings(settings))

    @Test fun `ring is bounded at 100 and the 101st entry evicts the oldest`() {
        repeat(101) { index ->
            log.info(DiagnosticStep.HEALTH, "event $index")
            now += 10
        }
        val entries = log.entries()
        assertEquals(100, DiagnosticsLog.CAPACITY)
        assertEquals(100, entries.size)
        assertEquals((1..100).map { "event $it" }, entries.map { it.redactedMessage })
        assertEquals(101, sink.writes.size)
        assertEquals("event 0", sink.writes.first().third.substringAfter(": "))
    }

    @Test fun `entries keep insertion order, injected timestamps and outcome`() {
        log.ok(DiagnosticStep.CREATE_SESSION)
        now += 250
        log.fail(DiagnosticStep.ATTACH_CAPTURE, "attach failed", SocketTimeoutException("read timed out"))
        now += 250
        log.info(DiagnosticStep.EVENT_STREAM, "connected")

        val entries = log.entries()
        assertEquals(
            listOf(DiagnosticStep.CREATE_SESSION, DiagnosticStep.ATTACH_CAPTURE, DiagnosticStep.EVENT_STREAM),
            entries.map { it.step },
        )
        assertEquals(listOf(1_790_318_645_205L, 1_790_318_645_455L, 1_790_318_645_705L), entries.map { it.timestampMs })
        assertEquals(
            listOf(DiagnosticOutcome.OK, DiagnosticOutcome.FAIL, DiagnosticOutcome.INFO),
            entries.map { it.outcome },
        )
        assertNull(entries[0].errorClass)
        assertEquals("SocketTimeoutException", entries[1].errorClass)
        assertEquals("attach failed: read timed out", entries[1].redactedMessage)
        assertEquals(
            "2026-09-25T06:44:05.455Z FAIL attachCapture SocketTimeoutException: attach failed: read timed out",
            entries[1].line(),
        )
        assertEquals("2026-09-25T06:44:05.205Z OK createSession", entries[0].line())
    }

    @Test fun `every entry is mirrored under the CleanReps tag with warn only for failures`() {
        log.ok(DiagnosticStep.PUBLISHER_START, "started")
        log.fail(DiagnosticStep.SRT_CONNECT, "disconnected")
        log.info(DiagnosticStep.PAUSE, "paused")

        assertEquals("CleanReps", DiagnosticsLog.TAG)
        assertTrue(sink.writes.all { it.second == "CleanReps" })
        assertEquals(listOf(LogPriority.INFO, LogPriority.WARN, LogPriority.INFO), sink.writes.map { it.first })
        assertEquals(log.entries().map { it.line() }, sink.writes.map { it.third })
    }

    @Test fun `planted secrets never reach storage, the sink or the export`() {
        val secrets = listOf(
            "abcdefghij",
            "Zq7!long-passphrase",
            "publisher-secret",
            "hunter2x",
            "owner:s3cret",
            "192.0.2.20",
            "video.example.test",
            "srt://192.0.2.10",
        )
        // Exception message with an SRT URL and passphrase.
        log.fail(
            DiagnosticStep.SRT_CONNECT,
            "SRT failed",
            IOException(
                "connect srt://192.0.2.10:8890?mode=caller&streamid=#!::m=publish,r=million-kicks-camera,u=publisher,s=hunter2x&passphrase=abcdefghij",
            ),
        )
        // API error body echoing the configured literals.
        log.fail(
            DiagnosticStep.ATTACH_CAPTURE,
            """409 {"error":"conflict","password":"hunter2x","echo":"publisher-secret Zq7!long-passphrase"}""",
        )
        // URL userinfo, a settings host inside a cause, and the API base URL in the message.
        log.fail(
            DiagnosticStep.CREATE_SESSION,
            "POST http://owner:s3cret@example.test/v1/challenge-sessions via http://192.0.2.20:8080",
            IOException("request failed", SocketTimeoutException("failed to connect to /192.0.2.20 (port 8080)")),
        )
        // The configured SRT host and passphrase as bare text.
        log.info(DiagnosticStep.PUBLISHER_START, "host video.example.test:8890 passphrase Zq7!long-passphrase")

        val stored = log.entries().joinToString("\n") { it.redactedMessage }
        val mirrored = sink.writes.joinToString("\n") { it.third }
        val exported = log.exportText()
        for (secret in secrets) {
            assertFalse("storage leaked $secret: $stored", stored.contains(secret))
            assertFalse("sink leaked $secret: $mirrored", mirrored.contains(secret))
            assertFalse("export leaked $secret: $exported", exported.contains(secret))
        }
        // The cause chain survives redaction so the failing step stays diagnosable.
        assertTrue(stored.contains("request failed | caused by SocketTimeoutException: failed to connect to /<redacted> (port 8080)"))
    }

    @Test fun `redaction happens before truncation so a secret on the boundary cannot escape`() {
        log.info(DiagnosticStep.HEALTH, "x".repeat(495) + "publisher-secret")
        val stored = log.entries().single().redactedMessage
        assertEquals(DiagnosticsLog.MAX_MESSAGE_LENGTH, stored.length)
        assertFalse(stored.contains("publi"))
    }

    @Test fun `control characters cannot forge extra log lines`() {
        log.fail(DiagnosticStep.HEALTH, "bad\n2026-09-25T06:44:05.205Z OK createSession\r\tforged")
        val exportLines = log.exportText().trimEnd('\n').lines()
        assertEquals(2, exportLines.size)
        assertEquals("bad 2026-09-25T06:44:05.205Z OK createSession forged", log.entries().single().redactedMessage)
        assertFalse(sink.writes.single().third.contains('\n'))
    }

    @Test fun `export is redacted text with a header and one line per entry`() {
        log.ok(DiagnosticStep.CAMERA_OPEN, "camera ready")
        log.fail(DiagnosticStep.PREVIEW_START, "surface lost")
        assertEquals(
            "Clean Reps diagnostics: 2 events, oldest first, redacted\n" +
                "2026-09-25T06:44:05.205Z OK cameraOpen: camera ready\n" +
                "2026-09-25T06:44:05.205Z FAIL previewStart: surface lost\n",
            log.exportText(),
        )
    }

    @Test fun `export applies the current settings to entries stored before they were known`() {
        val early = DiagnosticsLog(clock = { now }, sink = FakeSink())
        early.info(DiagnosticStep.HEALTH, "typed later-secret-value before saving")
        early.redaction = Redaction.forSettings(settings.copy(publishPassword = "later-secret-value"))
        assertFalse(early.exportText().contains("later-secret-value"))
        early.info(DiagnosticStep.HEALTH, "later-secret-value again")
        assertEquals("<redacted> again", early.entries().last().redactedMessage)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `capacity must be positive`() {
        DiagnosticsLog(clock = { now }, sink = FakeSink(), capacity = 0)
    }
}
