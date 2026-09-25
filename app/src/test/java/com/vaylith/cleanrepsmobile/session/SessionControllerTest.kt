package com.vaylith.cleanrepsmobile.session

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.view.SurfaceView
import com.vaylith.cleanrepsmobile.api.ChallengeApi
import com.vaylith.cleanrepsmobile.api.ChallengeBackend
import com.vaylith.cleanrepsmobile.api.ChallengeEvents
import com.vaylith.cleanrepsmobile.api.ClientInfoResult
import com.vaylith.cleanrepsmobile.api.FetchResult
import com.vaylith.cleanrepsmobile.api.MobileVerdictEvent
import com.vaylith.cleanrepsmobile.api.ServerHealth
import com.vaylith.cleanrepsmobile.api.SessionCounts
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticOutcome
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticStep
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticsLog
import com.vaylith.cleanrepsmobile.diagnostics.FailureKind
import com.vaylith.cleanrepsmobile.feedback.AthleteSignals
import com.vaylith.cleanrepsmobile.feedback.FeedbackCue
import com.vaylith.cleanrepsmobile.media.CanonicalSourcePublisher
import com.vaylith.cleanrepsmobile.media.CaptureGeometry
import com.vaylith.cleanrepsmobile.media.PreviewStatus
import com.vaylith.cleanrepsmobile.media.PublisherListener
import com.vaylith.cleanrepsmobile.media.PublisherResult
import com.vaylith.cleanrepsmobile.media.PublisherStatus
import com.vaylith.cleanrepsmobile.model.AthleteCue
import com.vaylith.cleanrepsmobile.model.BlockSelection
import com.vaylith.cleanrepsmobile.model.CaptureReadiness
import com.vaylith.cleanrepsmobile.model.ClientIdentity
import com.vaylith.cleanrepsmobile.model.KickSide
import com.vaylith.cleanrepsmobile.model.LiveAnalysisStatus
import com.vaylith.cleanrepsmobile.model.ManualEvidenceWindow
import com.vaylith.cleanrepsmobile.model.SourceEpoch
import com.vaylith.cleanrepsmobile.model.VerdictClass
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controller over fakes (L0/L1). The UI scope runs eagerly, like
 * `Dispatchers.Main.immediate` on the main thread; the app scope runs only when
 * the shared virtual clock is advanced, like `Dispatchers.IO`. Process death,
 * Home/Power behaviour and the real phone's orphan avoidance are device facts
 * that these tests cannot show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionControllerTest {
    private val scheduler = TestCoroutineScheduler()
    private val uiScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
    private val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val log = DiagnosticsLog(clock = { 1_000L }, sink = { _, _, _ -> })
    private val preferences = FakePreferences()
    private val store = SharedPreferencesPendingLostStore(preferences)
    private val backend = FakeBackend()
    private val events = FakeEvents()
    private val signals = RecordingSignals()
    private var visible = true
    private var wallClockReads = 0L
    private lateinit var publisher: FakePublisher

    @After fun tearDown() {
        uiScope.cancel()
        appScope.cancel()
    }

    private fun controller(initial: AppState = AppState()) = SessionController(
        backend = backend,
        events = events,
        newPublisher = { listener -> FakePublisher(listener).also { publisher = it } },
        signals = signals,
        diagnostics = log,
        pendingLost = store,
        appScope = appScope,
        uiScope = uiScope,
        elapsedRealtime = { 50_000L },
        // Every read gives a new instant, so a body built twice would differ.
        wallClock = { START.plusSeconds(wallClockReads++) },
        isScreenVisible = { visible },
        build = BUILD,
        sourceId = SOURCE,
        initial = initial,
    ).also { it.start() }

    /** Session created, capture attached, video LIVE, everything settled. */
    private fun live(): SessionController = controller().also { controller ->
        controller.startVideo()
        settle()
        publisher.live()
        settle()
        assertEquals(CaptureReadiness.LIVE, controller.state.value.readiness)
        assertEquals("capture-1", controller.state.value.captureId)
    }

    private fun settle() = scheduler.advanceUntilIdle()

    private fun lostCalls() = backend.health.filter { it.status == "lost" }

    @Test fun `lost survives cancellation of the UI scope and is persisted before it is sent`() {
        val controller = live()
        backend.failNext("lost", apiFailure(DiagnosticStep.HEALTH, FailureKind.Unreachable))

        controller.onLeftScreen()
        // Persisted synchronously at ON_STOP, before any attempt runs.
        assertEquals(mapOf("capture-1" to HealthDetail.LEFT_SCREEN), store.pending())
        assertTrue(lostCalls().isEmpty())
        // The Activity is destroyed: its scope (and the controller's) is cancelled.
        uiScope.cancel()
        settle()

        assertEquals(2, lostCalls().size)
        lostCalls().forEach { call ->
            assertEquals(HealthCall("capture-1", "lost", "left the screen", setOf("capture-1")), call)
        }
        assertTrue(store.pending().isEmpty())
        assertEquals(listOf("stop"), publisher.calls.filter { it == "stop" })
        assertEquals(SessionController.LEFT_SCREEN_BANNER, controller.state.value.banner)
        assertEquals("Video stopped because Clean Reps left the screen. Tap Go live.", controller.state.value.banner)
    }

    @Test fun `Stop video persists lost, sends it with three attempts and backoff, and keeps it pending after the third`() {
        val controller = live()
        repeat(3) { backend.failNext("lost", apiFailure(DiagnosticStep.HEALTH, FailureKind.Timeout)) }

        controller.stopVideo()
        assertEquals(mapOf("capture-1" to HealthDetail.STOPPED), store.pending())
        uiScope.cancel()

        scheduler.advanceTimeBy(1)
        assertEquals(1, lostCalls().size)
        scheduler.advanceTimeBy(1_000)
        assertEquals(2, lostCalls().size)
        scheduler.advanceTimeBy(4_000)
        assertEquals(3, lostCalls().size)
        settle()
        assertEquals(3, lostCalls().size)
        assertEquals(mapOf("capture-1" to HealthDetail.STOPPED), store.pending())
        assertTrue(log.entries().any { it.step == DiagnosticStep.HEALTH && "still pending after 3 attempts" in it.redactedMessage })
        assertNull(controller.state.value.captureId)
    }

    @Test fun `pending lost reports are flushed on app start`() {
        store.add("capture-old", HealthDetail.LEFT_SCREEN)
        controller()
        settle()
        assertEquals(listOf(HealthCall("capture-old", "lost", "left the screen", setOf("capture-old"))), backend.health)
        assertTrue(store.pending().isEmpty())
    }

    @Test fun `a pending lost is flushed before a new capture is attached`() {
        store.add("capture-old", HealthDetail.STOPPED)
        // The start-up flush fails once; Start video joins that delivery, which then succeeds.
        backend.failNext("lost", apiFailure(DiagnosticStep.HEALTH, FailureKind.Unreachable))
        val controller = controller()
        controller.startVideo()
        assertTrue("attachCapture" !in backend.calls)
        settle()

        val lastLost = backend.calls.lastIndexOf("lost")
        val attach = backend.calls.indexOf("attachCapture")
        assertTrue("${backend.calls}", lastLost in 0 until attach)
        assertEquals(1, backend.calls.count { it == "attachCapture" })
        assertEquals(2, lostCalls().count { it.captureId == "capture-old" })
        assertTrue(store.pending().isEmpty())
        assertEquals("capture-1", controller.state.value.captureId)
    }

    @Test fun `no second capture is attached while a lost is still pending`() {
        store.add("capture-old", HealthDetail.STOPPED)
        // Every attempt of the first flush fails; the server answers again afterwards.
        repeat(SessionController.LOST_ATTEMPTS) { backend.failNext("lost", apiFailure(DiagnosticStep.HEALTH, FailureKind.Unreachable)) }
        val controller = controller()
        controller.startVideo()
        settle()

        assertTrue("attachCapture" !in backend.calls)
        assertTrue(publisher.calls.none { it.startsWith("start") })
        assertNull(controller.state.value.captureId)
        assertFalse(controller.state.value.requestInFlight)
        assertEquals(setOf("capture-old"), store.pending().keys)
        val message = controller.state.value.statusDetail
        assertTrue(message, message.startsWith("Couldn't reach Clean Reps for video health report (unreachable)"))
        assertFalse(message, "MARKER" in message)

        // The server is back: the next tap reports the old capture first, then attaches.
        controller.startVideo()
        settle()
        assertTrue(backend.calls.lastIndexOf("lost") < backend.calls.indexOf("attachCapture"))
        assertEquals("capture-1", controller.state.value.captureId)
        assertTrue(store.pending().isEmpty())
    }

    @Test fun `a lost the server refuses is dropped, so it never blocks the next capture`() {
        store.add("capture-unknown", HealthDetail.STOPPED)
        backend.failNext("lost", apiFailure(DiagnosticStep.HEALTH, FailureKind.Http(409)))
        val controller = controller()
        controller.startVideo()
        settle()
        assertEquals(1, lostCalls().size)
        assertTrue(store.pending().isEmpty())
        assertEquals("capture-1", controller.state.value.captureId)
        assertTrue(log.entries().any { it.outcome == DiagnosticOutcome.FAIL && "refused; no longer pending" in it.redactedMessage })
    }

    @Test fun `P-SEP - preview statuses while LIVE never change readiness, send health, clear the capture or pause practice`() {
        val controller = live()
        controller.startPractice()
        settle()
        assertTrue(controller.state.value.practiceActive)
        val healthBefore = backend.health.toList()
        val callsBefore = backend.calls.toList()
        val geometry = CaptureGeometry.forDisplayRotation(1, 90).getOrThrow()
        val sequence = listOf(
            PreviewStatus.Ready,
            PreviewStatus.Lost,
            PreviewStatus.Starting,
            PreviewStatus.CameraError("Camera problem during camera open $MARKER"),
            PreviewStatus.RotationPending(geometry),
            PreviewStatus.Lost,
            PreviewStatus.Ready,
        )
        for (status in sequence) {
            publisher.listener.onPreviewStatus(status, "preview text $MARKER")
            settle()
            val state = controller.state.value
            assertEquals(status.toString(), CaptureReadiness.LIVE, state.readiness)
            assertEquals(status.toString(), "capture-1", state.captureId)
            assertTrue(status.toString(), state.practiceActive)
            assertTrue(status.toString(), state.blockReady)
            assertEquals(status, state.preview)
        }
        assertEquals(healthBefore, backend.health)
        assertEquals(callsBefore, backend.calls)
        assertTrue(store.pending().isEmpty())
    }

    @Test fun `a camera error while streaming is a banner only`() {
        val controller = live()
        publisher.listener.onPreviewStatus(PreviewStatus.CameraError(CAMERA_TEXT), CAMERA_TEXT)
        settle()
        assertEquals(CAMERA_TEXT, controller.state.value.previewBanner)
        assertEquals(CaptureReadiness.LIVE, controller.state.value.readiness)
        publisher.listener.onPreviewStatus(PreviewStatus.Ready, "Camera preview ready.")
        settle()
        assertNull(controller.state.value.previewBanner)
    }

    @Test fun `Start video is refused with a clear message while the camera reports an error`() {
        val controller = controller()
        publisher.listener.onPreviewStatus(PreviewStatus.CameraError(CAMERA_TEXT), CAMERA_TEXT)
        controller.startVideo()
        settle()
        assertTrue(backend.calls.isEmpty())
        assertTrue(publisher.calls.none { it.startsWith("start") })
        assertEquals("$CAMERA_TEXT. Tap Reopen camera, then Start video.", controller.state.value.statusDetail)
        assertFalse(controller.state.value.requestInFlight)

        controller.reopenCamera()
        assertEquals(listOf("reopenCamera"), publisher.calls)
        publisher.listener.onPreviewStatus(PreviewStatus.Ready, "Camera preview ready.")
        controller.startVideo()
        settle()
        assertEquals("capture-1", controller.state.value.captureId)
    }

    @Test fun `client-info retries re-send the byte-identical body`() {
        backend.failNext("clientInfo", apiFailure(DiagnosticStep.CLIENT_INFO, FailureKind.Timeout))
        backend.failNext("clientInfo", apiFailure(DiagnosticStep.CLIENT_INFO, FailureKind.Http(503)))
        live()
        settle()

        assertEquals(3, backend.clientInfoBodies.size)
        val first = backend.clientInfoBodies.first()
        backend.clientInfoBodies.forEach { assertArrayEquals(first, it) }
        assertEquals(listOf("capture-1", "capture-1", "capture-1"), backend.clientInfoCaptures)
        val geometry = CaptureGeometry.forDisplayRotation(0, 90).getOrThrow()
        val expected = ClientIdentity(
            BUILD.appVersionName, BUILD.appVersionCode, BUILD.appGitSha, BUILD.deviceManufacturer, BUILD.deviceModel,
            BUILD.androidSdkInt, 90, geometry.displayRotationDeg, geometry.orientation, geometry.encodedWidth,
            geometry.encodedHeight, START,
        ).body()
        assertArrayEquals(expected, first)
    }

    @Test fun `client info is built once per capture, and a reattached capture gets its own body`() {
        val controller = live()
        settle()
        assertEquals(1, backend.clientInfoBodies.size)
        // The transport fails: the capture ends and nothing more is sent for it.
        publisher.listener.onPublisherStatus(PublisherStatus.ERROR, "Video connection failed.")
        settle()
        assertEquals(1, backend.clientInfoBodies.size)

        controller.startVideo()
        settle()
        publisher.live()
        settle()
        assertEquals(listOf("capture-1", "capture-2"), backend.clientInfoCaptures)
        assertFalse(backend.clientInfoBodies[0].contentEquals(backend.clientInfoBodies[1]))
    }

    @Test fun `a failing client info never blocks Go live`() {
        repeat(SessionController.CLIENT_INFO_ATTEMPTS) {
            backend.failNext("clientInfo", apiFailure(DiagnosticStep.CLIENT_INFO, FailureKind.Unreachable))
        }
        val controller = live()
        settle()
        assertEquals(3, backend.clientInfoBodies.size)
        assertEquals(CaptureReadiness.LIVE, controller.state.value.readiness)
        assertFalse(controller.state.value.requestInFlight)
        controller.startPractice()
        settle()
        assertTrue(controller.state.value.practiceActive)
    }

    @Test fun `a client-info conflict or refusal is not retried and never blocks Go live`() {
        backend.clientInfoAnswer = ClientInfoResult.CONFLICT
        val controller = live()
        settle()
        assertEquals(1, backend.clientInfoBodies.size)
        assertEquals(CaptureReadiness.LIVE, controller.state.value.readiness)

        backend.failNext("clientInfo", apiFailure(DiagnosticStep.CLIENT_INFO, FailureKind.Http(400)))
        controller.stopVideo()
        settle()
        controller.startVideo()
        settle()
        publisher.live()
        settle()
        assertEquals(listOf("capture-1", "capture-2"), backend.clientInfoCaptures)
        assertEquals(CaptureReadiness.LIVE, controller.state.value.readiness)
    }

    @Test fun `every health detail is in the allowlist after injected API, SRT and camera failures with exception text`() {
        // API failures carrying exception text, on health, client info and practice calls.
        listOf("health", "clientInfo", "pausePractice").forEach { method ->
            repeat(2) { backend.failNext(method, IllegalStateException(MARKER)) }
        }
        backend.failNext("createBlock", apiFailure(DiagnosticStep.CREATE_BLOCK, FailureKind.Http(500)))
        val controller = live()
        controller.startPractice()
        settle()
        controller.startPractice()
        settle()
        assertTrue(controller.state.value.practiceActive)
        // SRT failures carry library text; the publisher passes status text through.
        publisher.listener.onPublisherStatus(PublisherStatus.RECONNECTING, "Publisher disconnected $MARKER")
        publisher.listener.onSourceDiscontinuity("SRT publisher disconnected $MARKER")
        settle()
        publisher.listener.onPublisherStatus(PublisherStatus.LIVE, "Canonical SRT source is live $MARKER")
        settle()
        // A camera failure, then the transport ends in ERROR.
        publisher.listener.onPreviewStatus(PreviewStatus.CameraError("Camera failed $MARKER"), "Camera failed $MARKER")
        publisher.listener.onPublisherStatus(PublisherStatus.ERROR, "Video could not start: $MARKER")
        settle()
        publisher.listener.onPreviewStatus(PreviewStatus.Ready, "Camera preview ready.")
        controller.startVideo()
        settle()
        publisher.live()
        controller.stopVideo()
        settle()
        controller.startVideo()
        settle()
        publisher.live()
        controller.onLeftScreen()
        settle()

        val details = backend.health.map { it.detail }
        assertTrue("$details", details.all { it in ALLOWLIST })
        assertTrue(details.none { "MARKER" in it })
        assertEquals(ALLOWLIST, details.toSet())
        assertEquals(
            listOf("camera unavailable", "stopped", "left the screen"),
            lostCalls().map { it.detail },
        )
        // The exception text went only to the log; the screen shows step messages.
        assertTrue(log.entries().any { it.errorClass == "IllegalStateException" && "MARKER-6A" in it.redactedMessage })
        assertFalse(controller.state.value.statusDetail.contains("MARKER"))
    }

    @Test fun `step-labelled messages for createSession and attachCapture failures`() {
        backend.failNext("createSession", apiFailure(DiagnosticStep.CREATE_SESSION, FailureKind.Timeout))
        val controller = controller()
        controller.startVideo()
        settle()
        assertEquals("Couldn't reach Clean Reps for create session (timeout) — is Tailscale on?", controller.state.value.statusDetail)
        assertFalse(controller.state.value.requestInFlight)

        backend.failNext("attachCapture", apiFailure(DiagnosticStep.ATTACH_CAPTURE, FailureKind.Http(409)))
        controller.startVideo()
        settle()
        assertEquals("Server refused attach capture (409)", controller.state.value.statusDetail)
        assertNull(controller.state.value.captureId)
        assertTrue(publisher.calls.none { it.startsWith("start") })
    }

    @Test fun `a failure outside the API is logged with its cause and shown by step only`() {
        val controller = controller()
        publisher.startFailure = IllegalStateException(MARKER)
        controller.startVideo()
        settle()
        assertEquals("Couldn't complete start video (unexpected error) — see Diagnostics", controller.state.value.statusDetail)
        val entry = log.entries().last { it.outcome == DiagnosticOutcome.FAIL }
        assertEquals(DiagnosticStep.PUBLISHER_START, entry.step)
        assertEquals("IllegalStateException", entry.errorClass)

        backend.failNext("createBlock", SocketTimeoutException(MARKER))
        publisher.startFailure = null
        controller.startVideo()
        settle()
        publisher.live()
        controller.startPractice()
        settle()
        assertEquals("Couldn't reach Clean Reps for start practice (timeout) — is Tailscale on? Video is still available.", controller.state.value.statusDetail)
        assertEquals(DiagnosticStep.CREATE_BLOCK, log.entries().last { it.outcome == DiagnosticOutcome.FAIL }.step)
    }

    @Test fun `verdict sounds come from the verdict class - pending and unjudgeable are silent`() {
        live()
        listOf(VerdictClass.ACCEPTED, VerdictClass.PENDING, VerdictClass.REJECTED, VerdictClass.UNJUDGEABLE).forEachIndexed { index, verdictClass ->
            events.onVerdict(MobileVerdictEvent("kick-$index", index.toLong(), "adj-$index", index.toLong(), "reason-$index", verdictClass))
        }
        assertEquals(listOf(FeedbackCue.Accept, FeedbackCue.Reject), signals.cues)
        assertTrue(signals.spoken.isEmpty())
    }

    @Test fun `practice flow - start, pause and resume call the same server steps as before`() {
        val controller = live()
        assertEquals("session-1", events.sessionId)
        controller.startPractice()
        settle()
        assertEquals(listOf("createBlock", "markReacquired"), backend.calls.filter { it in PRACTICE_CALLS })
        assertEquals("Practice active. Video continues independently.", controller.state.value.statusDetail)

        controller.pausePractice()
        settle()
        assertFalse(controller.state.value.practiceActive)
        assertEquals("Practice paused. Video continues.", controller.state.value.statusDetail)

        controller.selectDrill(BlockSelection(side = KickSide.LEFT))
        assertNull(controller.state.value.blockId)
        controller.startPractice()
        settle()
        controller.pausePractice()
        settle()
        controller.startPractice()
        settle()
        assertEquals(
            listOf("createBlock", "markReacquired", "pausePractice", "createBlock", "markReacquired", "pausePractice", "markReacquired", "resumePractice"),
            backend.calls.filter { it in PRACTICE_CALLS },
        )
        assertEquals("block-2", controller.state.value.blockId)

        backend.failNext("pausePractice", apiFailure(DiagnosticStep.PAUSE, FailureKind.Http(503)))
        controller.pausePractice()
        settle()
        assertTrue(controller.state.value.practiceActive)
        assertEquals("Server error during pause practice (503). Pause was not confirmed; retry or stop video before resting.", controller.state.value.statusDetail)
    }

    @Test fun `close ends an attached capture, stops the event stream and releases the publisher`() {
        val controller = controller()
        publisher.startResult = PublisherResult.Blocked("MEDIAMTX_SRT_HOST is not configured")
        controller.startVideo()
        settle()
        assertEquals(CaptureReadiness.PUBLISHER_UNAVAILABLE, controller.state.value.readiness)
        assertEquals("capture-1", controller.state.value.captureId)

        controller.close()
        assertEquals(mapOf("capture-1" to HealthDetail.STOPPED), store.pending())
        assertTrue("release" in publisher.calls)
        assertEquals(1, events.stops)
        settle()
        assertEquals(listOf(HealthCall("capture-1", "lost", "stopped", setOf("capture-1"))), lostCalls())
        assertTrue(store.pending().isEmpty())
    }

    @Test fun `a capture attached after the video stopped is reported lost instead of left open`() {
        val controller = live()
        backend.suspendAttach = true
        publisher.listener.onSourceDiscontinuity("SRT publisher disconnected.")
        publisher.listener.onPublisherStatus(PublisherStatus.RECONNECTING, "Publisher disconnected; retrying.")
        settle()
        assertNull(controller.state.value.captureId)
        controller.stopVideo()
        settle()
        backend.releaseAttach()
        settle()
        assertNull(controller.state.value.captureId)
        assertEquals(listOf(HealthCall("capture-2", "lost", "stopped", setOf("capture-2"))), lostCalls())
    }

    @Test fun `a pending lost that cannot be stored is still sent`() {
        val controller = live()
        preferences.commitSucceeds = false
        controller.onLeftScreen()
        settle()
        assertEquals(1, lostCalls().size)
        assertTrue(log.entries().any { it.step == DiagnosticStep.HEALTH && "could not persist lost" in it.redactedMessage })
    }

    @Test fun `the SharedPreferences store keeps allowlisted details and reads anything else as stopped`() {
        store.add("capture-a", HealthDetail.LEFT_SCREEN)
        store.add("capture-b", HealthDetail.CAMERA_UNAVAILABLE)
        preferences.values["capture-c"] = "Publisher disconnected $MARKER"
        assertEquals(
            mapOf("capture-a" to HealthDetail.LEFT_SCREEN, "capture-b" to HealthDetail.CAMERA_UNAVAILABLE, "capture-c" to HealthDetail.STOPPED),
            store.pending(),
        )
        assertEquals("left the screen", preferences.values["capture-a"])
        store.remove("capture-a")
        assertEquals(setOf("capture-b", "capture-c"), store.pending().keys)
        preferences.commitSucceeds = false
        assertThrows(IllegalStateException::class.java) { store.add("capture-d", HealthDetail.STOPPED) }
        assertEquals(ALLOWLIST, HealthDetail.entries.map { it.wireValue }.toSet())
    }

    private data class HealthCall(val captureId: String, val status: String, val detail: String, val pendingAtCall: Set<String>)

    private inner class FakeBackend : ChallengeBackend {
        val calls = mutableListOf<String>()
        val health = mutableListOf<HealthCall>()
        val clientInfoBodies = mutableListOf<ByteArray>()
        val clientInfoCaptures = mutableListOf<String>()
        var clientInfoAnswer = ClientInfoResult.STORED
        private val failures = mutableMapOf<String, ArrayDeque<Exception>>()
        private var captures = 0
        var suspendAttach = false
        private var attachGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        fun failNext(method: String, error: Exception) {
            failures.getOrPut(method) { ArrayDeque() }.addLast(error)
        }

        fun releaseAttach() {
            attachGate?.complete(Unit)
        }

        private fun call(method: String) {
            calls += method
            failures[method]?.removeFirstOrNull()?.let { throw it }
        }

        override suspend fun createSession(challengeId: String): String {
            call("createSession")
            return "session-1"
        }

        override suspend fun createBlock(sessionId: String, selection: BlockSelection): String {
            call("createBlock")
            return "block-${calls.count { it == "createBlock" }}"
        }

        override suspend fun markReacquired(sessionId: String, blockId: String) = call("markReacquired")
        override suspend fun pausePractice(sessionId: String, blockId: String) = call("pausePractice")
        override suspend fun resumePractice(sessionId: String, blockId: String) = call("resumePractice")

        override suspend fun attachCapture(sessionId: String, sourceId: String, epoch: SourceEpoch): String {
            assertEquals(SOURCE, sourceId)
            call("attachCapture")
            val id = "capture-${++captures}"
            if (suspendAttach) kotlinx.coroutines.CompletableDeferred<Unit>().also { attachGate = it }.await()
            return id
        }

        override suspend fun reportSourceHealth(captureId: String, status: String, detail: String) {
            health += HealthCall(captureId, status, detail, store.pending().keys)
            call(if (status == "lost") "lost" else "health")
        }

        override suspend fun logManualAttempt(
            sessionId: String, blockId: String, captureId: String, sourceId: String, epoch: SourceEpoch,
            occurredAt: String, window: ManualEvidenceWindow,
        ): String {
            call("logManualAttempt")
            return "event-1"
        }

        override suspend fun clientInfo(captureId: String, body: ByteArray): ClientInfoResult {
            clientInfoCaptures += captureId
            clientInfoBodies += body.copyOf()
            call("clientInfo")
            return clientInfoAnswer
        }

        override suspend fun health() = ServerHealth(reachable = true, release = null)
        override suspend fun qualityReport(captureId: String): FetchResult<String> = FetchResult.NotFound
        override suspend fun thumbnail(captureId: String, index: Int): FetchResult<ByteArray> = FetchResult.NotFound
    }

    /** Behaves like MediaMtxSrtPublisher at its listener: start reports CONNECTING, stop reports STOPPED. */
    private class FakePublisher(val listener: PublisherListener) : CanonicalSourcePublisher {
        val calls = mutableListOf<String>()
        var startResult: PublisherResult = PublisherResult.Connecting(SOURCE)
        var startFailure: Exception? = null

        override suspend fun start(epoch: SourceEpoch): PublisherResult {
            calls += "start:${epoch.value}"
            startFailure?.let { throw it }
            if (startResult is PublisherResult.Connecting) {
                listener.onPublisherStatus(PublisherStatus.CONNECTING, "Connecting one SRT source for epoch ${epoch.displayId}...")
            }
            return startResult
        }

        override suspend fun stop() {
            calls += "stop"
            listener.onPublisherStatus(PublisherStatus.STOPPED, "Capture stopped. Local safety spool is retained in app cache.")
        }

        fun live() = listener.onPublisherStatus(PublisherStatus.LIVE, "Canonical SRT source is live: $SOURCE")

        override val isAvailable = true
        override fun attachPreview(view: SurfaceView) = Unit
        override fun releasePreview() = Unit
        override val preparedGeometry: CaptureGeometry = CaptureGeometry.forDisplayRotation(0, 90).getOrThrow()
        override val sensorOrientationDeg: Int = 90
        override fun reopenCamera() {
            calls += "reopenCamera"
        }
        override fun frameCheck(callback: (Bitmap?, Int, Int) -> Unit) = callback(null, 0, 0)
        override fun release() {
            calls += "release"
        }
    }

    private class FakeEvents : ChallengeEvents {
        var sessionId: String? = null
        var stops = 0
        lateinit var onVerdict: (MobileVerdictEvent) -> Unit

        override fun start(
            scope: CoroutineScope,
            sessionId: String,
            onVerdict: (MobileVerdictEvent) -> Unit,
            onCue: (AthleteCue) -> Unit,
            onCueSafe: (AthleteCue) -> Unit,
            onError: (String) -> Unit,
            onChallengeTotal: (Long) -> Unit,
            onLiveAnalysis: (LiveAnalysisStatus?) -> Unit,
            onSessionCounts: (SessionCounts) -> Unit,
        ) {
            this.sessionId = sessionId
            this.onVerdict = onVerdict
        }

        override fun stop() {
            stops++
        }
    }

    private class RecordingSignals : AthleteSignals {
        val cues = mutableListOf<FeedbackCue>()
        val spoken = mutableListOf<String>()
        override fun cue(cue: FeedbackCue) {
            cues += cue
        }
        override fun speak(text: String) {
            spoken += text
        }
    }

    /** In-memory SharedPreferences: the last change to a key in an editor wins, as on Android. */
    private class FakePreferences : SharedPreferences {
        val values = linkedMapOf<String, String>()
        var commitSucceeds = true

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] ?: defValue
        override fun contains(key: String?): Boolean = key in values
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = unused()
        override fun getInt(key: String?, defValue: Int): Int = unused()
        override fun getLong(key: String?, defValue: Long): Long = unused()
        override fun getFloat(key: String?, defValue: Float): Float = unused()
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = unused()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = unused()
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = unused()

        private inner class Editor : SharedPreferences.Editor {
            private val changes = linkedMapOf<String, String?>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                changes[requireNotNull(key)] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                changes[requireNotNull(key)] = null
                return this
            }
            override fun commit(): Boolean {
                if (!commitSucceeds) return false
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                return true
            }
            override fun apply() {
                commit()
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = unused()
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = unused()
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = unused()
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = unused()
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = unused()
            override fun clear(): SharedPreferences.Editor = unused()
        }

        private fun unused(): Nothing = throw UnsupportedOperationException("not used by the pending-lost store")
    }

    private companion object {
        const val SOURCE = "million-kicks-camera"
        /** Exception text a failure might carry; synthetic RFC 2606/5737 values only. */
        const val MARKER = "MARKER-6A api.example.test/192.0.2.10:8443 key fixture-key-7"
        const val CAMERA_TEXT = "Camera problem during camera open — close other camera apps and try again"
        val START: Instant = Instant.parse("2030-01-01T00:00:00Z")
        val BUILD = ClientBuild("0.3.0-rehearsal", 3, "abc1234", "motorola", "moto g power 2024", 34)
        val ALLOWLIST = setOf("live", "reconnecting", "stopped", "left the screen", "camera unavailable")
        val PRACTICE_CALLS = setOf("createBlock", "markReacquired", "pausePractice", "resumePractice")

        fun apiFailure(step: DiagnosticStep, kind: FailureKind) =
            ChallengeApi.ApiException("${step.wireName} failed $MARKER", step, kind, ConnectException(MARKER))
    }
}
