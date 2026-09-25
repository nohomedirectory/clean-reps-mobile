package com.vaylith.cleanrepsmobile.media

import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticOutcome
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticStep
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticsLog
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

    private val port = FakeEncoderPort()
    private val listener = RecordingListener()
    private val log = DiagnosticsLog(clock = { 0L }, sink = { _, _, _ -> })
    private var rotation: Int? = 1
    private val surface = FakeSurface(1)

    private fun coordinator(sensor: Int? = 90, encoder: EncoderPort<FakeSurface> = port) =
        PreviewCoordinator(encoder, listener, log, sensor) { rotation }

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
        assertTrue(detail, detail.contains("Camera in use by another app"))
        val entry = log.entries().single { it.outcome == DiagnosticOutcome.FAIL }
        assertEquals(DiagnosticStep.PREVIEW_START, entry.step)
        assertEquals("RuntimeException", entry.errorClass)
    }

    @Test fun `an unsupported camera size is logged as a cameraOpen failure`() {
        port.prepareFailure = "Resolution 1280x720 not supported"
        val coordinator = coordinator()
        coordinator.displayRotationChanged(rotation)
        assertTrue(statuses().single() is PreviewStatus.CameraError)
        val entry = log.entries().single { it.outcome == DiagnosticOutcome.FAIL }
        assertEquals(DiagnosticStep.CAMERA_OPEN, entry.step)
        assertEquals("IllegalArgumentException", entry.errorClass)
        assertTrue(coordinator.goLive() is StreamGate.NotReady)
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
