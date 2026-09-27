package com.vaylith.cleanrepsmobile.session

import com.vaylith.cleanrepsmobile.api.ChallengeApi
import com.vaylith.cleanrepsmobile.api.ChallengeBackend
import com.vaylith.cleanrepsmobile.api.ChallengeEvents
import com.vaylith.cleanrepsmobile.api.FetchResult
import com.vaylith.cleanrepsmobile.api.MobileVerdictEvent
import com.vaylith.cleanrepsmobile.api.ServerHealth
import com.vaylith.cleanrepsmobile.api.SessionCounts
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticStep
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticsLog
import com.vaylith.cleanrepsmobile.diagnostics.StepMessages
import com.vaylith.cleanrepsmobile.feedback.AthleteSignals
import com.vaylith.cleanrepsmobile.feedback.FeedbackCue
import com.vaylith.cleanrepsmobile.feedback.FeedbackPolicy
import com.vaylith.cleanrepsmobile.media.CanonicalSourcePublisher
import com.vaylith.cleanrepsmobile.media.PreviewStatus
import com.vaylith.cleanrepsmobile.media.PublisherListener
import com.vaylith.cleanrepsmobile.media.PublisherResult
import com.vaylith.cleanrepsmobile.media.PublisherStatus
import com.vaylith.cleanrepsmobile.model.AthleteCue
import com.vaylith.cleanrepsmobile.model.BlockSelection
import com.vaylith.cleanrepsmobile.model.CaptureReadiness
import com.vaylith.cleanrepsmobile.model.ClientIdentity
import com.vaylith.cleanrepsmobile.model.DrillSelectionStore
import com.vaylith.cleanrepsmobile.model.LiveAnalysisState
import com.vaylith.cleanrepsmobile.model.LiveAnalysisStatus
import com.vaylith.cleanrepsmobile.model.LiveBlockedReason
import com.vaylith.cleanrepsmobile.model.SourceEpoch
import com.vaylith.cleanrepsmobile.model.manualEvidenceWindow
import com.vaylith.cleanrepsmobile.model.phoneText
import com.vaylith.cleanrepsmobile.ui.PracticeState
import com.vaylith.cleanrepsmobile.ui.PrimaryAction
import com.vaylith.cleanrepsmobile.ui.PrimaryActionInputs
import com.vaylith.cleanrepsmobile.ui.PrimaryActionState
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the screen in its current orientation while video runs (OD-7). The
 * Activity implements it by setting `requestedOrientation` synchronously on the
 * main thread, so the lock is in force when the next statement runs.
 */
fun interface OrientationLock {
    fun setLocked(locked: Boolean)
}

/**
 * The phone's session logic, moved out of `MainActivity` so it runs in JVM tests
 * against fakes: the session, capture, practice-block and health flows over
 * [backend], [events] and the publisher it creates for these connection settings.
 *
 * Public functions and publisher callbacks run on the main thread, the dispatcher
 * of `uiScope`. Only the terminal `lost` report runs on [appScope], so it survives
 * the Activity; [pendingLost] makes it survive process death.
 *
 * Failures are recorded in [diagnostics] under their step, with the cause; the
 * screen shows only the step's owner message, and `/health` carries only a
 * [HealthDetail], because the Cloud OBS overlay renders it on the broadcast.
 */
class SessionController(
    private val backend: ChallengeBackend,
    private val events: ChallengeEvents,
    newPublisher: (PublisherListener) -> CanonicalSourcePublisher,
    private val signals: AthleteSignals,
    private val diagnostics: DiagnosticsLog,
    private val pendingLost: PendingLostStore,
    /** Shared by every controller of the process: [AppScope.lostDeliveries]. */
    private val lostDeliveries: LostDeliveries,
    /** [lostServerKey] of the server [backend] talks to. */
    private val serverKey: String,
    private val drills: DrillSelectionStore,
    private val orientationLock: OrientationLock,
    private val appScope: CoroutineScope,
    uiScope: CoroutineScope,
    /** True on the main thread: a publisher callback made there is handled at once, as `runOnUiThread` did. */
    private val isMainThread: () -> Boolean,
    /** Monotonic milliseconds, as `SystemClock.elapsedRealtime`. */
    private val elapsedRealtime: () -> Long,
    private val wallClock: () -> Instant,
    /** True while the camera screen is at least STARTED. */
    private val isScreenVisible: () -> Boolean,
    private val build: ClientBuild,
    private val sourceId: String,
    initial: AppState,
) {
    private val scope = CoroutineScope(uiScope.coroutineContext + SupervisorJob(uiScope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(initial.copy(selection = drills.load()))
    val state: StateFlow<AppState> = mutableState.asStateFlow()
    private val current: AppState get() = mutableState.value

    /** READY and LOST cues, verdict tones and banners; fed with [elapsedRealtime]. */
    private val feedback = FeedbackPolicy(voiceHints = initial.voiceHints, speakVerdicts = initial.debugSpeakVerdicts)

    /** The capture whose client-info body has been built: each capture gets one body. */
    private var clientInfoCaptureId: String? = null

    /** The last block creation for the drill (OD-8); Start practice waits for it. */
    private var warmBlock: Job? = null

    /** [FeedbackPolicy.tick] every [TICK_MS] while the video is LIVE. */
    private var ticks: Job? = null

    /** Sets [AppState.framingHintDue] once the current unseen spell reaches [FRAMING_HINT_AFTER_MS]. */
    private var unseenSpell: Job? = null

    private var closed = false

    private val listener = object : PublisherListener {
        override fun onPublisherStatus(status: PublisherStatus, detail: String) = onMain { publisherStatus(status, detail) }
        override fun onSourceDiscontinuity(detail: String) = onMain { sourceDiscontinuity() }
        override fun onSafetyRecording(detail: String) = onMain { update { copy(statusDetail = detail) } }
        override fun onPreviewStatus(status: PreviewStatus, detail: String) = onMain { previewStatus(status, detail) }
    }

    /** Replaced with the controller when the connection settings change; released by [close]. */
    val publisher: CanonicalSourcePublisher = newPublisher(listener)

    /** App start (and each new connection): sends every `lost` of this server still pending from an earlier run. */
    fun start() {
        ownPendingLost().forEach { (captureId, lost) -> lostDelivery(captureId, lost.detail) }
    }

    /**
     * Go live. The orientation lock goes on here, at the tap and before anything
     * else, so it is in force when the publisher reads the display rotation.
     */
    fun startVideo() {
        val snapshot = current
        if (snapshot.requestInFlight || snapshot.videoRunning || refusedForCamera(snapshot)) return
        lockOrientation(true)
        update { copy(requestInFlight = true, banner = null, stepError = null) }
        scope.launch {
            try {
                goLive()
            } finally {
                update { copy(requestInFlight = false) }
            }
        }
    }

    /**
     * Restart video, offered when analysis is blocked for a reason only a new
     * capture clears ([AppState.restartOffered]): stop the video (its capture's
     * `lost` is persisted and sent), flush pending `lost` reports, attach a new
     * capture with a new epoch and start again.
     */
    fun restartVideo() {
        val snapshot = current
        if (snapshot.requestInFlight || refusedForCamera(snapshot)) return
        diagnostics.info(DiagnosticStep.PUBLISHER_START, "restart video")
        update { copy(requestInFlight = true, banner = null, stepError = null) }
        scope.launch {
            try {
                if (current.videoRunning) {
                    publisher.stop()
                    pausePracticeQuietly()
                }
                lockOrientation(true)
                goLive()
            } finally {
                update { copy(requestInFlight = false) }
            }
        }
    }

    fun stopVideo() {
        if (!current.videoRunning || current.requestInFlight) return
        update { copy(stepError = null) }
        // Camera shutdown must never wait for an unavailable API.
        scope.launch {
            publisher.stop()
            pausePracticeQuietly()
        }
    }

    /**
     * ON_STOP still stops video: there is no background capture. The capture's
     * `lost` is persisted and handed to [appScope] here, before anything can
     * suspend, so an Activity destroyed next cannot cancel it.
     */
    fun onLeftScreen() {
        if (!current.videoRunning) return
        val capture = current.captureId
        if (capture != null) endCapture(capture, HealthDetail.LEFT_SCREEN)
        update {
            copy(captureId = null, lastCaptureId = capture ?: lastCaptureId, epoch = if (capture != null) epoch.next() else epoch,
                blockReady = false, practiceActive = false, banner = LEFT_SCREEN_BANNER)
        }
        feedback.onPracticeStopped(elapsedRealtime())
        scope.launch {
            publisher.stop()
            pausePracticeQuietly()
        }
    }

    /**
     * OD-6: no confirmation and no gate; the analyzer allows no kick before a
     * confirmed upright head-to-ankle lock, and the phone chirps when tracking
     * begins. The first Start of a block marks it reacquired; later taps resume it.
     */
    fun startPractice() {
        val snapshot = current
        val session = snapshot.sessionId ?: return
        val capture = snapshot.captureId
        if (snapshot.readiness != CaptureReadiness.LIVE || capture == null || snapshot.practiceActive || snapshot.requestInFlight) return
        update { copy(requestInFlight = true, stepError = null) }
        scope.launch {
            var step = DiagnosticStep.CREATE_BLOCK
            try {
                warmBlock?.join()
                val block = current.blockId ?: backend.createBlock(session, current.selection).also { created ->
                    update { copy(blockId = created, practiceStarted = false) }
                }
                val resuming = current.practiceStarted
                step = DiagnosticStep.MARK_REACQUIRED
                backend.markReacquired(session, block)
                step = DiagnosticStep.RESUME
                if (resuming) backend.resumePractice(session, block)
                if (current.captureId == capture && current.readiness == CaptureReadiness.LIVE && isScreenVisible()) {
                    update {
                        copy(blockReady = true, practiceActive = true, practiceStarted = true, feedbackBanner = null,
                            statusDetail = "Practice active. Video continues independently.")
                    }
                    val now = elapsedRealtime()
                    play(if (resuming) feedback.onPracticeResumed(now) else feedback.onPracticeStarted(now))
                } else {
                    step = DiagnosticStep.PAUSE
                    backend.pausePractice(session, block)
                    update { copy(blockReady = false, practiceActive = false, practiceStarted = true) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showFailure(step, "start practice", error, "Video is still available.")
            } finally {
                update { copy(requestInFlight = false) }
            }
        }
    }

    fun pausePractice() {
        if (!current.practiceActive || current.requestInFlight) return
        update { copy(requestInFlight = true, stepError = null) }
        scope.launch {
            try {
                pause()
                play(feedback.onPracticePaused(elapsedRealtime()))
                update { copy(statusDetail = "Practice paused. Video continues.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showFailure(DiagnosticStep.PAUSE, "pause practice", error, "Pause was not confirmed; retry or stop video before resting.")
            } finally {
                update { copy(requestInFlight = false) }
            }
        }
    }

    /**
     * A drill change is refused while practice is active and for a technique that
     * is not judged automatically (OD-5). It is saved on this phone, and while the
     * video is LIVE it gets a new block at once, waiting for Start practice.
     */
    fun selectDrill(selection: BlockSelection) {
        val snapshot = current
        if (selection == snapshot.selection || snapshot.requestInFlight) return
        if (snapshot.practiceActive) {
            update { copy(statusDetail = "Pause practice to change the drill.") }
            return
        }
        if (!selection.technique.autoJudged) {
            update { copy(statusDetail = phoneText(LiveAnalysisState.BLOCKED, LiveBlockedReason.UNSUPPORTED_PRACTICE_TECHNIQUE)) }
            return
        }
        try {
            drills.save(selection)
        } catch (error: Exception) {
            // The drill still applies to this session; only the choice for the next start is not remembered.
            diagnostics.fail(DiagnosticStep.CREATE_BLOCK, "drill choice not saved on this phone", error)
        }
        update { copy(selection = selection, blockId = null, blockReady = false, practiceStarted = false, statusDetail = "Drill selected. Start practice when ready.") }
        ensureWarmBlock()
    }

    fun toggleSpeakVerdicts() {
        update { copy(debugSpeakVerdicts = !debugSpeakVerdicts) }
        feedback.speakVerdicts = current.debugSpeakVerdicts
    }

    /** OD-4: spoken hints with LOST, on by default. */
    fun toggleVoiceHints() {
        update { copy(voiceHints = !voiceHints) }
        feedback.voiceHints = current.voiceHints
    }

    /** "Reopen camera" after a camera error: one new attempt to open the camera and start the preview. */
    fun reopenCamera() = publisher.reopenCamera()

    /** Do not let a lens change race capture creation or alter a live evidence interval. */
    fun switchCamera(): Boolean {
        if (closed || current.videoRunning || current.practiceActive || current.requestInFlight) return false
        return publisher.switchCamera()
    }

    /** `GET /health` of this controller's server (C5), for the reachability chip; it never throws. */
    suspend fun serverHealth(): ServerHealth = backend.health()

    /** C4 `GET /v1/captures/{id}/quality-report` for the Session check card; throws an ApiException on failure. */
    suspend fun qualityReport(captureId: String): FetchResult<String> = backend.qualityReport(captureId)

    /** C4 thumbnail [index] (0, 1 or 2) of that report, as JPEG bytes; the card decodes it in memory only. */
    suspend fun thumbnail(captureId: String, index: Int): FetchResult<ByteArray> = backend.thumbnail(captureId, index)

    /** The last DiagnosticsLog events as lines, oldest first, each redacted again with the current settings. */
    fun diagnosticEventLines(): List<String> = diagnostics.exportLines()

    /** The Diagnostics sheet's Copy text: the same events, redacted again with the current settings. */
    fun diagnosticsExport(): String = diagnostics.exportText()

    fun saveManualMarker() {
        val snapshot = current
        val session = snapshot.sessionId
        val block = snapshot.blockId
        val capture = snapshot.captureId
        val start = snapshot.captureStartedAtElapsedMs
        if (!snapshot.practiceActive || session == null || block == null || capture == null || start == null) return
        scope.launch {
            try {
                val event = backend.logManualAttempt(session, block, capture, sourceId, current.epoch,
                    wallClock().toString(), manualEvidenceWindow(elapsedRealtime() - start))
                update { copy(lastManualKickEventId = event, statusDetail = "Review marker saved. This does not accept a kick.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The diagnostics vocabulary has no step of its own for this practice-block write,
                // and ChallengeApi does not record logManualAttempt, so it is recorded here.
                diagnostics.fail(DiagnosticStep.CREATE_BLOCK, "manual review marker not saved", error)
                update { copy(statusDetail = "Could not save review marker.") }
            }
        }
    }

    /**
     * Ends this controller: the connection settings changed, or the Activity is
     * being destroyed. An attached capture gets its `lost`, the event stream stops
     * and the publisher is released (release reports nothing to the listener).
     */
    fun close() {
        current.captureId?.let { endCapture(it, HealthDetail.STOPPED) }
        update { copy(captureId = null) }
        closed = true
        stopTicks()
        lockOrientation(false)
        events.stop()
        publisher.release()
        scope.cancel()
    }

    /**
     * Steps 2-4 of Go live are the publisher's `start`: it reads the display
     * rotation, passes `streamRequested` for that geometry through the state
     * machine (which re-prepares first if needed, I8) and only then starts the
     * stream. There is no second geometry path here. A start that does not end
     * with the video running releases the orientation lock.
     */
    private suspend fun goLive() {
        var step = DiagnosticStep.CREATE_SESSION
        try {
            val session = current.sessionId ?: backend.createSession(CHALLENGE_ID).also { created ->
                update { copy(sessionId = created) }
                subscribe(created)
            }
            val capture = current.captureId ?: run {
                step = DiagnosticStep.HEALTH
                if (!flushPendingLost()) return
                step = DiagnosticStep.ATTACH_CAPTURE
                backend.attachCapture(session, sourceId, current.epoch)
            }
            update { copy(captureId = capture) }
            step = DiagnosticStep.PUBLISHER_START
            if (!isScreenVisible()) {
                diagnostics.info(step, "not started: the camera screen is not visible")
                update { copy(statusDetail = "Video not started because Clean Reps left the screen.") }
                return
            }
            when (val result = publisher.start(current.epoch)) {
                // Only the transport callback can claim LIVE.
                is PublisherResult.Connecting, is PublisherResult.Live -> {
                    update { copy(streamGeometry = publisher.preparedGeometry?.label) }
                    sendClientInfo(capture)
                }
                is PublisherResult.Blocked -> update { copy(readiness = CaptureReadiness.PUBLISHER_UNAVAILABLE, statusDetail = result.reason) }
                is PublisherResult.Failed -> update { copy(readiness = CaptureReadiness.ERROR, statusDetail = result.reason) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            showFailure(step, "start video", error)
        } finally {
            if (!current.videoRunning) lockOrientation(false)
        }
    }

    /** The preview machine still answers Go live during a camera error (M4b), and the stream would carry no picture. */
    private fun refusedForCamera(snapshot: AppState): Boolean {
        val preview = snapshot.preview as? PreviewStatus.CameraError ?: return false
        diagnostics.info(DiagnosticStep.PUBLISHER_START, "start refused: camera error")
        update { copy(statusDetail = withAdvice(preview.reason, "Tap Reopen camera, then Start video.")) }
        return true
    }

    private fun publisherStatus(status: PublisherStatus, detail: String) {
        val readiness = when (status) {
            PublisherStatus.CONNECTING -> CaptureReadiness.CONNECTING
            PublisherStatus.LIVE -> CaptureReadiness.LIVE
            PublisherStatus.RECONNECTING -> CaptureReadiness.RECONNECTING
            PublisherStatus.STOPPED -> CaptureReadiness.STOPPED
            PublisherStatus.ERROR -> CaptureReadiness.ERROR
        }
        val capture = current.captureId
        val stopped = readiness == CaptureReadiness.STOPPED || readiness == CaptureReadiness.ERROR
        update {
            copy(
                readiness = readiness, statusDetail = detail,
                captureStartedAtElapsedMs = if (stopped) null else if (readiness == CaptureReadiness.LIVE) captureStartedAtElapsedMs ?: elapsedRealtime() else captureStartedAtElapsedMs,
                streamGeometry = if (stopped) null else streamGeometry,
            )
        }
        // The lock follows the transport: on through CONNECTING, LIVE and RECONNECTING, off once the video has stopped.
        lockOrientation(!stopped)
        if (readiness == CaptureReadiness.LIVE) startTicks() else stopTicks()
        watchUnseen(current.liveAnalysis)
        if (stopped) feedback.onPracticeStopped(elapsedRealtime())
        if (capture == null) return
        if (stopped) {
            val cameraFailed = readiness == CaptureReadiness.ERROR && current.preview is PreviewStatus.CameraError
            endCapture(capture, if (cameraFailed) HealthDetail.CAMERA_UNAVAILABLE else HealthDetail.STOPPED)
            // A later restart begins a different encoded time origin.
            update { copy(captureId = null, lastCaptureId = capture, epoch = epoch.next(), blockReady = false, practiceActive = false) }
            scope.launch { pausePracticeQuietly() }
        } else if (readiness == CaptureReadiness.LIVE) {
            reportHealth(capture, SourceHealth.HEALTHY, HealthDetail.LIVE)
            ensureWarmBlock()
        } else {
            reportHealth(capture, SourceHealth.DEGRADED, HealthDetail.RECONNECTING)
        }
    }

    /** A transport disconnect needs a new server capture with a new SourceEpoch. */
    private fun sourceDiscontinuity() {
        val epoch = current.epoch.next()
        val session = current.sessionId
        update {
            copy(epoch = epoch, blockReady = false, practiceActive = false, captureId = null, captureStartedAtElapsedMs = null,
                readiness = CaptureReadiness.RECONNECTING, statusDetail = "Video reconnecting. Resume practice after checking the preview.")
        }
        stopTicks()
        endUnseenSpell()
        play(feedback.onPracticePaused(elapsedRealtime()))
        if (session == null) return
        scope.launch {
            pausePracticeQuietly()
            var step = DiagnosticStep.HEALTH
            try {
                if (!flushPendingLost()) return@launch
                step = DiagnosticStep.ATTACH_CAPTURE
                val capture = backend.attachCapture(session, sourceId, epoch)
                if (current.sessionId == session && current.epoch == epoch && current.videoRunning) {
                    update { copy(captureId = capture) }
                    sendClientInfo(capture)
                    if (current.readiness == CaptureReadiness.LIVE) {
                        reportHealth(capture, SourceHealth.HEALTHY, HealthDetail.LIVE)
                        ensureWarmBlock()
                    } else {
                        reportHealth(capture, SourceHealth.DEGRADED, HealthDetail.RECONNECTING)
                    }
                } else {
                    // The video stopped or reconnected again meanwhile: this capture will never carry video.
                    endCapture(capture, HealthDetail.STOPPED)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showFailure(step, "attach the reconnected video", error, "Stop and restart video.")
            }
        }
    }

    /**
     * P-SEP: a preview status is only shown. It never changes readiness, sends
     * `/health`, pauses practice or clears the capture; a camera error while
     * streaming is a banner.
     */
    private fun previewStatus(status: PreviewStatus, detail: String) {
        update { copy(preview = status, previewDetail = detail) }
    }

    /**
     * OD-8, warm start: the block for the current drill is created as soon as the
     * video is LIVE with a session and a capture, so the analyzer runs (paused)
     * and confirms its lock before Start practice. A new block waits for
     * `markReacquired`, so nothing is judged before Start. Each creation waits for
     * the previous one and then uses the drill selected at that moment.
     */
    private fun ensureWarmBlock() {
        val previous = warmBlock
        warmBlock = scope.launch {
            previous?.join()
            val snapshot = current
            val session = snapshot.sessionId ?: return@launch
            if (snapshot.blockId != null || snapshot.readiness != CaptureReadiness.LIVE || snapshot.captureId == null) return@launch
            val selection = snapshot.selection
            try {
                val block = backend.createBlock(session, selection)
                // A drill changed meanwhile gets its own block from the next creation.
                if (current.sessionId == session && current.selection == selection && current.blockId == null) {
                    update { copy(blockId = block, blockReady = false, practiceStarted = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showFailure(DiagnosticStep.CREATE_BLOCK, "create the block at LIVE", error, "Analysis starts when you tap Start practice.")
            }
        }
    }

    private fun subscribe(session: String) {
        events.start(
            scope,
            session,
            onVerdict = ::verdict,
            onCue = { cue -> update { copy(activeCue = cue) } },
            onCueSafe = { cue -> signals.speak(cue.text) },
            onError = { message -> update { copy(statusDetail = message) } },
            onChallengeTotal = { total -> update { copy(challengeOfficialAcceptedCount = total) } },
            onLiveAnalysis = { status ->
                update { copy(liveAnalysis = status) }
                watchUnseen(status)
                play(feedback.onStatus(status, elapsedRealtime()))
            },
            onSessionCounts = { counts -> update { copy(sessionCounts = counts) } },
        )
    }

    /**
     * FeedbackPolicy decides: accepted and rejected sound, pending and unjudgeable
     * only raise the banner (which the next verdict replaces), and Speak verdicts
     * uses its phrase mapping.
     */
    private fun verdict(verdict: MobileVerdictEvent) {
        val cues = feedback.onVerdict(verdict.verdictClass, verdict.reasonCode, elapsedRealtime())
        update { copy(feedbackBanner = cues.filterIsInstance<FeedbackCue.Banner>().lastOrNull()?.text) }
        play(cues)
    }

    /** Tones and speech go to AthleteFeedback.cue; banners are state, never sound. */
    private fun play(cues: List<FeedbackCue>) {
        cues.filterNot { it is FeedbackCue.Banner }.forEach(signals::cue)
    }

    /** Time-based READY and LOST cues fire without new stream events; a steady state produces none. */
    private fun startTicks() {
        if (ticks?.isActive == true) return
        ticks = scope.launch {
            while (true) {
                delay(TICK_MS)
                play(feedback.tick(elapsedRealtime()))
            }
        }
    }

    private fun stopTicks() {
        ticks?.cancel()
        ticks = null
    }

    /**
     * An unseen spell is a run of `acquiring` and `no_person` statuses while LIVE; any other
     * status, a stale or missing one, or the video leaving LIVE ends it. A head-cut athlete is
     * seen only now and then, so the search rarely lasts 8 s on its own (S9a D1: at most 5.3 s)
     * and alternates with `no_person`, which already reads the whole-body line.
     */
    private fun watchUnseen(status: LiveAnalysisStatus?) {
        val unseen = current.readiness == CaptureReadiness.LIVE && status != null && status.available &&
            (status.state == LiveAnalysisState.ACQUIRING || status.state == LiveAnalysisState.NO_PERSON)
        if (!unseen) return endUnseenSpell()
        if (unseenSpell != null) return
        unseenSpell = scope.launch {
            delay(FRAMING_HINT_AFTER_MS)
            update { copy(framingHintDue = true) }
        }
    }

    private fun endUnseenSpell() {
        unseenSpell?.cancel()
        unseenSpell = null
        if (current.framingHintDue) update { copy(framingHintDue = false) }
    }

    /** Applied synchronously through the port, before anything else runs. */
    private fun lockOrientation(locked: Boolean) {
        if (current.orientationLocked == locked) return
        orientationLock.setLocked(locked)
        update { copy(orientationLocked = locked) }
    }

    /** A block that practice never started still waits for `markReacquired`, so analysis is already paused for it. */
    private suspend fun pause() {
        val session = current.sessionId
        val block = current.blockId
        if (session != null && block != null && current.practiceStarted) backend.pausePractice(session, block)
        update { copy(practiceActive = false, blockReady = false) }
    }

    /** Pauses practice on the way down; a failure is recorded and never holds up the video. */
    private suspend fun pausePracticeQuietly() {
        try {
            pause()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordFailure(DiagnosticStep.PAUSE, "pause after the video stopped failed", error)
        }
    }

    /** Every non-terminal `/health` call. [detail] comes from the allowlist, never from a status text. */
    private fun reportHealth(captureId: String, status: SourceHealth, detail: HealthDetail) {
        scope.launch {
            try {
                backend.reportSourceHealth(captureId, status.wireValue, detail.wireValue)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordFailure(DiagnosticStep.HEALTH, "${status.wireValue} report failed", error)
            }
        }
    }

    /** The terminal report: persisted first, then sent on [appScope]. */
    private fun endCapture(captureId: String, detail: HealthDetail) {
        try {
            pendingLost.add(captureId, PendingLost(detail, serverKey))
        } catch (error: Exception) {
            // It is still sent below; only a process death before it arrives can lose it now.
            diagnostics.fail(DiagnosticStep.HEALTH, "could not persist lost for capture $captureId", error)
        }
        lostDelivery(captureId, detail)
    }

    /** The pending reports that belong to this controller's server. */
    private fun ownPendingLost(): Map<String, PendingLost> = pendingLost.pending().filterValues {
        it.server == serverKey || it.server == SharedPreferencesPendingLostStore.ANY_SERVER
    }

    /** The delivery already running for [captureId] anywhere in the process, or a new one. */
    private fun lostDelivery(captureId: String, detail: HealthDetail): Deferred<Exception?> =
        lostDeliveries.runOrJoin(captureId) { appScope.async { deliverLost(captureId, detail) } }

    /**
     * Runs on [appScope]: up to [LOST_ATTEMPTS] attempts with backoff. Touches only
     * the backend, the store and the log. Null once the server has answered.
     */
    private suspend fun deliverLost(captureId: String, detail: HealthDetail): Exception? {
        var failure: Exception? = null
        for (attempt in 1..LOST_ATTEMPTS) {
            try {
                backend.reportSourceHealth(captureId, SourceHealth.LOST.wireValue, detail.wireValue)
                diagnostics.info(DiagnosticStep.HEALTH, "lost delivered for capture $captureId")
                forgetLost(captureId)
                return null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error
                recordFailure(DiagnosticStep.HEALTH, "lost attempt $attempt for capture $captureId failed", error)
                if (refused(error)) {
                    // The server answered and refused (for example an unknown capture): no retry can succeed.
                    diagnostics.fail(DiagnosticStep.HEALTH, "lost for capture $captureId refused; no longer pending")
                    forgetLost(captureId)
                    return null
                }
                if (attempt < LOST_ATTEMPTS) delay(RETRY_BACKOFF_MS[attempt - 1])
            }
        }
        diagnostics.fail(DiagnosticStep.HEALTH, "lost for capture $captureId still pending after $LOST_ATTEMPTS attempts")
        return failure
    }

    private fun forgetLost(captureId: String) {
        try {
            pendingLost.remove(captureId)
        } catch (error: Exception) {
            // Harmless: it is sent again at the next flush.
            diagnostics.fail(DiagnosticStep.HEALTH, "could not clear pending lost for capture $captureId", error)
        }
    }

    /**
     * Before any new capture is attached: sends every pending `lost` of this
     * server, joining a delivery already under way anywhere in the process.
     * False, with the reason shown, while any is still pending; no second
     * capture is attached then. Another server's reports neither go here nor
     * hold anything up.
     */
    private suspend fun flushPendingLost(): Boolean {
        val pending = ownPendingLost()
        if (pending.isEmpty()) return true
        update { copy(statusDetail = "Reporting the last video as ended...") }
        var failure: Exception? = null
        pending.forEach { (captureId, lost) -> lostDelivery(captureId, lost.detail).await()?.let { failure = it } }
        if (ownPendingLost().isEmpty()) return true
        val reason = failure?.let { ownerMessage(DiagnosticStep.HEALTH, it) }
        val message = withAdvice(reason ?: "The last video is still open on the server.", "No new video starts until it is reported as ended; try again.")
        update { copy(statusDetail = message, stepError = message) }
        return false
    }

    /**
     * C2: builds this capture's client-info body once and sends it best-effort.
     * Retries re-send the same bytes, because the server answers 409 to a
     * different body. A failure is recorded and never holds up the video.
     */
    private fun sendClientInfo(captureId: String) {
        if (clientInfoCaptureId == captureId) return
        clientInfoCaptureId = captureId
        val body = clientInfoBody() ?: return
        scope.launch {
            for (attempt in 1..CLIENT_INFO_ATTEMPTS) {
                try {
                    // Stored, unchanged or a 409 conflict: the server has answered, and ChallengeApi records which.
                    backend.clientInfo(captureId, body)
                    return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    recordFailure(DiagnosticStep.CLIENT_INFO, "client info attempt $attempt failed", error)
                    if (refused(error) || attempt == CLIENT_INFO_ATTEMPTS) return@launch
                    delay(RETRY_BACKOFF_MS[attempt - 1])
                }
            }
        }
    }

    /** From the build, the device and the geometry the encoder is prepared with. */
    private fun clientInfoBody(): ByteArray? {
        val geometry = publisher.preparedGeometry
        if (geometry == null) {
            diagnostics.info(DiagnosticStep.CLIENT_INFO, "not sent: the encoder geometry is unknown")
            return null
        }
        return try {
            ClientIdentity(
                appVersionName = build.appVersionName,
                appVersionCode = build.appVersionCode,
                appGitSha = build.appGitSha,
                deviceManufacturer = build.deviceManufacturer,
                deviceModel = build.deviceModel,
                androidSdkInt = build.androidSdkInt,
                cameraSensorOrientationDeg = publisher.sensorOrientationDeg ?: geometry.sensorOrientationDeg,
                displayRotationDeg = geometry.displayRotationDeg,
                captureOrientation = geometry.orientation,
                encodedWidth = geometry.encodedWidth,
                encodedHeight = geometry.encodedHeight,
                sentAt = wallClock(),
            ).body()
        } catch (error: Exception) {
            diagnostics.fail(DiagnosticStep.CLIENT_INFO, "client info not built", error)
            null
        }
    }

    /**
     * Records [error] under [step] and shows the step's owner message, also as the error banner
     * ([AppState.stepError]); exception text reaches only the log.
     */
    private fun showFailure(step: DiagnosticStep, action: String, error: Exception, advice: String? = null) {
        recordFailure(step, "$action failed", error)
        val message = withAdvice(ownerMessage(step, error), advice)
        update { copy(statusDetail = message, stepError = message) }
    }

    /** ChallengeApi records its own failures under their step; anything else is recorded here, with its cause. */
    private fun recordFailure(step: DiagnosticStep, message: String, error: Exception) {
        if (error !is ChallengeApi.ApiException) diagnostics.fail(step, message, error)
    }

    private fun ownerMessage(step: DiagnosticStep, error: Exception): String = when (error) {
        is ChallengeApi.ApiException -> StepMessages.message(error.step ?: step, error.kind)
        else -> StepMessages.forError(step, error)
    }

    private fun withAdvice(message: String, advice: String?): String = when {
        advice == null -> message
        message.endsWith('.') || message.endsWith('?') || message.endsWith('!') -> "$message $advice"
        else -> "$message. $advice"
    }

    /** The server answered with a refusal that a retry cannot change. */
    private fun refused(error: Exception): Boolean {
        val code = (error as? ChallengeApi.ApiException)?.statusCode ?: return false
        return code in 400..499 && code != 408 && code != 429
    }

    /**
     * A callback made on the main thread (the publisher's start and stop) is
     * handled before the call returns, so later statements see its effect; one
     * from a RootEncoder thread is posted to the main thread.
     */
    private fun onMain(block: () -> Unit) {
        if (closed) return
        if (isMainThread()) {
            block()
        } else {
            scope.launch { if (!closed) block() }
        }
    }

    private inline fun update(change: AppState.() -> AppState) {
        mutableState.value = current.change()
    }

    private enum class SourceHealth(val wireValue: String) { HEALTHY("healthy"), DEGRADED("degraded"), LOST("lost") }

    companion object {
        const val CHALLENGE_ID = "million-kicks-launch"
        const val LOST_ATTEMPTS = 3
        const val CLIENT_INFO_ATTEMPTS = 3

        /** The waits before the second and the third attempt. */
        val RETRY_BACKOFF_MS = listOf(1_000L, 4_000L)

        /** The FeedbackPolicy tick period while the video is LIVE. */
        const val TICK_MS = 250L

        /** How long an unseen spell lasts before `acquiring` reads the whole-body line. */
        const val FRAMING_HINT_AFTER_MS = 8_000L

        /** After a stop the primary button reads Restart video (PrimaryActionState), so the banner names it. */
        const val LEFT_SCREEN_BANNER = "Video stopped because Clean Reps left the screen. Tap Restart video."
    }
}

/**
 * The only `detail` values the phone sends with `/health`. The Cloud OBS overlay
 * renders the detail on the broadcast (clean-reps `apps/server/src/server.ts`),
 * so step names, causes and exception text go only to the DiagnosticsLog.
 */
enum class HealthDetail(val wireValue: String) {
    LIVE("live"),
    RECONNECTING("reconnecting"),
    STOPPED("stopped"),
    LEFT_SCREEN("left the screen"),
    CAMERA_UNAVAILABLE("camera unavailable");

    companion object {
        fun fromWire(value: String?): HealthDetail? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Build and device values for the client-info body; the Activity reads them from BuildConfig and `android.os.Build`. */
data class ClientBuild(
    val appVersionName: String,
    val appVersionCode: Int,
    val appGitSha: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidSdkInt: Int,
)

private val VIDEO_RUNNING = setOf(CaptureReadiness.CONNECTING, CaptureReadiness.LIVE, CaptureReadiness.RECONNECTING)

data class AppState(
    val sessionId: String? = null,
    val blockId: String? = null,
    val blockReady: Boolean = false,
    val practiceActive: Boolean = false,
    /** Start practice has been tapped for the current block, so the next tap resumes it. */
    val practiceStarted: Boolean = false,
    val captureId: String? = null,
    /** The capture of the last video that stopped; the Session check card shows its quality report. */
    val lastCaptureId: String? = null,
    val captureStartedAtElapsedMs: Long? = null,
    val lastManualKickEventId: String? = null,
    val selection: BlockSelection = BlockSelection(),
    val epoch: SourceEpoch = SourceEpoch(),
    val readiness: CaptureReadiness = CaptureReadiness.NOT_CONFIGURED,
    val statusDetail: String = "Configure private API and real canonical publisher before official capture.",
    val debugSpeakVerdicts: Boolean = false,
    /** OD-4: speak the reason together with LOST. */
    val voiceHints: Boolean = FeedbackPolicy.VOICE_HINTS_DEFAULT,
    val activeCue: AthleteCue? = null,
    val challengeOfficialAcceptedCount: Long? = null,
    /** A server or video request started from the screen has not finished. */
    val requestInFlight: Boolean = false,
    /** The last preview status and its owner text; shown only (P-SEP). */
    val preview: PreviewStatus? = null,
    val previewDetail: String = "",
    /** Why video stopped, kept until the next Start video. */
    val banner: String? = null,
    /** FeedbackPolicy's banner for the last verdict, e.g. an unjudgeable one. */
    val feedbackBanner: String? = null,
    /**
     * The owner message of the last failed step (M3a, naming the step), shown as the error
     * banner until the next screen action (Go live, Restart video, Start practice, Pause, Stop video) starts.
     */
    val stepError: String? = null,
    /** OD-7: the screen orientation is locked, from the Go-live tap until the video stops. */
    val orientationLocked: Boolean = false,
    /** The label of the geometry the stream was started with, e.g. "landscape 1280x720". */
    val streamGeometry: String? = null,
    val liveAnalysis: LiveAnalysisStatus? = null,
    /**
     * The athlete has gone unseen for [SessionController.FRAMING_HINT_AFTER_MS] while LIVE, so an
     * `acquiring` status reads the whole-body line in the rail hint (PrimaryActionState).
     */
    val framingHintDue: Boolean = false,
    val sessionCounts: SessionCounts? = null,
) {
    val videoRunning: Boolean get() = readiness in VIDEO_RUNNING

    val practice: PracticeState get() = PracticeState.of(practiceActive, practiceStarted)

    /** A camera error (also while streaming) or a rotation that waits until video stops. */
    val previewBanner: String?
        get() = when (preview) {
            is PreviewStatus.CameraError, is PreviewStatus.RotationPending -> previewDetail
            else -> null
        }

    /** The LIVE pill, e.g. "LIVE - landscape 1280x720". */
    val livePill: String? get() = streamGeometry?.takeIf { readiness == CaptureReadiness.LIVE }?.let { "LIVE - $it" }

    val sessionCountsText: String? get() = sessionCounts?.let { "This session ${it.accepted} accepted - ${it.rejected} rejected" }

    /**
     * Restart video is offered while LIVE when analysis is blocked for a reason
     * only a new capture clears (`waiting_for_new_capture_epoch`,
     * `worker_retry_limit`, `ambiguous_active_sessions`); the rule is M3c's.
     */
    val restartOffered: Boolean
        get() = PrimaryActionState.from(
            PrimaryActionInputs(
                readiness = readiness,
                practice = practice,
                inFlight = requestInFlight,
                configured = true,
                permission = true,
                captureAttached = captureId != null,
                liveAnalysis = liveAnalysis,
            ),
        ).hintAction == PrimaryAction.RESTART_VIDEO
}
