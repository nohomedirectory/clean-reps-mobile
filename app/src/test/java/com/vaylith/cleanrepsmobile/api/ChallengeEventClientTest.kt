package com.vaylith.cleanrepsmobile.api

import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticOutcome
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticStep
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticsLog
import com.vaylith.cleanrepsmobile.diagnostics.FailureKind
import com.vaylith.cleanrepsmobile.diagnostics.StepMessages
import com.vaylith.cleanrepsmobile.model.AthleteCue
import com.vaylith.cleanrepsmobile.model.LiveAnalysisState
import com.vaylith.cleanrepsmobile.model.LiveAnalysisStatus
import com.vaylith.cleanrepsmobile.model.VerdictClass
import com.vaylith.cleanrepsmobile.model.VerdictTone
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Runs the real SSE client against a real HTTP server on the loopback interface. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChallengeEventClientTest {
    // One scripted response per connection. An event-stream response stays open
    // until the test ends, as the server's stream does.
    private val responses = LinkedBlockingQueue<String>()
    private val requestLines = LinkedBlockingQueue<String>()
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val acceptor = thread(isDaemon = true, name = "loopback-sse") {
        while (true) {
            val socket = try { server.accept() } catch (_: IOException) { break }
            sockets += socket
            thread(isDaemon = true) { serve(socket) }
        }
    }

    private val log = DiagnosticsLog(clock = { 1_000L }, sink = { _, _, _ -> })
    private val scope = CoroutineScope(SupervisorJob())

    private val verdicts = LinkedBlockingQueue<MobileVerdictEvent>()
    private val classes = LinkedBlockingQueue<Pair<VerdictClass, String>>()
    private val statuses = LinkedBlockingQueue<Optional<LiveAnalysisStatus>>()
    private val counts = LinkedBlockingQueue<SessionCounts>()
    private val totals = LinkedBlockingQueue<Long>()
    private val cues = LinkedBlockingQueue<AthleteCue>()
    private val errors = LinkedBlockingQueue<String>()

    @Before fun mainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        scope.cancel()
        server.close()
        sockets.forEach { runCatching { it.close() } }
        acceptor.join(2_000)
        Dispatchers.resetMain()
    }

    @Test fun `server-shaped stream drives the verdict class, live status and count callbacks`() {
        responses += "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n" +
            ": comment lines are ignored\n\n" +
            event(7, frame(total = 41, live = LIVE_TRACKING)) +
            // A status-only change repeats the ledger revision and the same adjudication.
            event(7, frame(total = 41, live = LIVE_STALE)) +
            event(8, frame(total = 42, live = "null"))
        val client = start("http://127.0.0.1:${server.localPort}")
        try {
            assertEquals("GET /v1/challenge-sessions/session-1/stream HTTP/1.1", requestLines.poll(5, TimeUnit.SECONDS))
            // Frames are handled in order on one thread, so the third frame's total means the first two are done.
            assertEquals(listOf(41L, 41L, 42L), List(3) { totals.poll(5, TimeUnit.SECONDS) })

            assertEquals(
                listOf(LiveAnalysisState.TRACKING, LiveAnalysisState.STALE, null),
                List(3) { statuses.poll(5, TimeUnit.SECONDS)!!.orElse(null)?.state },
            )
            assertEquals(List(3) { SessionCounts(3, 1, 2) }, List(3) { counts.poll(5, TimeUnit.SECONDS) })

            // One adjudication gives exactly one verdict and one class, however often the frame repeats.
            val verdict = verdicts.poll(5, TimeUnit.SECONDS)!!
            assertEquals(VerdictTone.NEUTRAL, verdict.tone)
            assertEquals(VerdictClass.UNJUDGEABLE, verdict.verdictClass)
            assertEquals(VerdictClass.UNJUDGEABLE to "support_foot_occluded", classes.poll(5, TimeUnit.SECONDS))
            assertTrue(verdicts.isEmpty())
            assertTrue(classes.isEmpty())

            // activeCue is null in every frame while liveAnalysis.detail is present: no cue.
            assertTrue(cues.isEmpty())
            assertTrue(errors.isEmpty())
        } finally {
            client.stop()
        }
    }

    @Test fun `an HTTP failure reaches the status line only as a step message`() {
        responses += "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        val base = "http://127.0.0.1:${server.localPort}"
        val client = start(base)
        try {
            val message = errors.poll(5, TimeUnit.SECONDS)
            assertEquals(StepMessages.message(DiagnosticStep.EVENT_STREAM, FailureKind.Http(503)), message)
            assertEquals("Server error during live updates (503)", message)
            assertFalse(message!!.contains(base))
            assertFalse(message.contains("${server.localPort}"))

            val entry = log.entries().single()
            assertEquals(DiagnosticStep.EVENT_STREAM, entry.step)
            assertEquals(DiagnosticOutcome.FAIL, entry.outcome)
            assertEquals("EventStreamHttpException", entry.errorClass)
        } finally {
            client.stop()
        }
    }

    @Test fun `an unreachable server is reported by step, never by the exception text`() {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val client = start("http://127.0.0.1:$closedPort")
        try {
            val message = errors.poll(10, TimeUnit.SECONDS)
            assertEquals("Couldn't reach Clean Reps for live updates (unreachable) — is Tailscale on?", message)
            assertFalse(message!!.contains("refused", ignoreCase = true))
            assertFalse(message.contains("127.0.0.1"))

            // The cause is kept, but only in the DiagnosticsLog.
            val entry = log.entries().single()
            assertEquals(DiagnosticStep.EVENT_STREAM, entry.step)
            assertEquals(DiagnosticOutcome.FAIL, entry.outcome)
            assertEquals("ConnectException", entry.errorClass)
        } finally {
            client.stop()
        }
    }

    @Test fun `planted secret in an exception message never reaches the status line`() {
        val secret = "MARKER-5120 connect to api.example.test/192.0.2.10:8443 with key fixture-key-7"
        for (error in listOf(IOException(secret), IllegalStateException(secret), IOException("wrapper", IOException(secret)))) {
            val message = eventStreamErrorMessage(error)
            assertEquals(StepMessages.message(DiagnosticStep.EVENT_STREAM, FailureKind.Other), message)
            for (fragment in listOf("MARKER-5120", "example.test", "192.0.2.10", "8443", "fixture-key-7", "wrapper")) {
                assertFalse(message, message.contains(fragment))
            }
        }
        val timeout = eventStreamErrorMessage(SocketTimeoutException("Read timed out talking to api.example.test"))
        assertEquals("Couldn't reach Clean Reps for live updates (timeout) — is Tailscale on?", timeout)
        assertEquals("Server refused live updates (404)", eventStreamErrorMessage(EventStreamHttpException(404)))
    }

    @Test fun `stopping the client is not logged as a stream failure`() {
        responses += "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n" + event(7, frame(total = 41, live = LIVE_TRACKING))
        val client = start("http://127.0.0.1:${server.localPort}")
        assertEquals(41L, totals.poll(5, TimeUnit.SECONDS))
        client.stop()
        // The read ends with a socket error once stop() disconnects; give it time to surface.
        assertNull(errors.poll(500, TimeUnit.MILLISECONDS))
        assertTrue(log.entries().isEmpty())
    }

    private fun start(base: String) = ChallengeEventClient(base, log).also { client ->
        client.start(
            scope,
            "session-1",
            onVerdict = { verdicts += it },
            onCue = { cues += it },
            onCueSafe = { cues += it },
            onError = { errors += it },
            onChallengeTotal = { totals += it },
            onLiveAnalysis = { statuses += Optional.ofNullable(it) },
            onSessionCounts = { counts += it },
            onVerdictClass = { verdictClass, reason -> classes += verdictClass to reason },
        )
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream().bufferedReader()
        input.readLine()?.let { requestLines += it } ?: return
        while (true) {
            val line = input.readLine() ?: return
            if (line.isEmpty()) break
        }
        val response = responses.poll(5, TimeUnit.SECONDS) ?: return
        socket.getOutputStream().apply { write(response.toByteArray(Charsets.UTF_8)); flush() }
        if (!response.contains("text/event-stream")) socket.close()
    }

    private fun event(revision: Int, data: String) = "id: $revision\nevent: challenge-state\ndata: $data\n\n"

    /** OverlayState in server key order plus the top-level `liveAnalysis`; synthetic values. */
    private fun frame(total: Long, live: String) =
        """{"sessionId":"session-1","challengeId":"challenge-1","challengeOfficialAcceptedCount":$total,"officialAcceptedCount":0,""" +
            """"attempts":6,"accepted":3,"rejected":1,"pendingReview":0,"evidenceFailed":2,""" +
            """"currentBlock":{"id":"block-1","sessionId":"session-1","sequence":1,"technique":"teep","side":"right","targetContext":"hanging_bag",""" +
            """"intent":"challenge_counting","cameraProfile":"fixed_full_body_oblique_v1","startedAt":"2030-01-01T00:00:00.000Z","reacquisition":"ready","practiceState":"active"},""" +
            """"sourceHealth":{"captureSessionId":"capture-1","status":"healthy","detail":"phone preview ok","observedAt":"2030-01-01T00:00:03.000Z"},""" +
            """"activeCue":null,"latestVerdict":{"kickEventId":"kick-6","kickSequence":6,"adjudicationId":"adj-6","adjudicationSequence":9,""" +
            """"state":"evidence_failed","reasonCode":"support_foot_occluded","occurredAt":"2030-01-01T00:00:04.000Z","adjudicatedAt":"2030-01-01T00:00:05.000Z"},""" +
            """"latestSettledKickEventId":"kick-6","analysisLag":"ready","liveAnalysis":$live}"""

    private companion object {
        const val LIVE_TRACKING = """{"state":"tracking","reasonCode":null,"detail":"upright 9/12 body 12/12","practicePaused":true,""" +
            """"sourceGeometry":{"width":1280,"height":720,"orientation":"landscape"},"athleteRegion":{"left":0.2,"top":0.05,"right":0.8,"bottom":0.95},"stale":false}"""
        const val LIVE_STALE = """{"state":"stale","reasonCode":null,"detail":"","practicePaused":true,""" +
            """"sourceGeometry":{"width":1280,"height":720,"orientation":"landscape"},"athleteRegion":{"left":0.2,"top":0.05,"right":0.8,"bottom":0.95},"stale":true}"""
    }
}
