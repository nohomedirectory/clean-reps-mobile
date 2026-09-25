package com.vaylith.cleanrepsmobile.media

import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticOutcome
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticStep
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticsLog
import com.vaylith.cleanrepsmobile.diagnostics.Redaction
import com.vaylith.cleanrepsmobile.model.CaptureOrientation
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** The publisher's preview logic over M2b's FakeEncoderPort, which throws where RootEncoder 2.7.0 throws. */
class PreviewCoordinatorTest {
    private class RecordingListener : PublisherListener {
        val publisherStatuses = mutableListOf<PublisherStatus>()
        val previewStatuses = mutableListOf<Pair<PreviewStatus, String>>()

        override fun onPublisherStatus(status: PublisherStatus, detail: String) {
            publisherStatuses += status
        }
        override fun onSourceDiscontinuity(detail: String) = fail("the preview never reports a discontinuity")
        override fun onSafetyRecording(detail: String) = fail("the preview never reports recording")
        override fun onPreviewStatus(status: PreviewStatus, detail: String) {
            previewStatuses += status to detail
        }
    }

    /** The coordinator's delayed actions; they run only when a test runs them. */
    private class ManualScheduler {
        val pending = mutableListOf<Pair<Long, () -> Unit>>()

        fun schedule(delayMs: Long, action: () -> Unit) {
            pending += delayMs to action
        }

        fun runAll() {
            val due = pending.toList()
            pending.clear()
            due.forEach { it.second() }
        }
    }

    private val port = FakeEncoderPort()
    private val listener = RecordingListener()
    private val log = DiagnosticsLog(clock = { 0L }, sink = { _, _, _ -> })
    private val scheduler = ManualScheduler()
    private var rotation: Int? = 1
    private val surface = FakeSurface(1)

    private fun coordinator(sensor: Int? = 90, encoder: EncoderPort<FakeSurface> = port, diagnostics: DiagnosticsLog = log) =
        PreviewCoordinator(encoder, listener, diagnostics, sensor, scheduler::schedule) { rotation }

    private fun statuses() = listener.previewStatuses.map { it.first }

    /** Attach in the current rotation, then the view's surface is created and sized. */
    private fun PreviewCoordinator<FakeSurface>.attach(width: Int = 2400, height: Int = 1080) = apply {
        displayRotationChanged(rotation)
        surfaceAvailable(surface, 0, 0)
        surfaceChanged(width, height)
    }

    private fun PreviewCoordinator<FakeSurface>.goLiveAndStream(): CaptureGeometry {
        val gate = goLive()
        assertTrue("$gate", gate is StreamGate.Ready)
        port.startStream()
        streamStarted()
        return (gate as StreamGate.Ready).geometry
    }

    @After fun noPortViolationsAndNoTransportStatuses() {
        assertEquals(emptyList<String>(), port.violations)
        // P-SEP: preview events never reach the transport channel.
        assertEquals(emptyList<PublisherStatus>(), listener.publisherStatuses)
    }

    @Test fun `landscape attach prepares rotation 0 and starts the preview on the first sized surface`() {
        val coordinator = coordinator()
        coordinator.displayRotationChanged(rotation)
        coordinator.surfaceAvailable(surface, 0, 0)
        assertEquals(listOf("prepareVideo(0)", "prepareAudio"), port.calls)

        coordinator.surfaceChanged(2400, 1080)
        assertEquals("startPreview(surface#1,2400x1080)", port.calls.last())
        assertEquals(listOf(PreviewStatus.Starting, PreviewStatus.Ready), statuses())
        assertEquals("landscape 1280x720", coordinator.preparedGeometry?.label)
        assertEquals(90, coordinator.sensorOrientationDeg)
        assertEquals("Camera preview ready (landscape 1280x720).", listener.previewStatuses.last().second)
        assertTrue(log.entries().any { it.step == DiagnosticStep.PREVIEW_START && it.outcome == DiagnosticOutcome.OK })
    }

    @Test fun `portrait attach prepares rotation 90 for an upright 720x1280 frame`() {
        rotation = 0
        val coordinator = coordinator().attach(width = 1080, height = 2400)
        assertEquals("prepareVideo(90)", port.calls.first())
        assertEquals(CaptureOrientation.PORTRAIT, coordinator.preparedGeometry?.orientation)
        assertEquals(720 to 1280, coordinator.preparedGeometry?.let { it.encodedWidth to it.encodedHeight })
    }

    @Test fun `a rotation while idle re-prepares with the new upright geometry`() {
        val coordinator = coordinator().attach()
        port.calls.clear()
        coordinator.displayRotationChanged(0)
        assertEquals(listOf("stopPreview", "prepareVideo(90)", "startPreview(surface#1,2400x1080)"), port.calls)
        port.calls.clear()
        // Landscape to reverse landscape: same size, different rotation argument.
        coordinator.displayRotationChanged(3)
        assertEquals(listOf("stopPreview", "prepareVideo(180)", "startPreview(surface#1,2400x1080)"), port.calls)
        assertEquals(PreviewStatus.Ready, statuses().last())
    }

    @Test fun `Go live reads the rotation now and re-prepares before the stream may start (I8)`() {
        val coordinator = coordinator().attach()
        // The phone was turned to portrait but no rotation callback has arrived yet.
        rotation = 0
        port.calls.clear()
        val streamed = coordinator.goLiveAndStream()

        assertEquals("portrait 720x1280", streamed.label)
        assertEquals(listOf("stopPreview", "prepareVideo(90)", "startPreview(surface#1,2400x1080)", "startStream"), port.calls)
        assertEquals(listOf(streamed), port.streamStarts)
    }

    @Test fun `a rotation while streaming waits until the video stops`() {
        val coordinator = coordinator().attach()
        coordinator.goLiveAndStream()
        port.calls.clear()

        coordinator.displayRotationChanged(0)
        assertEquals(emptyList<String>(), port.calls)
        val (pending, detail) = listener.previewStatuses.last()
        assertEquals(PreviewStatus.RotationPending(CaptureGeometry.forDisplayRotation(0, 90).getOrThrow()), pending)
        assertEquals("Phone turned — video stays landscape. Stop video to switch.", detail)
        assertEquals("landscape 1280x720", coordinator.preparedGeometry?.label)

        port.stopStream()
        coordinator.streamStopped()
        assertEquals(listOf("stopStream", "stopPreview", "prepareVideo(90)", "startPreview(surface#1,2400x1080)"), port.calls)
        assertEquals("portrait 720x1280", coordinator.preparedGeometry?.label)
    }

    @Test fun `the preview comes back after the surface is lost while streaming, and the stream keeps running`() {
        val coordinator = coordinator().attach()
        coordinator.goLiveAndStream()
        port.calls.clear()

        coordinator.surfaceLost()
        assertEquals(listOf("stopPreview"), port.calls)
        assertTrue(port.isStreaming)
        assertEquals(PreviewStatus.Lost, statuses().last())

        coordinator.surfaceAvailable(surface, 0, 0)
        coordinator.surfaceChanged(2400, 1080)
        assertEquals("startPreview(surface#1,2400x1080)", port.calls.last())
        assertEquals(PreviewStatus.Ready, statuses().last())
        assertTrue(port.isStreaming)
        assertFalse(port.calls.any { it.startsWith("prepareVideo") })
    }

    @Test fun `camera failures are preview statuses, logged with their exception class`() {
        port.startPreviewFailure = "Camera in use by another app"
        coordinator().attach()
        val (error, detail) = listener.previewStatuses.last()
        assertTrue("$error", error is PreviewStatus.CameraError)
        // M4c: the exception's own text goes only to the redacting log, never to a listener string.
        assertEquals("Camera preview failed (RuntimeException)", detail)
        val entry = log.entries().single { it.outcome == DiagnosticOutcome.FAIL }
        assertEquals(DiagnosticStep.PREVIEW_START, entry.step)
        assertEquals("RuntimeException", entry.errorClass)
        assertTrue(entry.redactedMessage, entry.redactedMessage.contains("Camera in use by another app"))
    }

    @Test fun `an unsupported camera size is logged as a cameraOpen failure`() {
        port.prepareFailure = "Unsupported resolution: 1280x720"
        val coordinator = coordinator()
        coordinator.displayRotationChanged(rotation)
        assertEquals(PreviewStatus.CameraError("This camera cannot provide 1280x720 video"), statuses().single())
        assertEquals("This camera cannot provide 1280x720 video", listener.previewStatuses.single().second)
        val entry = log.entries().single { it.outcome == DiagnosticOutcome.FAIL }
        assertEquals(DiagnosticStep.CAMERA_OPEN, entry.step)
        assertEquals("IllegalArgumentException", entry.errorClass)
        assertTrue(entry.redactedMessage.contains("Unsupported resolution: 1280x720"))
        assertEquals(StreamGate.NotReady("This camera cannot provide 1280x720 video"), coordinator.goLive())
    }

    @Test fun `an encoder that refuses portrait gets the exact message and landscape stays available`() {
        port.encoderRefuses = { it.orientation == CaptureOrientation.PORTRAIT }
        rotation = 0
        val coordinator = coordinator().attach(width = 1080, height = 2400)
        val portraitRefused = "Portrait video not supported on this phone - use landscape"
        assertEquals(PreviewStatus.CameraError(portraitRefused), statuses().last())
        assertEquals(portraitRefused, listener.previewStatuses.last().second)
        assertFalse(port.isOnPreview)
        assertEquals(StreamGate.NotReady(portraitRefused), coordinator.goLive())
        assertTrue(log.entries().any { it.step == DiagnosticStep.CAMERA_OPEN && it.redactedMessage == "prepareVideo portrait 720x1280 returned false" })

        // Turning to landscape needs no Reopen camera: that prepare succeeds and the preview starts.
        rotation = 1
        coordinator.displayRotationChanged(1)
        assertEquals("landscape 1280x720", coordinator.preparedGeometry?.label)
        assertEquals(PreviewStatus.Ready, statuses().last())
        assertTrue(port.isOnPreview)
        assertEquals("landscape 1280x720", coordinator.goLiveAndStream().label)
    }

    @Test fun `a camera error while streaming is only a preview status and the stream keeps running`() {
        val coordinator = coordinator().attach()
        coordinator.goLiveAndStream()
        port.startRecord()
        port.calls.clear()

        // ERROR_CAMERA_DEVICE (4): not "in use", so there is no automatic retry either.
        coordinator.cameraError("Open camera failed: 4")
        assertEquals(PreviewStatus.CameraError(PreviewCoordinator.CAMERA_FAILED) to PreviewCoordinator.CAMERA_FAILED, listener.previewStatuses.last())
        assertEquals("Camera problem during camera open — close other camera apps and try again", PreviewCoordinator.CAMERA_FAILED)
        assertTrue(port.isStreaming)
        assertTrue(port.isRecording)
        assertEquals("no stop, prepare or preview call", emptyList<String>(), port.calls)
        assertTrue(scheduler.pending.isEmpty())
        assertEquals("landscape 1280x720", coordinator.preparedGeometry?.label)
        // (@After: no transport status, so capture readiness and health cannot change.)
    }

    @Test fun `Reopen camera retries and the preview is ready again`() {
        val coordinator = coordinator().attach()
        coordinator.cameraError("Open camera failed: 4")
        port.calls.clear()

        coordinator.reopenCamera()
        assertEquals(listOf("stopPreview", "startPreview(surface#1,2400x1080)"), port.calls)
        assertEquals(listOf(PreviewStatus.Starting, PreviewStatus.Ready), statuses().takeLast(2))
        assertTrue(log.entries().any { it.step == DiagnosticStep.CAMERA_OPEN && it.redactedMessage == "reopen camera requested" })

        port.calls.clear()
        coordinator.reopenCamera()
        assertEquals("nothing to reopen", emptyList<String>(), port.calls)
    }

    @Test fun `camera in use is retried automatically exactly once`() {
        val coordinator = coordinator().attach()
        coordinator.cameraError("Open camera failed: 1")
        assertEquals(PreviewStatus.CameraError(PreviewCoordinator.CAMERA_FAILED), statuses().last())
        assertEquals(listOf(PreviewCoordinator.CAMERA_IN_USE_RETRY_MS), scheduler.pending.map { it.first })

        port.calls.clear()
        scheduler.runAll()
        assertEquals(listOf("stopPreview", "startPreview(surface#1,2400x1080)"), port.calls)
        assertEquals(PreviewStatus.Ready, statuses().last())

        // Still held by the other app: no second automatic retry, whatever the callback.
        coordinator.cameraError("Open camera failed: 2")
        coordinator.cameraDisconnected()
        assertTrue(scheduler.pending.isEmpty())
        assertTrue(statuses().last() is PreviewStatus.CameraError)

        // Once the camera has opened again, a later loss gets its one retry again.
        coordinator.reopenCamera()
        coordinator.cameraOpened()
        coordinator.cameraDisconnected()
        assertEquals(1, scheduler.pending.size)
    }

    @Test fun `Reopen camera cancels a pending automatic retry`() {
        val coordinator = coordinator().attach()
        coordinator.cameraError("Open camera failed: 1")
        coordinator.reopenCamera()
        coordinator.cameraError("Open camera failed: 1")
        port.calls.clear()
        scheduler.runAll()
        assertEquals("the cancelled retry does nothing", emptyList<String>(), port.calls)
        assertTrue(statuses().last() is PreviewStatus.CameraError)
    }

    @Test fun `a raw camera error reaches only the redacted log`() {
        // A configured setting value inside the library's text, as the settings redaction would list it.
        val redactingLog = DiagnosticsLog(clock = { 0L }, sink = { _, _, _ -> }, redaction = Redaction(listOf("video.example.test")))
        val coordinator = coordinator(diagnostics = redactingLog).attach()
        coordinator.cameraError("Create capture session failed: video.example.test refused")

        assertEquals(PreviewCoordinator.CAMERA_FAILED, listener.previewStatuses.last().second)
        assertEquals(PreviewStatus.CameraError(PreviewCoordinator.CAMERA_FAILED), statuses().last())
        val entry = redactingLog.entries().last { it.outcome == DiagnosticOutcome.FAIL }
        assertEquals(DiagnosticStep.CAMERA_OPEN, entry.step)
        assertEquals("camera error: Create capture session failed: <redacted> refused", entry.redactedMessage)
    }

    @Test fun `release stops record, stream and preview before releasing, exactly once`() {
        val coordinator = coordinator().attach()
        coordinator.goLiveAndStream()
        port.startRecord()
        port.calls.clear()

        coordinator.release(port)
        assertEquals(listOf("stopRecord", "stopStream", "stopPreview", "release"), port.calls)
        assertTrue(port.released)

        port.calls.clear()
        coordinator.release(port)
        assertEquals("idempotent", emptyList<String>(), port.calls)

        // Every later input is ignored: nothing touches the released stream.
        val statusesBefore = listener.previewStatuses.size
        coordinator.surfaceAvailable(surface, 0, 0)
        coordinator.surfaceChanged(1080, 2400)
        coordinator.displayRotationChanged(0)
        coordinator.cameraError("Open camera failed: 1")
        coordinator.cameraOpened()
        coordinator.reopenCamera()
        coordinator.streamStopped()
        assertEquals(StreamGate.NotReady("The camera was released"), coordinator.goLive())
        assertEquals(emptyList<String>(), port.calls)
        assertEquals(statusesBefore, listener.previewStatuses.size)
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test fun `release while idle stops the preview first and cancels a pending automatic retry`() {
        val coordinator = coordinator().attach()
        coordinator.cameraError("Open camera failed: 1")
        assertEquals(1, scheduler.pending.size)
        port.calls.clear()

        coordinator.release(port)
        assertEquals(listOf("stopPreview", "release"), port.calls)
        scheduler.runAll()
        assertEquals(listOf("stopPreview", "release"), port.calls)
    }

    @Test fun `a failed release step is logged and the camera is still released`() {
        val coordinator = coordinator().attach()
        coordinator.goLiveAndStream()
        port.stopStreamFailure = "encoder already released"
        port.calls.clear()

        coordinator.release(port)
        assertEquals(listOf("stopStream", "stopPreview", "release"), port.calls)
        val entry = log.entries().single { it.outcome == DiagnosticOutcome.FAIL }
        assertEquals(DiagnosticStep.PUBLISHER_START, entry.step)
        assertEquals("IllegalStateException", entry.errorClass)
        assertEquals("release: stopStream failed: encoder already released", entry.redactedMessage)
    }

    @Test fun `an unsupported or unreadable sensor orientation fails closed and never prepares`() {
        for (sensor in listOf(0, 180, null)) {
            listener.previewStatuses.clear()
            val coordinator = coordinator(sensor = sensor)
            coordinator.attach()
            coordinator.displayRotationChanged(0)
            val errors = statuses().filterIsInstance<PreviewStatus.CameraError>()
            assertEquals("sensor $sensor reported once", 1, errors.size)
            val gate = coordinator.goLive()
            assertTrue("$gate", gate is StreamGate.NotReady)
            assertEquals(errors.single().reason, (gate as StreamGate.NotReady).reason)
            assertNull(coordinator.preparedGeometry)
        }
        assertEquals(emptyList<String>(), port.calls.filter { it.startsWith("prepareVideo") || it.startsWith("startPreview") })
        assertEquals(
            listOf("Unsupported camera orientation (0 degrees)", "Unsupported camera orientation (180 degrees)", "The back camera's orientation could not be read"),
            log.entries().filter { it.outcome == DiagnosticOutcome.FAIL }.map { it.redactedMessage },
        )
    }

    @Test fun `an unknown display rotation never prepares a guessed geometry`() {
        rotation = null
        val coordinator = coordinator().attach()
        val gate = coordinator.goLive()
        assertEquals(StreamGate.NotReady("The display rotation could not be read"), gate)
        assertTrue(port.calls.isEmpty())
        assertTrue(listener.previewStatuses.isEmpty())
    }

    @Test fun `an unexpected port exception becomes a camera error instead of a crash`() {
        val failingResize = object : EncoderPort<FakeSurface> by port {
            override fun setPreviewResolution(width: Int, height: Int) = throw IllegalStateException("GL context lost")
        }
        val coordinator = coordinator(encoder = failingResize).attach()
        coordinator.surfaceChanged(1080, 2400)
        val (error, detail) = listener.previewStatuses.last()
        assertTrue("$error", error is PreviewStatus.CameraError)
        assertEquals("Camera preview failed (IllegalStateException)", detail)
        assertEquals("IllegalStateException", log.entries().last().errorClass)
    }
}
