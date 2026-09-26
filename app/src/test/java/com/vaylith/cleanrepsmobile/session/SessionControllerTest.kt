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
import com.vaylith.cleanrepsmobile.feedback.FeedbackPolicy
import com.vaylith.cleanrepsmobile.media.CanonicalSourcePublisher
import com.vaylith.cleanrepsmobile.media.CaptureGeometry
import com.vaylith.cleanrepsmobile.media.FakeEncoderPort
import com.vaylith.cleanrepsmobile.media.FakeSurface
import com.vaylith.cleanrepsmobile.media.PreviewCoordinator
import com.vaylith.cleanrepsmobile.media.PreviewStatus
import com.vaylith.cleanrepsmobile.media.PublisherListener
import com.vaylith.cleanrepsmobile.media.PublisherResult
import com.vaylith.cleanrepsmobile.media.PublisherStatus
import com.vaylith.cleanrepsmobile.media.StreamGate
import com.vaylith.cleanrepsmobile.model.AthleteCue
import com.vaylith.cleanrepsmobile.model.BlockSelection
import com.vaylith.cleanrepsmobile.model.CaptureReadiness
import com.vaylith.cleanrepsmobile.model.ClientIdentity
import com.vaylith.cleanrepsmobile.model.DrillSelectionStore
import com.vaylith.cleanrepsmobile.model.KickSide
import com.vaylith.cleanrepsmobile.model.KickTechnique
import com.vaylith.cleanrepsmobile.model.LiveAnalysisState
import com.vaylith.cleanrepsmobile.model.LiveAnalysisStatus
import com.vaylith.cleanrepsmobile.model.LiveBlockedReason
import com.vaylith.cleanrepsmobile.model.ManualEvidenceWindow
import com.vaylith.cleanrepsmobile.model.SourceEpoch
import com.vaylith.cleanrepsmobile.model.VerdictClass
import com.vaylith.cleanrepsmobile.ui.PracticeState
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.runBlocking
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
    /** One ordered record of lock, backend and publisher calls, to check what happens before what. */
    private val order = mutableListOf<String>()
    private val backend = FakeBackend()
    private val events = FakeEvents()
    private val signals = RecordingSignals { scheduler.currentTime }
    private val drills = MemoryDrills()
    /** One per process in the app ([AppScope.lostDeliveries]); shared by every controller of a test. */
    private val lostDeliveries = LostDeliveries()
    private var visible = true
    private var wallClockReads = 0L
    private var clockReads = 0
    private lateinit var publisher: FakePublisher

    @After fun tearDown() {
        uiScope.cancel()
        appScope.cancel()
    }

    private fun controller(
        initial: AppState = AppState(),
        backend: FakeBackend = this.backend,
        server: String = SERVER,
        newPublisher: (PublisherListener) -> CanonicalSourcePublisher = { listener -> FakePublisher(listener, order).also { publisher = it } },
    ) = SessionController(
        backend = backend,
        events = events,
        newPublisher = newPublisher,
        signals = signals,
        diagnostics = log,
        pendingLost = store,
        lostDeliveries = lostDeliveries,
        serverKey = server,
        drills = drills,
        orientationLock = { locked -> order += if (locked) "lock" else "unlock" },
        appScope = appScope,
        uiScope = uiScope,
        isMainThread = { true },
        // The virtual time of the test scheduler, so the FeedbackPolicy tick and its clock agree.
        elapsedRealtime = { clockReads++; scheduler.currentTime },
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

    /**
     * Runs everything due within [SETTLE_MS] of virtual time: every backoff and
     * retry. Never advanceUntilIdle: while the video is LIVE the FeedbackPolicy
     * tick is always due again.
     */
    private fun settle() {
        scheduler.advanceTimeBy(SETTLE_MS)
        scheduler.runCurrent()
    }

    private fun lostCalls() = backend.health.filter { it.status == "lost" }

    private fun pending(detail: HealthDetail) = PendingLost(detail, SERVER)

    private fun status(state: LiveAnalysisState, reason: LiveBlockedReason? = null, stale: Boolean = false) =
        LiveAnalysisStatus(state = state, reasonCode = reason, stale = stale)

    @Test fun `lost survives cancellation of the UI scope and is persisted before it is sent`() {
        val controller = live()
        backend.failNext("lost", apiFailure(DiagnosticStep.HEALTH, FailureKind.Unreachable))

        controller.onLeftScreen()
        // Persisted synchronously at ON_STOP, before any attempt runs.
        assertEquals(mapOf("capture-1" to pending(HealthDetail.LEFT_SCREEN)), store.pending())
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
        assertEquals("Video stopped because Clean Reps left the screen. Tap Restart video.", controller.state.value.banner)
    }

    @Test fun `Stop video persists lost, sends it with three attempts and backoff, and keeps it pending after the third`() {
        val controller = live()
        repeat(3) { backend.failNext("lost", apiFailure(DiagnosticStep.HEALTH, FailureKind.Timeout)) }

        controller.stopVideo()
        assertEquals(mapOf("capture-1" to pending(HealthDetail.STOPPED)), store.pending())
        uiScope.cancel()

        scheduler.advanceTimeBy(1)
        assertEquals(1, lostCalls().size)
        scheduler.advanceTimeBy(1_000)
        assertEquals(2, lostCalls().size)
        scheduler.advanceTimeBy(4_000)
        assertEquals(3, lostCalls().size)
        settle()
        assertEquals(3, lostCalls().size)
        assertEquals(mapOf("capture-1" to pending(HealthDetail.STOPPED)), store.pending())
        assertTrue(log.entries().any { it.step == DiagnosticStep.HEALTH && "still pending after 3 attempts" in it.redactedMessage })
        assertNull(controller.state.value.captureId)
    }

    @Test fun `pending lost reports are flushed on app start`() {
        store.add("capture-old", pending(HealthDetail.LEFT_SCREEN))
        controller()
        settle()
        assertEquals(listOf(HealthCall("capture-old", "lost", "left the screen", setOf("capture-old"))), backend.health)
        assertTrue(store.pending().isEmpty())
    }

    @Test fun `a pending lost is flushed before a new capture is attached`() {
        store.add("capture-old", pending(HealthDetail.STOPPED))
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
        store.add("capture-old", pending(HealthDetail.STOPPED))
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
        store.add("capture-unknown", pending(HealthDetail.STOPPED))
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

        // One failure for the block created at LIVE (M6b warm start), one for Start practice's own attempt.
        backend.failNext("createBlock", SocketTimeoutException(MARKER))
        backend.failNext("createBlock", SocketTimeoutException(MARKER))
        publisher.startFailure = null
        controller.startVideo()
        settle()
        publisher.live()
        assertEquals(
            "Couldn't reach Clean Reps for start practice (timeout) — is Tailscale on? Analysis starts when you tap Start practice.",
            controller.state.value.statusDetail,
        )
        controller.startPractice()
        settle()
        assertEquals("Couldn't reach Clean Reps for start practice (timeout) — is Tailscale on? Video is still available.", controller.state.value.statusDetail)
        assertEquals(DiagnosticStep.CREATE_BLOCK, log.entries().last { it.outcome == DiagnosticOutcome.FAIL }.step)
    }

    @Test fun `a step failure is also the error banner, without its cause, until the next action starts`() {
        backend.failNext("createSession", apiFailure(DiagnosticStep.CREATE_SESSION, FailureKind.Timeout))
        val controller = controller()
        assertNull(controller.state.value.stepError)
        controller.startVideo()
        settle()
        val createSession = "Couldn't reach Clean Reps for create session (timeout) — is Tailscale on?"
        assertEquals(createSession, controller.state.value.stepError)
        assertEquals(createSession, controller.state.value.statusDetail)

        // Go live again: cleared at the tap, and the video comes up.
        controller.startVideo()
        assertNull(controller.state.value.stepError)
        settle()
        publisher.live()
        settle()
        assertNull(controller.state.value.stepError)

        // A failure outside the API (planted cause text) at Start practice: the banner names the step only.
        backend.failNext("markReacquired", SocketTimeoutException(MARKER))
        controller.startPractice()
        settle()
        val stepError = controller.state.value.stepError
        assertEquals("Couldn't reach Clean Reps for back-in-frame marker (timeout) — is Tailscale on? Video is still available.", stepError)
        assertFalse("MARKER" in stepError!!)

        // Stop video is an action too.
        controller.stopVideo()
        assertNull(controller.state.value.stepError)
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
        // M6b warm start: while LIVE the new drill gets its block at once, not yet started.
        assertEquals("block-2", controller.state.value.blockId)
        assertEquals(PracticeState.NOT_STARTED, controller.state.value.practice)
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
        assertEquals(mapOf("capture-1" to pending(HealthDetail.STOPPED)), store.pending())
        assertTrue("release" in publisher.calls)
        assertEquals(1, events.stops)
        settle()
        assertEquals(listOf(HealthCall("capture-1", "lost", "stopped", setOf("capture-1"))), lostCalls())
        assertTrue(store.pending().isEmpty())
    }

    @Test fun `a capture attached after the video stopped is reported lost instead of left open`() {
        val controller = live()
        val attach = backend.hold("attachCapture")
        publisher.listener.onSourceDiscontinuity("SRT publisher disconnected.")
        publisher.listener.onPublisherStatus(PublisherStatus.RECONNECTING, "Publisher disconnected; retrying.")
        settle()
        assertNull(controller.state.value.captureId)
        controller.stopVideo()
        settle()
        attach.complete(Unit)
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
        store.add("capture-a", pending(HealthDetail.LEFT_SCREEN))
        store.add("capture-b", PendingLost(HealthDetail.CAMERA_UNAVAILABLE, OTHER_SERVER))
        preferences.values["capture-c"] = "Publisher disconnected $MARKER"
        assertEquals(
            mapOf(
                "capture-a" to pending(HealthDetail.LEFT_SCREEN),
                "capture-b" to PendingLost(HealthDetail.CAMERA_UNAVAILABLE, OTHER_SERVER),
                // No server key: it belongs to no server in particular.
                "capture-c" to PendingLost(HealthDetail.STOPPED, SharedPreferencesPendingLostStore.ANY_SERVER),
            ),
            store.pending(),
        )
        assertEquals("$SERVER|left the screen", preferences.values["capture-a"])
        store.remove("capture-a")
        assertEquals(setOf("capture-b", "capture-c"), store.pending().keys)
        preferences.commitSucceeds = false
        assertThrows(IllegalStateException::class.java) { store.add("capture-d", pending(HealthDetail.STOPPED)) }
        assertEquals(ALLOWLIST, HealthDetail.entries.map { it.wireValue }.toSet())
    }

    @Test fun `the server key is a stable digest that never contains the address`() {
        val key = lostServerKey("http://api.example.test:8443")
        assertEquals(key, lostServerKey("http://api.example.test:8443/"))
        assertEquals(key, lostServerKey(" http://api.example.test:8443 "))
        assertTrue(key, Regex("[0-9a-f]{16}").matches(key))
        assertFalse(key == lostServerKey("http://192.0.2.10:8443"))
        assertFalse("example" in key)
    }

    // ---- M6b: warm start, drill changes, orientation lock, Restart video, FeedbackPolicy ----

    @Test fun `LIVE creates the block before Start, Start marks it reacquired and Resume resumes it (OD-8)`() {
        val controller = live()
        assertEquals(listOf("createBlock"), backend.calls.filter { it in PRACTICE_CALLS })
        assertEquals("block-1", controller.state.value.blockId)
        assertFalse(controller.state.value.practiceActive)
        assertEquals(PracticeState.NOT_STARTED, controller.state.value.practice)

        controller.startPractice()
        settle()
        assertEquals(listOf("createBlock", "markReacquired"), backend.calls.filter { it in PRACTICE_CALLS })
        assertEquals(PracticeState.ACTIVE, controller.state.value.practice)
        controller.pausePractice()
        settle()
        assertEquals(PracticeState.PAUSED, controller.state.value.practice)
        controller.startPractice()
        settle()
        assertEquals(
            listOf("createBlock", "markReacquired", "pausePractice", "markReacquired", "resumePractice"),
            backend.calls.filter { it in PRACTICE_CALLS },
        )

        // LIVE again after a reconnect keeps the block.
        publisher.listener.onPublisherStatus(PublisherStatus.RECONNECTING, "Publisher disconnected; retrying.")
        publisher.live()
        settle()
        assertEquals(1, backend.calls.count { it == "createBlock" })
    }

    @Test fun `Start practice never asks for a confirmation, whatever the live status says (OD-6)`() {
        val controller = live()
        val planted = listOf(
            LiveAnalysisState.NO_PERSON, LiveAnalysisState.SIDEWAYS, LiveAnalysisState.HEAD_CUT,
            LiveAnalysisState.FEET_CUT, LiveAnalysisState.MULTIPLE_PEOPLE, LiveAnalysisState.ACQUIRING,
        )
        for (state in planted) {
            events.onLiveAnalysis(status(state))
            val before = backend.calls.count { it == "markReacquired" }
            // The tap itself goes to the server; nothing waits for an answer from the athlete.
            controller.startPractice()
            assertEquals(state.name, before + 1, backend.calls.count { it == "markReacquired" })
            assertTrue(state.name, controller.state.value.practiceActive)
            controller.pausePractice()
            settle()
        }
    }

    @Test fun `a drill change during practice is refused, while paused it gets a new waiting block and is saved`() {
        drills.stored = BlockSelection(side = KickSide.LEFT)
        val controller = live()
        assertEquals(KickSide.LEFT, controller.state.value.selection.side)
        controller.startPractice()
        settle()

        val right = BlockSelection(side = KickSide.RIGHT)
        controller.selectDrill(right)
        assertEquals(KickSide.LEFT, controller.state.value.selection.side)
        assertEquals("Pause practice to change the drill.", controller.state.value.statusDetail)
        assertTrue(drills.saved.isEmpty())
        assertEquals(1, backend.calls.count { it == "createBlock" })

        controller.pausePractice()
        settle()
        controller.selectDrill(right)
        settle()
        assertEquals(listOf(right), drills.saved)
        assertEquals(right, controller.state.value.selection)
        assertEquals("block-2", controller.state.value.blockId)
        assertEquals(PracticeState.NOT_STARTED, controller.state.value.practice)
        assertEquals(1, backend.calls.count { it == "markReacquired" })

        // OD-5: only Teep is judged automatically.
        controller.selectDrill(right.copy(technique = KickTechnique.SIDE_KICK))
        assertEquals(right, controller.state.value.selection)
        assertEquals("Automatic analysis supports Teep only", controller.state.value.statusDetail)
        assertEquals(listOf(right), drills.saved)

        // Not LIVE: the block waits for the next LIVE.
        controller.stopVideo()
        settle()
        val low = right.copy(targetHeight = com.vaylith.cleanrepsmobile.model.TargetHeight.LOW)
        drills.saveFails = true
        controller.selectDrill(low)
        assertNull(controller.state.value.blockId)
        assertEquals(low, controller.state.value.selection)
        assertTrue(log.entries().any { "drill choice not saved" in it.redactedMessage })
        assertEquals(2, backend.calls.count { it == "createBlock" })
        controller.startVideo()
        settle()
        publisher.live()
        settle()
        assertEquals(3, backend.calls.count { it == "createBlock" })
        assertEquals("block-3", controller.state.value.blockId)
    }

    @Test fun `Go live locks the orientation before the publisher starts and releases it when the video stops`() {
        val controller = controller()
        controller.startVideo()
        // At the tap, before any server call and before the publisher reads the rotation.
        assertEquals("lock", order.first())
        assertTrue("$order", order.indexOf("lock") < order.indexOf("start:0"))
        settle()
        assertTrue(controller.state.value.orientationLocked)
        publisher.live()
        settle()
        assertTrue(controller.state.value.orientationLocked)
        publisher.listener.onPublisherStatus(PublisherStatus.RECONNECTING, "Publisher disconnected; retrying.")
        assertTrue(controller.state.value.orientationLocked)
        publisher.live()
        controller.stopVideo()
        settle()
        assertFalse(controller.state.value.orientationLocked)

        // ERROR releases it.
        controller.startVideo()
        settle()
        publisher.listener.onPublisherStatus(PublisherStatus.ERROR, "Video connection failed.")
        assertFalse(controller.state.value.orientationLocked)

        // A failed start releases it: a refused start, a blocked publisher and a failing server call.
        publisher.startResult = PublisherResult.Failed("Video could not start.")
        controller.startVideo()
        settle()
        assertFalse(controller.state.value.orientationLocked)
        publisher.startResult = PublisherResult.Blocked("MEDIAMTX_SRT_HOST is not configured")
        controller.startVideo()
        settle()
        assertFalse(controller.state.value.orientationLocked)
        backend.failNext("attachCapture", apiFailure(DiagnosticStep.ATTACH_CAPTURE, FailureKind.Timeout))
        controller.close()
        val next = controller()
        next.startVideo()
        settle()
        assertFalse(next.state.value.orientationLocked)

        val locks = order.filter { it == "lock" || it == "unlock" }
        assertEquals(List(locks.size / 2) { listOf("lock", "unlock") }.flatten(), locks)
        assertEquals(10, locks.size)
    }

    @Test fun `a rotation between the tap and the stream start never streams a stale geometry (I8), watcher on time`() =
        rotationBetweenTapAndStream(watcherDelivers = true)

    @Test fun `a rotation between the tap and the stream start never streams a stale geometry (I8), watcher late`() =
        rotationBetweenTapAndStream(watcherDelivers = false)

    private fun rotationBetweenTapAndStream(watcherDelivers: Boolean) {
        var rotation = 1
        lateinit var coordinated: CoordinatedPublisher
        val controller = controller(newPublisher = { listener ->
            CoordinatedPublisher(listener, order) { rotation }.also { coordinated = it }
        })
        val landscape = CaptureGeometry.forDisplayRotation(1, 90).getOrThrow()
        val portrait = CaptureGeometry.forDisplayRotation(0, 90).getOrThrow()
        assertEquals(landscape, coordinated.preparedGeometry)

        val session = backend.hold("createSession")
        controller.startVideo()
        // The phone turns after the tap and before the stream starts.
        rotation = 0
        if (watcherDelivers) coordinated.rotated(0)
        session.complete(Unit)
        settle()

        assertEquals(listOf(portrait), coordinated.port.streamStarts)
        assertEquals(emptyList<String>(), coordinated.port.violations)
        assertEquals(portrait.label, controller.state.value.streamGeometry)
        val lock = order.indexOf("lock")
        val read = order.indexOf("readRotation:0")
        val stream = order.indexOf("startStream:${portrait.label}")
        assertTrue("$order", lock in 0 until read && read < stream)
        val calls = coordinated.port.calls
        assertEquals("prepareVideo(${portrait.rotationArg})", calls.subList(0, calls.indexOf("startStream")).last { it.startsWith("prepareVideo") })
    }

    @Test fun `Restart video stops, reports the old capture lost, attaches a new capture with a new epoch and starts again`() {
        val controller = live()
        controller.startPractice()
        settle()
        events.onLiveAnalysis(status(LiveAnalysisState.BLOCKED, LiveBlockedReason.WAITING_FOR_NEW_CAPTURE_EPOCH))
        assertTrue(controller.state.value.restartOffered)
        order.clear()

        controller.restartVideo()
        settle()
        publisher.live()
        settle()

        val sequence = listOf("stop", "unlock", "lock", "lost:capture-1", "attachCapture:1", "start:1")
        assertEquals(sequence, order.filter { it in sequence })
        assertTrue("$order", order.indexOf("pausePractice") in 0 until order.indexOf("attachCapture:1"))
        assertEquals(listOf(0L, 1L), backend.attachEpochs)
        assertEquals(listOf(HealthCall("capture-1", "lost", "stopped", setOf("capture-1"))), lostCalls())
        assertEquals("capture-2", controller.state.value.captureId)
        assertEquals(CaptureReadiness.LIVE, controller.state.value.readiness)
        assertTrue(controller.state.value.orientationLocked)
        assertTrue(store.pending().isEmpty())
        assertFalse(controller.state.value.requestInFlight)
    }

    @Test fun `Restart video is offered only for the three blocked reasons, while LIVE`() {
        val controller = live()
        val restartReasons = setOf(
            LiveBlockedReason.WAITING_FOR_NEW_CAPTURE_EPOCH,
            LiveBlockedReason.WORKER_RETRY_LIMIT,
            LiveBlockedReason.AMBIGUOUS_ACTIVE_SESSIONS,
        )
        for (reason in LiveBlockedReason.entries) {
            events.onLiveAnalysis(status(LiveAnalysisState.BLOCKED, reason))
            assertEquals(reason.name, reason in restartReasons, controller.state.value.restartOffered)
        }
        events.onLiveAnalysis(status(LiveAnalysisState.BLOCKED, LiveBlockedReason.WAITING_FOR_NEW_CAPTURE_EPOCH, stale = true))
        assertFalse(controller.state.value.restartOffered)
        events.onLiveAnalysis(status(LiveAnalysisState.NO_PERSON))
        assertFalse(controller.state.value.restartOffered)
        events.onLiveAnalysis(null)
        assertFalse(controller.state.value.restartOffered)
        events.onLiveAnalysis(status(LiveAnalysisState.BLOCKED, LiveBlockedReason.WORKER_RETRY_LIMIT))
        assertTrue(controller.state.value.restartOffered)
        controller.stopVideo()
        settle()
        assertFalse(controller.state.value.restartOffered)
    }

    @Test fun `with one no_person status after Start and no further events, the tick gives exactly one LOST at +10 s`() {
        val controller = live()
        val armedAt = scheduler.currentTime
        controller.startPractice()
        assertTrue(controller.state.value.practiceActive)
        events.onLiveAnalysis(status(LiveAnalysisState.NO_PERSON))

        scheduler.advanceTimeBy(FeedbackPolicy.WALK_BACK_GRACE_MS)
        assertTrue(signals.cues.none { it == FeedbackCue.Lost })
        scheduler.runCurrent()
        val lostAt = signals.cues.indices.filter { signals.cues[it] == FeedbackCue.Lost }.map { signals.cueTimes[it] }
        assertEquals(listOf(armedAt + 10_000), lostAt)
        // OD-4: the spoken hint comes with it.
        assertTrue(FeedbackCue.Speak("Can't see you - get your whole body, head to feet, in view") in signals.cues)

        scheduler.advanceTimeBy(14_000)
        scheduler.runCurrent()
        assertEquals(1, signals.cues.count { it == FeedbackCue.Lost })
    }

    @Test fun `the FeedbackPolicy tick runs only while the video is LIVE`() {
        val controller = live()
        val reads = clockReads
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()
        // One clock read per tick: 4 ticks in 1 s of LIVE.
        assertEquals(4, clockReads - reads)
        controller.stopVideo()
        settle()
        val stopped = clockReads
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()
        assertEquals(stopped, clockReads)
    }

    @Test fun `feedback wiring - unjudgeable is silent with a banner, tracking after Start gives one READY`() {
        val controller = live()
        controller.startPractice()
        events.onVerdict(verdict(VerdictClass.UNJUDGEABLE, "evidence_failed"))
        assertTrue(signals.cues.isEmpty())
        assertEquals(FeedbackPolicy.UNJUDGED_BANNER, controller.state.value.feedbackBanner)
        assertEquals("Couldn't judge that one - keep head and feet in view", controller.state.value.feedbackBanner)

        events.onLiveAnalysis(status(LiveAnalysisState.TRACKING))
        events.onLiveAnalysis(status(LiveAnalysisState.TRACKING))
        assertEquals(listOf<FeedbackCue>(FeedbackCue.Ready), signals.cues)

        events.onVerdict(verdict(VerdictClass.ACCEPTED, "practice_cycle_complete"))
        assertEquals(listOf(FeedbackCue.Ready, FeedbackCue.Accept), signals.cues)
        assertNull(controller.state.value.feedbackBanner)

        // Speak verdicts uses the phrase mapping.
        controller.toggleSpeakVerdicts()
        events.onVerdict(verdict(VerdictClass.REJECTED, "practice_no_extension"))
        assertEquals(listOf(FeedbackCue.Ready, FeedbackCue.Accept, FeedbackCue.Reject, FeedbackCue.Speak("No extension")), signals.cues)

        // OD-4: voice hints are on by default, with a toggle.
        assertTrue(AppState().voiceHints)
        assertTrue(controller.state.value.voiceHints)
        controller.toggleVoiceHints()
        assertFalse(controller.state.value.voiceHints)
    }

    @Test fun `session counts and the LIVE pill are exposed`() {
        val controller = live()
        assertEquals("LIVE - ${CaptureGeometry.forDisplayRotation(0, 90).getOrThrow().label}", controller.state.value.livePill)
        events.onSessionCounts(SessionCounts(accepted = 3, rejected = 1, evidenceFailed = 2))
        assertEquals("This session 3 accepted - 1 rejected", controller.state.value.sessionCountsText)
        controller.stopVideo()
        settle()
        assertNull(controller.state.value.livePill)
    }

    @Test fun `Stop video leaves the ended capture for the Session check card, and a new video keeps it until it ends`() {
        val controller = live()
        assertNull(controller.state.value.lastCaptureId)
        controller.stopVideo()
        settle()
        assertEquals("capture-1", controller.state.value.lastCaptureId)
        assertNull(controller.state.value.captureId)
        // The next video: the card still refers to the capture before it until this one ends.
        controller.startVideo()
        settle()
        publisher.live()
        settle()
        val second = controller.state.value.captureId!!
        assertEquals("capture-1", controller.state.value.lastCaptureId)
        controller.onLeftScreen()
        settle()
        assertEquals(second, controller.state.value.lastCaptureId)
    }

    @Test fun `the card and the Diagnostics sheet read through the controller`() = runBlocking {
        val controller = live()
        backend.reportAnswer = FetchResult.Found("""{"schemaVersion":1}""")
        assertEquals(FetchResult.Found("""{"schemaVersion":1}"""), controller.qualityReport("capture-1"))
        val thumb = controller.thumbnail("capture-1", 2) as FetchResult.Found
        assertArrayEquals(byteArrayOf(2), thumb.value)
        assertEquals(listOf("capture-1", "capture-1/thumb-2"), backend.reportReads)
        // Diagnostics: the controller's own log, and its redacted export.
        log.info(DiagnosticStep.PREVIEW_START, "probe event")
        // On screen: the lines redacted again with the current settings, never the raw entries.
        assertEquals(log.exportLines(), controller.diagnosticEventLines())
        assertTrue(controller.diagnosticEventLines().last().endsWith("previewStart: probe event"))
        assertEquals(log.exportText(), controller.diagnosticsExport())
        assertTrue("probe event" in controller.diagnosticsExport())
    }

    @Test fun `session counts change only with the server snapshot, never with a verdict (planted)`() {
        val controller = live()
        controller.startPractice()
        settle()
        events.onSessionCounts(SessionCounts(accepted = 1, rejected = 0, evidenceFailed = 0))
        assertEquals("This session 1 accepted - 0 rejected", controller.state.value.sessionCountsText)
        // Verdicts arrive on the same stream; none of them is counted on the phone.
        events.onVerdict(verdict(VerdictClass.ACCEPTED, "accepted_extension"))
        events.onVerdict(verdict(VerdictClass.REJECTED, "practice_no_extension"))
        events.onVerdict(verdict(VerdictClass.UNJUDGEABLE, "evidence_failed"))
        settle()
        assertEquals(SessionCounts(accepted = 1, rejected = 0, evidenceFailed = 0), controller.state.value.sessionCounts)
        events.onSessionCounts(SessionCounts(accepted = 2, rejected = 1, evidenceFailed = 1))
        assertEquals("This session 2 accepted - 1 rejected", controller.state.value.sessionCountsText)
    }

    // runBlocking, not runTest: runTest reports any earlier test's leaked coroutine exception as its own failure.
    @Test fun `serverHealth is the backend's GET health answer`() = runBlocking {
        val controller = controller()
        backend.healthAnswer = ServerHealth(reachable = true, release = "0123abc")
        assertEquals(ServerHealth(reachable = true, release = "0123abc"), controller.serverHealth())
        backend.healthAnswer = ServerHealth(reachable = false, release = null)
        assertEquals(ServerHealth(reachable = false, release = null), controller.serverHealth())
    }

    @Test fun `a settings change or a recreated Activity never sends a capture's lost twice`() {
        val first = live()
        backend.failNext("lost", apiFailure(DiagnosticStep.HEALTH, FailureKind.Unreachable))
        first.onLeftScreen()
        first.close()
        // The next controller of the same process and server finds the capture pending and joins the delivery.
        val nextBackend = FakeBackend()
        val next = controller(backend = nextBackend)
        next.startVideo()
        settle()

        // One delivery: the first attempt failed and its own retry arrived.
        assertEquals(2, lostCalls().size)
        assertTrue(nextBackend.health.none { it.status == "lost" })
        assertTrue(store.pending().isEmpty())
        // The new capture was attached only after that delivery.
        assertEquals("capture-1", next.state.value.captureId)
        assertTrue("$order", order.lastIndexOf("lost:capture-1") < order.lastIndexOf("attachCapture:0"))
    }

    @Test fun `another server's pending lost is neither sent nor holds up a new capture`() {
        store.add("capture-other", PendingLost(HealthDetail.STOPPED, OTHER_SERVER))
        store.add("capture-unkeyed", PendingLost(HealthDetail.STOPPED, SharedPreferencesPendingLostStore.ANY_SERVER))
        val controller = controller()
        controller.startVideo()
        settle()
        // The unkeyed entry may belong to any server, so this one takes it; the other server's stays.
        assertEquals(listOf("capture-unkeyed"), lostCalls().map { it.captureId })
        assertEquals("capture-1", controller.state.value.captureId)
        assertEquals(setOf("capture-other"), store.pending().keys)
    }

    private fun verdict(verdictClass: VerdictClass, reason: String) =
        MobileVerdictEvent("kick-$reason", 1, "adj-$reason-${verdictClass.name}", 1, reason, verdictClass)

    private data class HealthCall(val captureId: String, val status: String, val detail: String, val pendingAtCall: Set<String>)

    private inner class FakeBackend : ChallengeBackend {
        val calls = mutableListOf<String>()
        val health = mutableListOf<HealthCall>()
        val clientInfoBodies = mutableListOf<ByteArray>()
        val clientInfoCaptures = mutableListOf<String>()
        var clientInfoAnswer = ClientInfoResult.STORED
        val attachEpochs = mutableListOf<Long>()
        private val failures = mutableMapOf<String, ArrayDeque<Exception>>()
        private val holds = mutableMapOf<String, CompletableDeferred<Unit>>()
        private var captures = 0

        fun failNext(method: String, error: Exception) {
            failures.getOrPut(method) { ArrayDeque() }.addLast(error)
        }

        /** The next call of [method] waits until the returned gate is completed. */
        fun hold(method: String): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { holds[method] = it }

        private suspend fun call(method: String, recorded: String = method) {
            calls += method
            order += recorded
            holds.remove(method)?.await()
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
            attachEpochs += epoch.value
            call("attachCapture", "attachCapture:${epoch.value}")
            return "capture-${++captures}"
        }

        override suspend fun reportSourceHealth(captureId: String, status: String, detail: String) {
            health += HealthCall(captureId, status, detail, store.pending().keys)
            if (status == "lost") call("lost", "lost:$captureId") else call("health")
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

        var healthAnswer = ServerHealth(reachable = true, release = null)
        override suspend fun health() = healthAnswer
        val reportReads = mutableListOf<String>()
        var reportAnswer: FetchResult<String> = FetchResult.NotFound
        override suspend fun qualityReport(captureId: String): FetchResult<String> = reportAnswer.also { reportReads += captureId }
        override suspend fun thumbnail(captureId: String, index: Int): FetchResult<ByteArray> =
            FetchResult.Found(byteArrayOf(index.toByte())).also { reportReads += "$captureId/thumb-$index" }
    }

    /** Behaves like MediaMtxSrtPublisher at its listener: start reports CONNECTING, stop reports STOPPED. */
    private class FakePublisher(val listener: PublisherListener, private val order: MutableList<String>) : CanonicalSourcePublisher {
        val calls = mutableListOf<String>()
        var startResult: PublisherResult = PublisherResult.Connecting(SOURCE)
        var startFailure: Exception? = null

        override suspend fun start(epoch: SourceEpoch): PublisherResult {
            calls += "start:${epoch.value}"
            order += "start:${epoch.value}"
            startFailure?.let { throw it }
            if (startResult is PublisherResult.Connecting) {
                listener.onPublisherStatus(PublisherStatus.CONNECTING, "Connecting one SRT source for epoch ${epoch.displayId}...")
            }
            return startResult
        }

        override suspend fun stop() {
            calls += "stop"
            order += "stop"
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

    /**
     * The publisher's Go-live path over the real PreviewCoordinator and M2b's
     * FakeEncoderPort, in MediaMtxSrtPublisher.start's order: the coordinator
     * reads the display rotation and passes streamRequested (I8), then the stream
     * starts. [displayRotation] is what the display reports at the moment of a read.
     */
    private class CoordinatedPublisher(
        private val listener: PublisherListener,
        private val order: MutableList<String>,
        private val displayRotation: () -> Int,
    ) : CanonicalSourcePublisher {
        val port = FakeEncoderPort()
        private val coordinator = PreviewCoordinator(port, listener, null, 90, { _, _ -> }) {
            displayRotation().also { order += "readRotation:$it" }
        }

        init {
            coordinator.displayRotationChanged(displayRotation())
            coordinator.surfaceAvailable(FakeSurface(1), 0, 0)
            coordinator.surfaceChanged(1080, 2400)
        }

        /** What DisplayRotationWatcher reports when the display turns. */
        fun rotated(rotation: Int) = coordinator.displayRotationChanged(rotation)

        override suspend fun start(epoch: SourceEpoch): PublisherResult {
            order += "start:${epoch.value}"
            return when (val gate = coordinator.goLive()) {
                is StreamGate.NotReady -> {
                    listener.onPublisherStatus(PublisherStatus.ERROR, "Video could not start: ${gate.reason}")
                    PublisherResult.Failed(gate.reason)
                }
                is StreamGate.Ready -> {
                    listener.onPublisherStatus(PublisherStatus.CONNECTING, "Connecting one SRT source.")
                    port.startStream()
                    order += "startStream:${port.streamStarts.last().label}"
                    coordinator.streamStarted()
                    PublisherResult.Connecting(SOURCE)
                }
            }
        }

        override suspend fun stop() {
            port.stopStream()
            coordinator.streamStopped()
            listener.onPublisherStatus(PublisherStatus.STOPPED, "Capture stopped.")
        }

        override val isAvailable = true
        override fun attachPreview(view: SurfaceView) = Unit
        override fun releasePreview() = Unit
        override val preparedGeometry: CaptureGeometry? get() = coordinator.preparedGeometry
        override val sensorOrientationDeg: Int? get() = coordinator.sensorOrientationDeg
        override fun reopenCamera() = coordinator.reopenCamera()
        override fun frameCheck(callback: (Bitmap?, Int, Int) -> Unit) = callback(null, 0, 0)
        override fun release() = coordinator.release(port)
    }

    private class MemoryDrills(var stored: BlockSelection = BlockSelection()) : DrillSelectionStore {
        val saved = mutableListOf<BlockSelection>()
        var saveFails = false

        override fun load(): BlockSelection = stored

        override fun save(selection: BlockSelection) {
            check(!saveFails) { "Could not save the drill on this phone" }
            saved += selection
            stored = selection
        }
    }

    private class FakeEvents : ChallengeEvents {
        var sessionId: String? = null
        var stops = 0
        lateinit var onVerdict: (MobileVerdictEvent) -> Unit
        lateinit var onLiveAnalysis: (LiveAnalysisStatus?) -> Unit
        lateinit var onSessionCounts: (SessionCounts) -> Unit

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
            this.onLiveAnalysis = onLiveAnalysis
            this.onSessionCounts = onSessionCounts
        }

        override fun stop() {
            stops++
        }
    }

    private class RecordingSignals(private val now: () -> Long) : AthleteSignals {
        val cues = mutableListOf<FeedbackCue>()
        /** The virtual time of each entry of [cues]. */
        val cueTimes = mutableListOf<Long>()
        val spoken = mutableListOf<String>()
        override fun cue(cue: FeedbackCue) {
            cues += cue
            cueTimes += now()
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
        /** Server keys, as lostServerKey gives them; synthetic values. */
        const val SERVER = "0123456789abcdef"
        const val OTHER_SERVER = "fedcba9876543210"
        /** Longer than every backoff and retry (1 s + 4 s), a multiple of the tick period. */
        const val SETTLE_MS = 30_000L
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
