package com.vaylith.cleanrepsmobile.ui

import com.vaylith.cleanrepsmobile.feedback.FeedbackPolicy
import com.vaylith.cleanrepsmobile.media.CaptureGeometry
import com.vaylith.cleanrepsmobile.media.FakeEncoderPort
import com.vaylith.cleanrepsmobile.media.FakeSurface
import com.vaylith.cleanrepsmobile.media.PreviewCoordinator
import com.vaylith.cleanrepsmobile.media.PreviewStatus
import com.vaylith.cleanrepsmobile.media.PublisherListener
import com.vaylith.cleanrepsmobile.media.PublisherStatus
import com.vaylith.cleanrepsmobile.media.StreamGate
import com.vaylith.cleanrepsmobile.model.CaptureReadiness
import com.vaylith.cleanrepsmobile.session.AppState
import com.vaylith.cleanrepsmobile.session.SessionController
import org.junit.Assert.*
import org.junit.Test

class BannersTest {
    private val cameraText = "Camera problem during camera open — close other camera apps and try again"
    private val stepText = "Couldn't reach Clean Reps for create session (timeout) — is Tailscale on?"
    private val running = listOf(CaptureReadiness.CONNECTING, CaptureReadiness.LIVE, CaptureReadiness.RECONNECTING)

    /** Landscape video started with the display at ROTATION_90, i.e. the phone held at 270 degrees. */
    private val landscape = CaptureGeometry.forDisplayRotation(1, 90).getOrThrow()

    /** Portrait video started in the natural orientation, 0 degrees. */
    private val portrait = CaptureGeometry.forDisplayRotation(0, 90).getOrThrow()

    private fun cameraError(readiness: CaptureReadiness, inFlight: Boolean = false) =
        AppState(readiness = readiness, preview = PreviewStatus.CameraError(cameraText), previewDetail = cameraText, requestInFlight = inFlight)

    /** Feeds [degrees] every 100 ms from [fromMs] until [toMs] and returns the last banner. */
    private fun TurnWatch.hold(degrees: Int, fromMs: Long, toMs: Long): String? {
        var banner: String? = null
        var now = fromMs
        while (now <= toMs) {
            banner = reading(degrees, now)
            now += 100
        }
        return banner
    }

    @Test fun `nothing to say, no banner`() {
        assertEquals(emptyList<Banner>(), Banners.select(AppState(), turned = null))
        assertEquals(emptyList<Banner>(), Banners.select(AppState(readiness = CaptureReadiness.LIVE, streamGeometry = landscape.label), turned = null))
    }

    @Test fun `the controller's left-screen and unjudgeable banners`() {
        val left = Banners.select(AppState(readiness = CaptureReadiness.STOPPED, banner = SessionController.LEFT_SCREEN_BANNER), null).single()
        assertEquals(Banner(BannerKind.LEFT_SCREEN, "Video stopped because Clean Reps left the screen. Tap Go live."), left)
        val unjudged = Banners.select(AppState(readiness = CaptureReadiness.LIVE, feedbackBanner = FeedbackPolicy.UNJUDGED_BANNER), null).single()
        assertEquals(Banner(BannerKind.UNJUDGED, "Couldn't judge that one - keep head and feet in view"), unjudged)
    }

    @Test fun `a camera error offers Reopen camera only while the video is off`() {
        for (readiness in listOf(CaptureReadiness.NOT_CONFIGURED, CaptureReadiness.STOPPED, CaptureReadiness.ERROR, CaptureReadiness.PUBLISHER_UNAVAILABLE)) {
            val banner = Banners.select(cameraError(readiness), null).first()
            assertEquals("$readiness", Banner(BannerKind.CAMERA_ERROR, cameraText, BannerAction.REOPEN_CAMERA, actionEnabled = true), banner)
        }
        // Planted: while a stream runs, RootEncoder's startPreview skips the camera, so Reopen would lie; Stop video is offered instead.
        for (readiness in running) {
            val banners = Banners.select(cameraError(readiness), null)
            assertTrue("$readiness", banners.none { it.action == BannerAction.REOPEN_CAMERA })
            assertEquals("$readiness", Banner(BannerKind.CAMERA_ERROR, cameraText, BannerAction.STOP_VIDEO, actionEnabled = true, note = Banners.REOPEN_AFTER_STOP),
                banners.single { it.kind == BannerKind.CAMERA_ERROR })
        }
        assertFalse(Banners.select(cameraError(CaptureReadiness.STOPPED, inFlight = true), null).first().actionEnabled)
        assertFalse(Banners.select(cameraError(CaptureReadiness.LIVE, inFlight = true), null).first().actionEnabled)
    }

    @Test fun `step and connection errors name the step`() {
        val step = Banners.select(AppState(readiness = CaptureReadiness.STOPPED, statusDetail = stepText, stepError = stepText), null)
        assertEquals(listOf(Banner(BannerKind.ERROR, stepText)), step)
        val connection = "Video connection failed. Check the connection settings and retry."
        assertEquals(listOf(Banner(BannerKind.ERROR, connection)), Banners.select(AppState(readiness = CaptureReadiness.ERROR, statusDetail = connection), null))
        assertEquals(listOf(Banner(BannerKind.ERROR, "MEDIAMTX_SRT_HOST is not configured")),
            Banners.select(AppState(readiness = CaptureReadiness.PUBLISHER_UNAVAILABLE, statusDetail = "MEDIAMTX_SRT_HOST is not configured"), null))
        // An ordinary status is the caption under the pill, not an error banner.
        val info = AppState(readiness = CaptureReadiness.LIVE, statusDetail = "Drill selected. Start practice when ready.")
        assertEquals(emptyList<Banner>(), Banners.select(info, null))
        assertEquals("Drill selected. Start practice when ready.", Banners.caption(info, emptyList()))
        // The caption never repeats a banner.
        assertNull(Banners.caption(AppState(statusDetail = stepText, stepError = stepText), step))
    }

    /**
     * The status and detail a real PreviewCoordinator reports when the phone is turned to portrait
     * while a landscape video streams: RotationPending with its own detail text.
     */
    private fun realRotationPending(): Pair<PreviewStatus, String> {
        val port = FakeEncoderPort()
        val reported = mutableListOf<Pair<PreviewStatus, String>>()
        val listener = object : PublisherListener {
            override fun onPublisherStatus(status: PublisherStatus, detail: String) = Unit
            override fun onSourceDiscontinuity(detail: String) = Unit
            override fun onSafetyRecording(detail: String) = Unit
            override fun onPreviewStatus(status: PreviewStatus, detail: String) {
                reported += status to detail
            }
        }
        var rotation: Int? = 1
        val coordinator = PreviewCoordinator(port, listener, null, 90, { _, _ -> }) { rotation }
        coordinator.displayRotationChanged(1)
        coordinator.surfaceAvailable(FakeSurface(1), 0, 0)
        coordinator.surfaceChanged(2400, 1080)
        assertTrue(coordinator.goLive() is StreamGate.Ready)
        port.startStream()
        coordinator.streamStarted()
        rotation = 0
        coordinator.displayRotationChanged(0)
        assertEquals(emptyList<String>(), port.violations)
        return reported.last()
    }

    @Test fun `a rotation waiting for the video to stop shows the coordinator's own text`() {
        val (pending, detail) = realRotationPending()
        assertTrue("$pending", pending is PreviewStatus.RotationPending)
        val state = AppState(readiness = CaptureReadiness.LIVE, preview = pending, previewDetail = detail)
        assertEquals(listOf(Banner(BannerKind.ROTATION_PENDING, "Phone turned - video stays landscape. Stop video to switch.")),
            Banners.select(state, turned = null))
    }

    @Test fun `a turned phone and a pending rotation together make one banner`() {
        val (pending, detail) = realRotationPending()
        val state = AppState(readiness = CaptureReadiness.LIVE, preview = pending, previewDetail = detail)
        // The quantizer's banner for the same turn: landscape video, phone now held in portrait.
        val turned = TurnWatch(landscape).hold(0, 0, 1_600)
        // One builder: the two signals read exactly the same.
        assertEquals(detail, turned)
        assertEquals(listOf(Banner(BannerKind.ROTATION, detail)), Banners.select(state, turned))
        // Planted: even a pending banner with different text is dropped while the turned banner shows.
        val differing = state.copy(previewDetail = "Rotation applies after Stop video.")
        assertEquals(listOf(BannerKind.ROTATION), Banners.select(differing, turned).map { it.kind })
    }

    @Test fun `all four signals together, most urgent first and never twice`() {
        val state = cameraError(CaptureReadiness.STOPPED).copy(
            banner = SessionController.LEFT_SCREEN_BANNER, stepError = stepText, statusDetail = stepText, feedbackBanner = FeedbackPolicy.UNJUDGED_BANNER,
        )
        val turned = "Phone turned - video stays landscape. Stop video to switch."
        assertEquals(
            listOf(BannerKind.ROTATION, BannerKind.LEFT_SCREEN, BannerKind.CAMERA_ERROR, BannerKind.ERROR, BannerKind.UNJUDGED),
            Banners.select(state, turned).map { it.kind },
        )
        // The same text from two sources shows once.
        assertEquals(1, Banners.select(cameraError(CaptureReadiness.STOPPED).copy(stepError = cameraText), null).size)
    }

    @Test fun `the rotation banner shows after a held turn and names the locked orientation`() {
        val watch = TurnWatch(landscape)
        assertNull(watch.hold(270, 0, 2_000))
        // Turned to portrait: nothing for the first 1.4 s, then the banner.
        assertNull(watch.hold(0, 2_100, 3_500))
        assertEquals("Phone turned - video stays landscape. Stop video to switch.", watch.reading(0, 3_600))
        // Turned back: the banner goes once the start orientation has held again for 1.5 s.
        assertNotNull(watch.hold(270, 3_700, 5_100))
        assertNull(watch.reading(270, 5_200))

        val upright = TurnWatch(portrait)
        assertEquals("Phone turned - video stays portrait. Stop video to switch.", upright.hold(90, 0, 1_600))
    }

    @Test fun `the rotation banner never shows for a flat phone, within hysteresis or on a diagonal (planted)`() {
        val watch = TurnWatch(landscape)
        assertNull("flat", watch.hold(PhysicalOrientation.ORIENTATION_UNKNOWN, 0, 20_000))
        // Up to 30 degrees either side of the start orientation, each held for 5 s.
        listOf(240, 250, 290, 300).forEachIndexed { i, degrees ->
            val from = 20_100L + i * 5_000
            assertNull("$degrees", watch.hold(degrees, from, from + 4_900))
        }
        // Between two zones (300 < 315 < 330) the last orientation holds for any time.
        assertNull("diagonal", watch.hold(315, 40_100, 60_000))
        // A turn interrupted by lying flat restarts the 1.5 s wait.
        assertNull(watch.hold(0, 60_100, 61_100))
        assertNull(watch.reading(PhysicalOrientation.ORIENTATION_UNKNOWN, 61_200))
        assertNull(watch.hold(0, 61_300, 62_700))
        assertNotNull(watch.reading(0, 62_800))
    }
}
