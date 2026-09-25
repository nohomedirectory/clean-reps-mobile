package com.vaylith.cleanrepsmobile.session

import com.vaylith.cleanrepsmobile.api.ChallengeApi
import com.vaylith.cleanrepsmobile.api.ChallengeBackend
import com.vaylith.cleanrepsmobile.api.ChallengeEvents
import com.vaylith.cleanrepsmobile.api.MobileVerdictEvent
import com.vaylith.cleanrepsmobile.api.SessionCounts
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticStep
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticsLog
import com.vaylith.cleanrepsmobile.diagnostics.StepMessages
import com.vaylith.cleanrepsmobile.feedback.AthleteSignals
import com.vaylith.cleanrepsmobile.feedback.CueTones
import com.vaylith.cleanrepsmobile.media.CanonicalSourcePublisher
import com.vaylith.cleanrepsmobile.media.PreviewStatus
import com.vaylith.cleanrepsmobile.media.PublisherListener
import com.vaylith.cleanrepsmobile.media.PublisherResult
import com.vaylith.cleanrepsmobile.media.PublisherStatus
import com.vaylith.cleanrepsmobile.model.AthleteCue
import com.vaylith.cleanrepsmobile.model.BlockSelection
import com.vaylith.cleanrepsmobile.model.CaptureReadiness
import com.vaylith.cleanrepsmobile.model.ClientIdentity
import com.vaylith.cleanrepsmobile.model.LiveAnalysisStatus
import com.vaylith.cleanrepsmobile.model.SourceEpoch
import com.vaylith.cleanrepsmobile.model.manualEvidenceWindow
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
    private val appScope: CoroutineScope,
    uiScope: CoroutineScope,
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
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<AppState> = mutableState.asStateFlow()
    private val current: AppState get() = mutableState.value

    /** Main thread only. A capture's delivery answers null once the server has answered, else its last failure. */
    private val lostDeliveries = mutableMapOf<String, Deferred<Exception?>>()

    /** The capture whose client-info body has been built: each capture gets one body. */
    private var clientInfoCaptureId: String? = null

    private val listener = object : PublisherListener {
        override fun onPublisherStatus(status: PublisherStatus, detail: String) = onMain { publisherStatus(status, detail) }
        override fun onSourceDiscontinuity(detail: String) = onMain { sourceDiscontinuity() }
        override fun onSafetyRecording(detail: String) = onMain { update { copy(statusDetail = detail) } }
        override fun onPreviewStatus(status: PreviewStatus, detail: String) = onMain { previewStatus(status, detail) }
    }

    /** Replaced with the controller when the connection settings change; released by [close]. */
    val publisher: CanonicalSourcePublisher = newPublisher(listener)

    /** App start (and each new connection): sends every `lost` still pending from an earlier run. */
    fun start() {
        pendingLost.pending().forEach { (captureId, detail) -> lostDelivery(captureId, detail) }
    }

    fun startVideo() {
        val snapshot = current
        if (snapshot.requestInFlight || snapshot.videoRunning) return
        val preview = snapshot.preview
        if (preview is PreviewStatus.CameraError) {
            // The preview machine still answers Go live during a camera error (M4b), and the stream would carry no picture.
            diagnostics.info(DiagnosticStep.PUBLISHER_START, "start refused: camera error")
            update { copy(statusDetail = withAdvice(preview.reason, "Tap Reopen camera, then Start video.")) }
            return
        }
        update { copy(requestInFlight = true, banner = null) }
        scope.launch {
            var step = DiagnosticStep.CREATE_SESSION
            try {
                val session = current.sessionId ?: backend.createSession(CHALLENGE_ID).also { created ->
                    update { copy(sessionId = created) }
                    subscribe(created)
                }
                val capture = current.captureId ?: run {
                    step = DiagnosticStep.HEALTH
                    if (!flushPendingLost()) return@launch
                    step = DiagnosticStep.ATTACH_CAPTURE
                    backend.attachCapture(session, sourceId, current.epoch)
                }
                update { copy(captureId = capture) }
                step = DiagnosticStep.PUBLISHER_START
                if (!isScreenVisible()) {
                    diagnostics.info(step, "not started: the camera screen is not visible")
                    update { copy(statusDetail = "Video not started because Clean Reps left the screen.") }
                    return@launch
                }
                when (val result = publisher.start(current.epoch)) {
                    // Only the transport callback can claim LIVE; the stream's geometry is prepared now (I8).
                    is PublisherResult.Connecting, is PublisherResult.Live -> sendClientInfo(capture)
                    is PublisherResult.Blocked -> update { copy(readiness = CaptureReadiness.PUBLISHER_UNAVAILABLE, statusDetail = result.reason) }
                    is PublisherResult.Failed -> update { copy(readiness = CaptureReadiness.ERROR, statusDetail = result.reason) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showFailure(step, "start video", error)
            } finally {
                update { copy(requestInFlight = false) }
            }
        }
    }

    fun stopVideo() {
        if (!current.videoRunning || current.requestInFlight) return
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
            copy(captureId = null, epoch = if (capture != null) epoch.next() else epoch, blockReady = false, practiceActive = false, banner = LEFT_SCREEN_BANNER)
        }
        scope.launch {
            publisher.stop()
            pausePracticeQuietly()
        }
    }

    fun startPractice() {
        val snapshot = current
        val session = snapshot.sessionId ?: return
        val capture = snapshot.captureId
        if (snapshot.readiness != CaptureReadiness.LIVE || capture == null || snapshot.practiceActive || snapshot.requestInFlight) return
        update { copy(requestInFlight = true) }
        scope.launch {
            var step = DiagnosticStep.CREATE_BLOCK
            try {
                val previousBlock = current.blockId
                val block = previousBlock ?: backend.createBlock(session, current.selection)
                update { copy(blockId = block) }
                step = DiagnosticStep.MARK_REACQUIRED
                backend.markReacquired(session, block)
                step = DiagnosticStep.RESUME
                if (previousBlock != null) backend.resumePractice(session, block)
                if (current.captureId == capture && current.readiness == CaptureReadiness.LIVE && isScreenVisible()) {
                    update { copy(blockReady = true, practiceActive = true, statusDetail = "Practice active. Video continues independently.") }
                } else {
                    step = DiagnosticStep.PAUSE
                    backend.pausePractice(session, block)
                    update { copy(blockReady = false, practiceActive = false) }
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
        update { copy(requestInFlight = true) }
        scope.launch {
            try {
                pause()
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

    fun selectDrill(selection: BlockSelection) {
        val snapshot = current
        if (!snapshot.practiceActive && !snapshot.requestInFlight && selection != snapshot.selection) {
            update { copy(selection = selection, blockId = null, blockReady = false, statusDetail = "Drill selected. Start practice when ready.") }
        }
    }

    fun toggleSpeakVerdicts() = update { copy(debugSpeakVerdicts = !debugSpeakVerdicts) }

    /** "Reopen camera" after a camera error: one new attempt to open the camera and start the preview. */
    fun reopenCamera() = publisher.reopenCamera()

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
        events.stop()
        publisher.release()
        scope.cancel()
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
            )
        }
        if (capture == null) return
        if (stopped) {
            val cameraFailed = readiness == CaptureReadiness.ERROR && current.preview is PreviewStatus.CameraError
            endCapture(capture, if (cameraFailed) HealthDetail.CAMERA_UNAVAILABLE else HealthDetail.STOPPED)
            // A later restart begins a different encoded time origin.
            update { copy(captureId = null, epoch = epoch.next(), blockReady = false, practiceActive = false) }
            scope.launch { pausePracticeQuietly() }
        } else if (readiness == CaptureReadiness.LIVE) {
            reportHealth(capture, SourceHealth.HEALTHY, HealthDetail.LIVE)
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
                    if (current.readiness == CaptureReadiness.LIVE) reportHealth(capture, SourceHealth.HEALTHY, HealthDetail.LIVE)
                    else reportHealth(capture, SourceHealth.DEGRADED, HealthDetail.RECONNECTING)
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

    private fun subscribe(session: String) {
        events.start(
            scope,
            session,
            onVerdict = ::verdict,
            onCue = { cue -> update { copy(activeCue = cue) } },
            onCueSafe = { cue -> signals.speak(cue.text) },
            onError = { message -> update { copy(statusDetail = message) } },
            onChallengeTotal = { total -> update { copy(challengeOfficialAcceptedCount = total) } },
            onLiveAnalysis = { status -> update { copy(liveAnalysis = status) } },
            onSessionCounts = { counts -> update { copy(sessionCounts = counts) } },
        )
    }

    /** Until FeedbackPolicy is wired (M6b): accepted and rejected sound, pending and unjudgeable are silent. */
    private fun verdict(verdict: MobileVerdictEvent) {
        CueTones.forVerdict(verdict.verdictClass)?.let(signals::cue)
        if (current.debugSpeakVerdicts) signals.speak(verdict.reasonCode)
    }

    private suspend fun pause() {
        val session = current.sessionId
        val block = current.blockId
        if (session != null && block != null) backend.pausePractice(session, block)
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
            pendingLost.add(captureId, detail)
        } catch (error: Exception) {
            // It is still sent below; only a process death before it arrives can lose it now.
            diagnostics.fail(DiagnosticStep.HEALTH, "could not persist lost for capture $captureId", error)
        }
        lostDelivery(captureId, detail)
    }

    /** The delivery already running for [captureId], or a new one. */
    private fun lostDelivery(captureId: String, detail: HealthDetail): Deferred<Exception?> {
        lostDeliveries.values.removeAll { it.isCompleted }
        return lostDeliveries[captureId]
            ?: appScope.async { deliverLost(captureId, detail) }.also { lostDeliveries[captureId] = it }
    }

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
     * Before any new capture is attached: sends every pending `lost`, joining a
     * delivery already under way. False, with the reason shown, while any is
     * still pending; no second capture is attached then.
     */
    private suspend fun flushPendingLost(): Boolean {
        val pending = pendingLost.pending()
        if (pending.isEmpty()) return true
        update { copy(statusDetail = "Reporting the last video as ended...") }
        var failure: Exception? = null
        pending.forEach { (captureId, detail) -> lostDelivery(captureId, detail).await()?.let { failure = it } }
        if (pendingLost.pending().isEmpty()) return true
        val reason = failure?.let { ownerMessage(DiagnosticStep.HEALTH, it) }
        update { copy(statusDetail = withAdvice(reason ?: "The last video is still open on the server.", "No new video starts until it is reported as ended; try again.")) }
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

    /** Records [error] under [step] and shows the step's owner message; exception text reaches only the log. */
    private fun showFailure(step: DiagnosticStep, action: String, error: Exception, advice: String? = null) {
        recordFailure(step, "$action failed", error)
        update { copy(statusDetail = withAdvice(ownerMessage(step, error), advice)) }
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

    private fun onMain(block: () -> Unit) {
        scope.launch { block() }
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

        const val LEFT_SCREEN_BANNER = "Video stopped because Clean Reps left the screen. Tap Go live."
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
    val captureId: String? = null,
    val captureStartedAtElapsedMs: Long? = null,
    val lastManualKickEventId: String? = null,
    val selection: BlockSelection = BlockSelection(),
    val epoch: SourceEpoch = SourceEpoch(),
    val readiness: CaptureReadiness = CaptureReadiness.NOT_CONFIGURED,
    val statusDetail: String = "Configure private API and real canonical publisher before official capture.",
    val debugSpeakVerdicts: Boolean = false,
    val activeCue: AthleteCue? = null,
    val challengeOfficialAcceptedCount: Long? = null,
    /** A server or video request started from the screen has not finished. */
    val requestInFlight: Boolean = false,
    /** The last preview status and its owner text; shown only (P-SEP). */
    val preview: PreviewStatus? = null,
    val previewDetail: String = "",
    /** Why video stopped, kept until the next Start video. */
    val banner: String? = null,
    val liveAnalysis: LiveAnalysisStatus? = null,
    val sessionCounts: SessionCounts? = null,
) {
    val videoRunning: Boolean get() = readiness in VIDEO_RUNNING

    /** A camera error (also while streaming) or a rotation that waits until video stops. */
    val previewBanner: String?
        get() = when (preview) {
            is PreviewStatus.CameraError, is PreviewStatus.RotationPending -> previewDetail
            else -> null
        }
}
