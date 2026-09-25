package com.vaylith.cleanrepsmobile.ui

import com.vaylith.cleanrepsmobile.media.CaptureGeometry
import com.vaylith.cleanrepsmobile.model.CaptureOrientation
import com.vaylith.cleanrepsmobile.model.CaptureReadiness
import com.vaylith.cleanrepsmobile.model.LiveAnalysisState
import com.vaylith.cleanrepsmobile.model.LiveAnalysisStatus
import com.vaylith.cleanrepsmobile.model.LiveAthleteRegion
import com.vaylith.cleanrepsmobile.model.LiveSourceGeometry
import com.vaylith.cleanrepsmobile.model.LiveSourceOrientation
import com.vaylith.cleanrepsmobile.session.AppState
import org.junit.Assert.*
import org.junit.Test

class FramingGuideModelTest {
    private val landscape = CaptureGeometry.forDisplayRotation(1, 90).getOrThrow()
    private val portrait = CaptureGeometry.forDisplayRotation(0, 90).getOrThrow()

    /** The live worker's configured region (`--athlete-region 0.2,0.05,0.8,0.95`). */
    private val workerRegion = LiveAthleteRegion(0.2, 0.05, 0.8, 0.95)
    private val landscapeSource = LiveSourceGeometry(1280, 720, LiveSourceOrientation.LANDSCAPE)
    private val portraitSource = LiveSourceGeometry(720, 1280, LiveSourceOrientation.PORTRAIT)

    private fun fill(region: LiveAthleteRegion?, prepared: CaptureGeometry?, w: Int, h: Int, source: LiveSourceGeometry? = null) =
        FramingGuide.box(region, source, prepared, w, h, PreviewMode.FILL)

    /** Screen coordinates are doubles (0.7 x 1350 is 944.999...); a thousandth of a pixel is equal. */
    private fun assertBox(expected: ScreenBox, actual: ScreenBox?) {
        assertNotNull("expected $expected", actual)
        listOf(expected.left to actual!!.left, expected.top to actual.top, expected.right to actual.right, expected.bottom to actual.bottom)
            .forEach { (e, a) -> assertEquals("$expected vs $actual", e, a, 1e-3) }
    }

    /** What PreviewLayout predicts for a corner, clipped to the window. */
    private fun predicted(w: Int, h: Int, streamW: Int, streamH: Int, region: LiveAthleteRegion): ScreenBox {
        val shown = PreviewLayout.compute(PreviewMode.FILL, w, h, streamW, streamH)
        val a = PreviewLayout.toScreen(shown, NormalizedPoint(region.left, region.top))
        val b = PreviewLayout.toScreen(shown, NormalizedPoint(region.right, region.bottom))
        return ScreenBox(a.x.coerceIn(0.0, w.toDouble()), a.y.coerceIn(0.0, h.toDouble()), b.x.coerceIn(0.0, w.toDouble()), b.y.coerceIn(0.0, h.toDouble()))
    }

    @Test fun `landscape 2400x1080 with 1280x720 - corners land where PreviewLayout predicts, the cropped rows are clipped`() {
        // Fill shows 2400x1350 at top -135: the region's top (-67.5) and bottom (1147.5) lie in the cropped rows.
        val box = fill(workerRegion, landscape, 2400, 1080, landscapeSource)
        assertBox(ScreenBox(480.0, 0.0, 1920.0, 1080.0), box)
        assertBox(predicted(2400, 1080, 1280, 720, workerRegion), box)
        // A region inside the visible rows keeps its exact corners: 0.3 -> -135 + 405 = 270.
        assertBox(ScreenBox(720.0, 270.0, 1680.0, 810.0), fill(LiveAthleteRegion(0.3, 0.3, 0.7, 0.7), landscape, 2400, 1080))
    }

    @Test fun `portrait 1080x2400 with 720x1280 - corners land where PreviewLayout predicts, the cropped columns are clipped`() {
        // Fill shows 1350x2400 at left -135.
        val box = fill(workerRegion, portrait, 1080, 2400, portraitSource)
        assertBox(ScreenBox(135.0, 120.0, 945.0, 2280.0), box)
        assertBox(predicted(1080, 2400, 720, 1280, workerRegion), box)
        // Reaching into the cropped columns is clipped at the window edge.
        assertBox(ScreenBox(0.0, 240.0, 1080.0, 2160.0), fill(LiveAthleteRegion(0.0, 0.1, 1.0, 0.9), portrait, 1080, 2400))
    }

    @Test fun `a region wholly in a cropped margin draws nothing`() {
        assertNull(fill(LiveAthleteRegion(0.1, 0.0, 0.9, 0.09), landscape, 2400, 1080))
        assertNull(fill(LiveAthleteRegion(0.1, 0.91, 0.9, 1.0), landscape, 2400, 1080))
        assertNull(fill(LiveAthleteRegion(0.0, 0.2, 0.09, 0.8), portrait, 1080, 2400))
        assertNull(fill(LiveAthleteRegion(0.91, 0.2, 1.0, 0.8), portrait, 1080, 2400))
        assertNull(fill(null, landscape, 2400, 1080))
    }

    @Test fun `no region point ever draws off the visible frame (planted over many regions)`() {
        val steps = listOf(0.0, 0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 0.95, 1.0)
        val cases = listOf(
            Triple(landscape, 2400, 1080), Triple(portrait, 1080, 2400), Triple(landscape, 1920, 1080),
            Triple(landscape, 2340, 1080), Triple(portrait, 1080, 2340), Triple(portrait, 720, 1600),
        )
        var drawn = 0
        for ((geometry, w, h) in cases) for (mode in PreviewMode.entries) for (l in steps) for (t in steps) for (r in steps) for (b in steps) {
            if (l >= r || t >= b) continue
            val box = FramingGuide.box(LiveAthleteRegion(l, t, r, b), null, geometry, w, h, mode) ?: continue
            drawn++
            assertTrue("$box in ${w}x$h", box.left >= 0.0 && box.top >= 0.0 && box.right <= w && box.bottom <= h)
            assertTrue("$box is not empty", box.left < box.right && box.top < box.bottom)
        }
        assertTrue(drawn > 1_000)
        // Planted: the unclipped mapping of the worker region does leave the landscape window, so clipping is what keeps it on screen.
        val shown = PreviewLayout.compute(PreviewMode.FILL, 2400, 1080, 1280, 720)
        assertTrue(PreviewLayout.toScreen(shown, NormalizedPoint(0.2, 0.05)).y < 0.0)
    }

    @Test fun `a sideways source draws no box`() {
        // The analyzer decodes portrait while the phone shows landscape: the box would point at the wrong place.
        assertNull(fill(workerRegion, landscape, 2400, 1080, portraitSource))
        assertNull(fill(workerRegion, portrait, 1080, 2400, landscapeSource))
        assertNotNull(fill(workerRegion, landscape, 2400, 1080, LiveSourceGeometry(1280, 720, LiveSourceOrientation.UNKNOWN)))
    }

    @Test fun `before the first prepare the frame follows the window orientation, and FIT maps into the stream box`() {
        assertEquals(1280 to 720, FramingGuide.streamSize(null, 2400, 1080))
        assertEquals(720 to 1280, FramingGuide.streamSize(null, 1080, 2400))
        assertEquals(fill(workerRegion, landscape, 2400, 1080), fill(workerRegion, null, 2400, 1080))
        // FIT: the 1920x1080 box at the top left, uncropped.
        assertBox(ScreenBox(384.0, 54.0, 1536.0, 1026.0), FramingGuide.box(workerRegion, null, landscape, 2400, 1080, PreviewMode.FIT))
        assertNull(FramingGuide.box(workerRegion, null, landscape, 0, 1080, PreviewMode.FILL))
    }

    @Test fun `no box is drawn from a stale status or when the video is not LIVE`() {
        val current = LiveAnalysisStatus(LiveAnalysisState.TRACKING, athleteRegion = workerRegion, sourceGeometry = landscapeSource)
        assertEquals(current, FramingGuide.drawnStatus(AppState(readiness = CaptureReadiness.LIVE, liveAnalysis = current)))
        // Stale keeps the last region, but drawing it would claim the analyzer is still watching.
        val stale = current.copy(state = LiveAnalysisState.STALE, stale = true)
        assertNull(FramingGuide.drawnStatus(AppState(readiness = CaptureReadiness.LIVE, liveAnalysis = stale)))
        assertNull(FramingGuide.drawnStatus(AppState(readiness = CaptureReadiness.LIVE, liveAnalysis = current.copy(stale = true))))
        assertNull(FramingGuide.drawnStatus(AppState(readiness = CaptureReadiness.LIVE, liveAnalysis = current.copy(state = LiveAnalysisState.STALE))))
        for (readiness in CaptureReadiness.entries - CaptureReadiness.LIVE) {
            assertNull("$readiness", FramingGuide.drawnStatus(AppState(readiness = readiness, liveAnalysis = current)))
        }
        assertNull(FramingGuide.drawnStatus(AppState(readiness = CaptureReadiness.LIVE)))
    }

    @Test fun `the Stay in this area label stays inside the window`() {
        val label = 300 to 60
        // Inside a box away from the edges: 8 px in from its top left corner.
        assertEquals(488 to 8, FramingGuide.labelPosition(ScreenBox(480.0, 0.0, 1920.0, 1080.0), label.first, label.second, 2400, 1080, 8))
        // A narrow box at the right edge: moved left so the whole label is on screen.
        assertEquals(2100 to 108, FramingGuide.labelPosition(ScreenBox(2350.0, 100.0, 2400.0, 500.0), label.first, label.second, 2400, 1080, 8))
        // A box starting near the bottom: moved up.
        assertEquals(58 to 1020, FramingGuide.labelPosition(ScreenBox(50.0, 1060.0, 900.0, 1080.0), label.first, label.second, 2400, 1080, 8))
        // A label wider than the window starts at the left edge rather than off screen.
        assertEquals(0 to 8, FramingGuide.labelPosition(ScreenBox(0.0, 0.0, 200.0, 200.0), 500, 60, 400, 800, 8))
        // Planted: over many boxes and label sizes, the label never leaves the window when it fits.
        for (left in listOf(0.0, 500.0, 1900.0, 2390.0)) for (top in listOf(0.0, 540.0, 1050.0, 1079.0)) for ((w, h) in listOf(300 to 60, 120 to 40, 2400 to 1080)) {
            val (x, y) = FramingGuide.labelPosition(ScreenBox(left, top, 2400.0, 1080.0), w, h, 2400, 1080, 8)
            assertTrue("$left,$top ${w}x$h at $x,$y", x >= 0 && y >= 0 && x + w <= 2400 && y + h <= 1080)
        }
    }

    @Test fun `the guide and its hint fade during practice`() {
        assertEquals(1f, FramingGuide.alpha(practiceActive = false))
        assertEquals(0f, FramingGuide.alpha(practiceActive = true))
        assertEquals("Stay in this area", FramingGuide.LABEL)
        assertEquals("Head and feet on screen - about 3-4 m away - phone at waist height", FramingGuide.HINT)
    }

    @Test fun `the portrait hint shows for a portrait geometry until dismissed`() {
        assertEquals("Landscape works best for kicks", PortraitHint.TEXT)
        assertTrue(PortraitHint.shown(CaptureOrientation.PORTRAIT, windowPortrait = true, dismissed = false))
        assertFalse(PortraitHint.shown(CaptureOrientation.PORTRAIT, windowPortrait = true, dismissed = true))
        assertFalse(PortraitHint.shown(CaptureOrientation.LANDSCAPE, windowPortrait = false, dismissed = false))
        // A geometry read before a rotation is applied never shows it in a landscape window.
        assertFalse(PortraitHint.shown(CaptureOrientation.PORTRAIT, windowPortrait = false, dismissed = false))
        assertFalse(PortraitHint.shown(null, windowPortrait = true, dismissed = false))
    }
}
