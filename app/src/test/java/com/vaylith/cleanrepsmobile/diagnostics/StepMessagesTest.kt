package com.vaylith.cleanrepsmobile.diagnostics

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepMessagesTest {
    private val kinds = listOf(
        FailureKind.Timeout,
        FailureKind.Unreachable,
        FailureKind.Http(409),
        FailureKind.Http(503),
        FailureKind.Http(302),
        FailureKind.Camera,
        FailureKind.Other,
    )

    @Test fun `every step and failure kind yields its own message`() {
        val messages = DiagnosticStep.entries.flatMap { step -> kinds.map { kind -> StepMessages.message(step, kind) } }
        assertEquals(15, DiagnosticStep.entries.size)
        assertEquals(DiagnosticStep.entries.size * kinds.size, messages.toSet().size)
        messages.forEach { assertTrue("blank message", it.isNotBlank()) }
    }

    @Test fun `the step vocabulary uses the agreed wire names`() {
        assertEquals(
            listOf(
                "createSession", "attachCapture", "clientInfo", "publisherStart", "srtConnect", "cameraOpen",
                "previewStart", "createBlock", "markReacquired", "pause", "resume", "health", "serverHealth", "eventStream",
                "qualityReport",
            ),
            DiagnosticStep.entries.map { it.wireName },
        )
    }

    @Test fun `the server check and the capture's video health report are told apart`() {
        assertEquals("Couldn't reach Clean Reps for server check (timeout) — is Tailscale on?",
            StepMessages.message(DiagnosticStep.SERVER_HEALTH, FailureKind.Timeout))
        assertEquals("Couldn't reach Clean Reps for video health report (timeout) — is Tailscale on?",
            StepMessages.message(DiagnosticStep.HEALTH, FailureKind.Timeout))
        assertEquals("Server error during server check (503)", StepMessages.message(DiagnosticStep.SERVER_HEALTH, FailureKind.Http(503)))
    }

    @Test fun `messages name the failure kind and the HTTP code`() {
        for (step in DiagnosticStep.entries) {
            assertTrue(StepMessages.message(step, FailureKind.Timeout).contains("(timeout)"))
            assertTrue(StepMessages.message(step, FailureKind.Unreachable).contains("(unreachable)"))
            assertTrue(StepMessages.message(step, FailureKind.Http(409)).endsWith("(409)"))
            assertTrue(StepMessages.message(step, FailureKind.Http(503)).endsWith("(503)"))
        }
    }

    @Test fun `owner messages read as the plan describes`() {
        assertEquals("Server refused attach capture (409)", StepMessages.message(DiagnosticStep.ATTACH_CAPTURE, FailureKind.Http(409)))
        assertEquals(
            "Couldn't reach Clean Reps for create session (timeout) — is Tailscale on?",
            StepMessages.message(DiagnosticStep.CREATE_SESSION, FailureKind.Timeout),
        )
        assertEquals(
            "Couldn't reach the video server for video connection (unreachable) — is Tailscale on?",
            StepMessages.message(DiagnosticStep.SRT_CONNECT, FailureKind.Unreachable),
        )
        assertEquals(
            "Server error during start practice (503)",
            StepMessages.message(DiagnosticStep.CREATE_BLOCK, FailureKind.Http(503)),
        )
        assertEquals(
            "Camera problem during camera open — close other camera apps and try again",
            StepMessages.message(DiagnosticStep.CAMERA_OPEN, FailureKind.Camera),
        )
    }

    @Test fun `no message contains raw exception text`() {
        val raw = "MARKER-7731 passphrase=abcdefghij srt://192.0.2.10:8890?s=pw http://owner:s3cret@example.test"
        val errors = listOf(
            IllegalStateException(raw),
            SocketTimeoutException(raw),
            IOException(raw, UnknownHostException(raw)),
        )
        for (step in DiagnosticStep.entries) {
            for (error in errors) {
                val message = StepMessages.forError(step, error)
                listOf("MARKER-7731", "abcdefghij", "srt://", "192.0.2.10", "s3cret", "passphrase=").forEach {
                    assertFalse("'$it' in: $message", message.contains(it))
                }
            }
        }
    }

    @Test fun `network failures are classified from exception types, including causes`() {
        assertEquals(FailureKind.Timeout, FailureKind.of(SocketTimeoutException("x")))
        assertEquals(FailureKind.Unreachable, FailureKind.of(UnknownHostException("x")))
        assertEquals(FailureKind.Unreachable, FailureKind.of(ConnectException("x")))
        assertEquals(FailureKind.Unreachable, FailureKind.of(NoRouteToHostException("x")))
        assertEquals(FailureKind.Unreachable, FailureKind.of(PortUnreachableException("x")))
        assertEquals(FailureKind.Timeout, FailureKind.of(IOException("wrapped", SocketTimeoutException("x"))))
        assertEquals(FailureKind.Other, FailureKind.of(IllegalStateException("timeout unreachable")))
        assertEquals(
            "Couldn't reach Clean Reps for attach capture (timeout) — is Tailscale on?",
            StepMessages.forError(DiagnosticStep.ATTACH_CAPTURE, IOException("wrapped", SocketTimeoutException("x"))),
        )
    }

    @Test fun `a cyclic cause chain terminates`() {
        val first = IOException("first")
        val second = IOException("second")
        first.initCause(second)
        second.initCause(first)
        assertEquals(FailureKind.Other, FailureKind.of(first))
    }
}
