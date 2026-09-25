package com.vaylith.cleanrepsmobile.api

import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticOutcome
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticStep
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticsLog
import com.vaylith.cleanrepsmobile.diagnostics.FailureKind
import com.vaylith.cleanrepsmobile.diagnostics.Redaction
import com.vaylith.cleanrepsmobile.diagnostics.StepMessages
import com.vaylith.cleanrepsmobile.model.BlockSelection
import com.vaylith.cleanrepsmobile.model.ConnectionSettings
import com.vaylith.cleanrepsmobile.model.ManualEvidenceWindow
import com.vaylith.cleanrepsmobile.model.SourceEpoch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Runs the real client against a real HTTP server on the loopback interface. */
class ChallengeApiTest {
    private class Recorded(val method: String, val path: String, val headers: Map<String, String?>, val body: ByteArray)
    private class Scripted(val status: Int, val body: ByteArray, val contentType: String?, val delayMs: Long = 0)

    private val requests = CopyOnWriteArrayList<Recorded>()
    private val scripts = ConcurrentHashMap<String, ConcurrentLinkedQueue<Scripted>>()
    private val handlers = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }

    // A minimal HTTP/1.1 responder on a real loopback socket: one request per
    // connection, answered with Content-Length and `Connection: close`.
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val acceptor = thread(isDaemon = true, name = "loopback-http") {
        while (true) {
            val client = try { server.accept() } catch (_: IOException) { break }
            handlers.execute { serve(client) }
        }
    }
    private val authority = "127.0.0.1:${server.localPort}"
    private val base = "http://$authority"
    private val settings = ConnectionSettings(
        apiBaseUrl = base,
        srtHost = "video.example.test:8890",
        srtPassphrase = "fixture-passphrase-7",
        publishPassword = "fixture-publish-pw",
    )
    private val log = DiagnosticsLog(clock = { 1_000L }, sink = { _, _, _ -> }, redaction = Redaction.forSettings(settings))

    @After fun stopServer() {
        server.close()
        acceptor.join(1_000)
        handlers.shutdownNow()
    }

    private fun serve(client: Socket) {
        try {
            client.use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readLine(input).split(' ')
                if (requestLine.size < 2) return
                val (method, target) = requestLine
                val headers = generateSequence { readLine(input).takeIf(String::isNotEmpty) }
                    .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                val body = readExactly(input, headers["content-length"]?.toInt() ?: 0)
                val path = target.substringBefore('?')
                requests += Recorded(method, path, listOf("Content-Type", "Accept", "Idempotency-Key").associateWith { headers[it.lowercase()] }, body)
                // Replies are consumed in order; the last one keeps answering.
                val queue = scripts["$method $path"]
                val reply = (if (queue != null && queue.size > 1) queue.poll() else queue?.peek())
                    ?: json(404, """{"error":"no scripted route"}""")
                if (reply.delayMs > 0) Thread.sleep(reply.delayMs)
                val head = buildString {
                    append("HTTP/1.1 ").append(reply.status).append(" Scripted\r\n")
                    reply.contentType?.let { append("Content-Type: ").append(it).append("\r\n") }
                    append("Content-Length: ").append(reply.body.size).append("\r\nConnection: close\r\n\r\n")
                }
                socket.getOutputStream().apply {
                    write(head.toByteArray(Charsets.ISO_8859_1))
                    write(reply.body)
                    flush()
                }
            }
        } catch (_: IOException) {
            // The client gave up first (timeout or refused oversized reply).
        } catch (_: InterruptedException) {
        }
    }

    private fun readLine(input: InputStream): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0 || byte == '\n'.code) break
            if (byte != '\r'.code) line.write(byte)
        }
        return line.toString(Charsets.ISO_8859_1.name())
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return body.copyOf(offset)
    }

    private fun json(status: Int, text: String) = Scripted(status, text.toByteArray(), "application/json; charset=utf-8")

    private fun respond(method: String, path: String, vararg replies: Scripted) {
        scripts["$method $path"] = ConcurrentLinkedQueue(replies.toList())
    }

    private fun api(diagnostics: DiagnosticsLog? = log, readTimeoutMs: Int = 5_000) =
        ChallengeApi(base, diagnostics, connectTimeoutMs = 2_000, readTimeoutMs = readTimeoutMs)

    private suspend fun apiFailure(block: suspend () -> Any?): ChallengeApi.ApiException {
        try {
            block()
        } catch (error: ChallengeApi.ApiException) {
            return error
        }
        throw AssertionError("expected an ApiException")
    }

    private fun closedLoopbackPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    @Test fun `existing calls send the same requests and record their steps`() = runTest {
        respond("POST", "/v1/challenge-sessions", json(201, """{"id":"session_1"}"""))
        respond("POST", "/v1/challenge-sessions/session_1/blocks", json(201, """{"id":"block_1"}"""))
        listOf("reacquired", "pause", "resume").forEach { respond("POST", "/v1/challenge-sessions/session_1/blocks/block_1/$it", json(200, "{}")) }
        respond("POST", "/v1/challenge-sessions/session_1/captures", json(201, """{"id":"capture_1"}"""))
        respond("POST", "/v1/captures/capture_1/health", json(200, "{}"))
        val backend: ChallengeBackend = api()

        assertEquals("session_1", backend.createSession("million-kicks-launch"))
        assertEquals("block_1", backend.createBlock("session_1", BlockSelection()))
        backend.markReacquired("session_1", "block_1")
        backend.pausePractice("session_1", "block_1")
        backend.resumePractice("session_1", "block_1")
        assertEquals("capture_1", backend.attachCapture("session_1", "million-kicks-camera", SourceEpoch(3)))
        backend.reportSourceHealth("capture_1", "healthy", "live")

        val create = requests.first()
        assertEquals("POST", create.method)
        assertEquals("""{"challengeId":"million-kicks-launch"}""", create.body.decodeToString())
        assertEquals("application/json", create.headers["Content-Type"])
        assertFalse(create.headers["Idempotency-Key"].isNullOrBlank())
        assertEquals(blockRequestBody(BlockSelection()), requests[1].body.decodeToString())
        assertEquals(
            """{"sourceId":"million-kicks-camera","sourceEpoch":3,"purpose":"rehearsal"}""",
            requests.single { it.path.endsWith("/captures") }.body.decodeToString(),
        )
        assertEquals("""{"status":"healthy","detail":"live"}""", requests.last().body.decodeToString())
        assertEquals(
            listOf(
                DiagnosticStep.CREATE_SESSION, DiagnosticStep.CREATE_BLOCK, DiagnosticStep.MARK_REACQUIRED,
                DiagnosticStep.PAUSE, DiagnosticStep.RESUME, DiagnosticStep.ATTACH_CAPTURE, DiagnosticStep.HEALTH,
            ),
            log.entries().map { it.step },
        )
        assertTrue(log.entries().all { it.outcome == DiagnosticOutcome.OK })
    }

    @Test fun `a 409 on attachCapture carries status code, step and kind`() = runTest {
        respond("POST", "/v1/challenge-sessions/session_1/captures", json(409, """{"error":"capture already attached"}"""))
        val error = apiFailure { api().attachCapture("session_1", "million-kicks-camera", SourceEpoch(1)) }

        assertEquals(409, error.statusCode)
        assertEquals(DiagnosticStep.ATTACH_CAPTURE, error.step)
        assertEquals(FailureKind.Http(409), error.kind)
        assertEquals("attachCapture HTTP 409: capture already attached", error.message)
        assertEquals("Server refused attach capture (409)", StepMessages.message(error.step!!, error.kind))
        val entry = log.entries().single()
        assertEquals(DiagnosticStep.ATTACH_CAPTURE, entry.step)
        assertEquals(DiagnosticOutcome.FAIL, entry.outcome)
        assertEquals("attachCapture HTTP 409: capture already attached", entry.redactedMessage)
    }

    @Test fun `timeouts and refused connections map to the timeout and unreachable kinds`() = runTest {
        respond("POST", "/v1/challenge-sessions", Scripted(201, """{"id":"late"}""".toByteArray(), "application/json", delayMs = 1_500))
        val timeout = apiFailure { api(readTimeoutMs = 200).createSession("million-kicks-launch") }
        assertEquals(FailureKind.Timeout, timeout.kind)
        assertNull(timeout.statusCode)
        assertEquals(DiagnosticStep.CREATE_SESSION, timeout.step)
        assertTrue(timeout.cause is SocketTimeoutException)
        assertEquals("createSession failed (timeout)", timeout.message)

        val unreachable = apiFailure {
            ChallengeApi("http://127.0.0.1:${closedLoopbackPort()}", log).createBlock("session_1", BlockSelection())
        }
        assertEquals(FailureKind.Unreachable, unreachable.kind)
        assertEquals(DiagnosticStep.CREATE_BLOCK, unreachable.step)
        assertTrue(unreachable.cause is ConnectException)

        assertEquals(listOf("SocketTimeoutException", "ConnectException"), log.entries().map { it.errorClass })
        assertTrue(log.entries().all { it.outcome == DiagnosticOutcome.FAIL })
    }

    @Test fun `error bodies echoing secrets are redacted before they reach the exception or the log`() = runTest {
        val echo = "rejected password: hunter2hunter2 from http://owner:s3cret@example.test via $authority " +
            "with fixture-publish-pw and fixture-passphrase-7"
        respond("POST", "/v1/challenge-sessions", json(400, """{"error":"$echo"}"""))
        respond("POST", "/v1/challenge-sessions/session_1/blocks", Scripted(502, "<html>proxy saw password=hunter2hunter2</html>".toByteArray(), "text/html"))
        // A configured secret straddling the 200-character excerpt limit: cutting before
        // redacting would leave "fixture-p", which no redaction rule recognises.
        respond("POST", "/v1/challenge-sessions/session_1/captures", json(409, """{"error":"${"x".repeat(190)} fixture-publish-pw"}"""))

        val withSettings = apiFailure { api().createSession("million-kicks-launch") }
        val htmlBody = apiFailure { api().createBlock("session_1", BlockSelection()) }
        val atTheCut = apiFailure { api().attachCapture("session_1", "million-kicks-camera", SourceEpoch(1)) }
        val patternsOnly = apiFailure { api(diagnostics = null).createSession("million-kicks-launch") }

        val texts = listOf(withSettings, htmlBody, atTheCut).map { it.message.orEmpty() } + log.entries().map { it.line() }
        texts.forEach { text ->
            listOf("hunter", "s3cret", "127.0.0.1", "fixture-p").forEach { secret ->
                assertFalse("$secret leaked: $text", text.contains(secret))
            }
        }
        assertTrue(withSettings.message!!.startsWith("createSession HTTP 400: rejected password: <redacted>"))
        assertTrue(htmlBody.message!!.contains("<redacted>"))
        // Without the settings the API's own address and every known pattern are still redacted.
        listOf("hunter", "s3cret", "127.0.0.1").forEach { assertFalse(it, patternsOnly.message!!.contains(it)) }
    }

    @Test fun `clientInfo sends exactly the given bytes and surfaces stored, unchanged and 409`() = runTest {
        val bytes = """{"appVersionName":"0.3.0-rehearsal","sentAt":"2026-09-25T12:00:00.000Z"}""".toByteArray()
        respond(
            "POST", "/v1/captures/capture_1/client-info",
            json(200, """{"captureId":"capture_1","clientInfo":"stored"}"""),
            json(200, """{"captureId":"capture_1","clientInfo":"unchanged"}"""),
            json(409, """{"error":"client_info_conflict"}"""),
        )
        respond("POST", "/v1/captures/capture_9/client-info", json(404, """{"error":"capture_not_found"}"""))
        val api = api()

        assertEquals(ClientInfoResult.STORED, api.clientInfo("capture_1", bytes))
        assertEquals(ClientInfoResult.UNCHANGED, api.clientInfo("capture_1", bytes))
        assertEquals(ClientInfoResult.CONFLICT, api.clientInfo("capture_1", bytes))
        assertEquals(3, requests.size)
        requests.forEach {
            assertEquals("POST", it.method)
            assertEquals("/v1/captures/capture_1/client-info", it.path)
            assertEquals("application/json", it.headers["Content-Type"])
            assertArrayEquals(bytes, it.body)
        }

        val missing = apiFailure { api.clientInfo("capture_9", bytes) }
        assertEquals(404, missing.statusCode)
        assertEquals(DiagnosticStep.CLIENT_INFO, missing.step)
        assertEquals("clientInfo HTTP 404: capture_not_found", missing.message)

        val invalid = apiFailure { api.clientInfo("../capture_1", bytes) }
        assertEquals(FailureKind.Other, invalid.kind)
        assertEquals(4, requests.size)

        assertEquals(
            listOf(DiagnosticOutcome.OK, DiagnosticOutcome.OK, DiagnosticOutcome.FAIL, DiagnosticOutcome.FAIL, DiagnosticOutcome.FAIL),
            log.entries().map { it.outcome },
        )
        assertTrue(log.entries().all { it.step == DiagnosticStep.CLIENT_INFO })
        assertEquals(listOf("stored", "unchanged"), log.entries().take(2).map { it.redactedMessage })
    }

    @Test fun `quality report and thumbnails map 404 to NotFound`() = runTest {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        respond("GET", "/v1/captures/capture_1/quality-report", json(200, """{"schemaVersion":1}"""))
        respond("GET", "/v1/captures/capture_2/quality-report", json(404, """{"error":"quality_report_not_found"}"""))
        respond("GET", "/v1/captures/capture_3/quality-report", json(500, """{"error":"quality_report_failed"}"""))
        respond("GET", "/v1/captures/capture_1/quality-report/thumbnails/2.jpg", Scripted(200, jpeg, "image/jpeg"))
        respond("GET", "/v1/captures/capture_2/quality-report/thumbnails/0.jpg", Scripted(404, ByteArray(0), null))
        respond("GET", "/v1/captures/capture_1/quality-report/thumbnails/0.jpg", Scripted(200, "<html>".toByteArray(), "text/html"))
        val api = api()

        assertEquals(FetchResult.Found("""{"schemaVersion":1}"""), api.qualityReport("capture_1"))
        assertEquals(FetchResult.NotFound, api.qualityReport("capture_2"))
        val serverError = apiFailure { api.qualityReport("capture_3") }
        assertEquals(500, serverError.statusCode)
        assertEquals(DiagnosticStep.QUALITY_REPORT, serverError.step)

        assertArrayEquals(jpeg, (api.thumbnail("capture_1", 2) as FetchResult.Found).value)
        assertEquals("image/jpeg", requests.last().headers["Accept"])
        assertEquals(FetchResult.NotFound, api.thumbnail("capture_2", 0))
        assertEquals(FailureKind.Other, apiFailure { api.thumbnail("capture_1", 0) }.kind)
        for (index in listOf(-1, 3)) {
            try {
                api.thumbnail("capture_1", index)
                fail("thumbnail $index was accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertTrue(requests.all { it.method == "GET" })
        assertEquals(
            listOf(DiagnosticOutcome.OK, DiagnosticOutcome.INFO, DiagnosticOutcome.FAIL, DiagnosticOutcome.OK, DiagnosticOutcome.INFO, DiagnosticOutcome.FAIL),
            log.entries().map { it.outcome },
        )
        assertTrue(log.entries().all { it.step == DiagnosticStep.QUALITY_REPORT })
    }

    @Test fun `health reports reachability and release and never throws`() = runTest {
        respond(
            "GET", "/health",
            json(200, """{"service":"clean-reps","release":"4acbcce"}"""),
            json(200, """{"service":"clean-reps"}"""),
            json(200, """{"service":"clean-reps","release":"x y/z"}"""),
            json(503, """{"error":"starting"}"""),
        )
        val api = api()
        assertEquals(ServerHealth(reachable = true, release = "4acbcce"), api.health())
        assertEquals(ServerHealth(reachable = true, release = null), api.health())
        assertEquals(ServerHealth(reachable = true, release = null), api.health())
        assertEquals(ServerHealth(reachable = false, release = null), api.health())
        assertEquals(ServerHealth(reachable = false, release = null), ChallengeApi("http://127.0.0.1:${closedLoopbackPort()}", log).health())
        assertEquals(ServerHealth(reachable = false, release = null), ChallengeApi("", log).health())

        assertTrue(requests.all { it.method == "GET" && it.path == "/health" })
        // Its own step: the capture's video health report stays HEALTH.
        assertTrue(log.entries().all { it.step == DiagnosticStep.SERVER_HEALTH })
        // Logged on change only: the third answer repeats the second (reachable, no release) and adds no entry.
        assertEquals(
            listOf(DiagnosticOutcome.OK, DiagnosticOutcome.OK, DiagnosticOutcome.FAIL, DiagnosticOutcome.FAIL, DiagnosticOutcome.FAIL),
            log.entries().map { it.outcome },
        )
    }

    @Test fun `a steady health poll never floods the diagnostics log`() = runTest {
        respond("GET", "/health", json(200, """{"service":"clean-reps","release":"4acbcce"}"""))
        val api = api()
        repeat(20) { assertEquals(ServerHealth(reachable = true, release = "4acbcce"), api.health()) }
        assertEquals(20, requests.size)
        assertEquals(listOf("server release 4acbcce"), log.entries().map { it.redactedMessage })

        // Each change is logged once: unreachable, back, and a new release.
        respond("GET", "/health", json(503, """{"error":"starting"}"""))
        repeat(5) { api.health() }
        respond("GET", "/health", json(200, """{"service":"clean-reps","release":"4acbcce"}"""))
        repeat(5) { api.health() }
        respond("GET", "/health", json(200, """{"service":"clean-reps","release":"5bdcdff"}"""))
        repeat(5) { api.health() }
        assertEquals(
            listOf(DiagnosticOutcome.OK, DiagnosticOutcome.FAIL, DiagnosticOutcome.OK, DiagnosticOutcome.OK),
            log.entries().map { it.outcome },
        )
        assertEquals("server release 5bdcdff", log.entries().last().redactedMessage)
        assertTrue(log.entries().all { it.step == DiagnosticStep.SERVER_HEALTH })
    }

    @Test fun `an unconfigured client fails with its step and never touches the network`() = runTest {
        val error = apiFailure { ChallengeApi("", log).createSession("million-kicks-launch") }
        assertEquals(DiagnosticStep.CREATE_SESSION, error.step)
        assertEquals(FailureKind.Other, error.kind)
        assertEquals("CHALLENGE_API_BASE_URL is not configured", error.message)
        assertTrue(requests.isEmpty())
    }

    @Test fun `the manual review marker keeps its request and has no diagnostics step`() = runTest {
        val path = "/v1/challenge-sessions/session_1/analysis/kick-events"
        respond("POST", path, json(201, """{"event":{"id":"event_1","sequence":4}}"""), json(500, """{"error":"ledger unavailable"}"""))
        val api = api()
        val window = ManualEvidenceWindow(startMs = 2_500, endMs = 5_000)
        assertEquals("event_1", api.logManualAttempt("session_1", "block_1", "capture_1", "million-kicks-camera", SourceEpoch(2), "2026-09-25T12:00:00Z", window))
        assertEquals("manual-attempt:capture_1:2:5000", requests.single().headers["Idempotency-Key"])

        val error = apiFailure { api.logManualAttempt("session_1", "block_1", "capture_1", "million-kicks-camera", SourceEpoch(2), "2026-09-25T12:00:01Z", window) }
        assertNull(error.step)
        assertEquals(500, error.statusCode)
        assertEquals("logManualAttempt HTTP 500: ledger unavailable", error.message)
        assertTrue(log.entries().isEmpty())
    }

    @Test fun `an oversized reply is refused`() = runTest {
        respond("GET", "/v1/captures/capture_1/quality-report", Scripted(200, ByteArray(1024 * 1024 + 1) { 'a'.code.toByte() }, "application/json"))
        val error = apiFailure { api().qualityReport("capture_1") }
        assertEquals(FailureKind.Other, error.kind)
        assertEquals("qualityReport reply exceeded 1048576 bytes", error.message)
    }
}
