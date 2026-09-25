package com.vaylith.cleanrepsmobile.ui

import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.encoder.utils.gl.SizeCalculator
import com.vaylith.cleanrepsmobile.model.LiveAthleteRegion
import com.vaylith.cleanrepsmobile.ui.PreviewMode.FILL
import com.vaylith.cleanrepsmobile.ui.PreviewMode.FIT
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class PreviewLayoutTest {
    private fun rect(mode: PreviewMode, containerW: Int, containerH: Int, streamW: Int, streamH: Int) =
        PreviewLayout.compute(mode, containerW, containerH, streamW, streamH)

    /** The real RootEncoder 2.7.0 class the preview uses: Fill for FILL, Adjust inside the FIT box. */
    private fun rootEncoder(mode: PreviewMode, surfaceW: Int, surfaceH: Int, streamW: Int, streamH: Int): GlViewPort {
        val aspect = if (mode == FILL) AspectRatioMode.Fill else AspectRatioMode.Adjust
        val port = SizeCalculator.calculateViewPort(aspect, surfaceW, surfaceH, streamW, streamH)
        return GlViewPort(port.x, port.y, port.width, port.height)
    }

    /** Relative difference between the displayed aspect and the stream aspect. */
    private fun aspectError(displayed: PixelRect, streamW: Int, streamH: Int): Double {
        val shown = displayed.width.toDouble() / displayed.height
        val stream = streamW.toDouble() / streamH
        return abs(shown - stream) / stream
    }

    @Test fun `fill on a 20 by 9 phone equals RootEncoder Fill in landscape and portrait`() {
        assertEquals(GlViewPort(0, -135, 2400, 1350), PreviewLayout.viewPort(FILL, 2400, 1080, 1280, 720))
        assertEquals(rootEncoder(FILL, 2400, 1080, 1280, 720), PreviewLayout.viewPort(FILL, 2400, 1080, 1280, 720))
        assertEquals(PixelRect(0, -135, 2400, 1350), rect(FILL, 2400, 1080, 1280, 720))

        assertEquals(GlViewPort(-135, 0, 1350, 2400), PreviewLayout.viewPort(FILL, 1080, 2400, 720, 1280))
        assertEquals(rootEncoder(FILL, 1080, 2400, 720, 1280), PreviewLayout.viewPort(FILL, 1080, 2400, 720, 1280))
        assertEquals(PixelRect(-135, 0, 1350, 2400), rect(FILL, 1080, 2400, 720, 1280))
    }

    @Test fun `fill fits a 16 by 9 phone exactly and crops the other axis on a 4 by 3 tablet`() {
        assertEquals(PixelRect(0, 0, 1920, 1080), rect(FILL, 1920, 1080, 1280, 720))
        assertEquals(PixelRect(0, 0, 1080, 1920), rect(FILL, 1080, 1920, 720, 1280))
        // A 4:3 landscape tablet is narrower than the stream, so the sides are cropped, not the top.
        assertEquals(PixelRect(-341, 0, 2730, 1536), rect(FILL, 2048, 1536, 1280, 720))
        assertEquals(PixelRect(0, -341, 1536, 2730), rect(FILL, 1536, 2048, 720, 1280))
        assertNull(PreviewLayout.controlBand(FILL, 2400, 1080, 1280, 720))
    }

    @Test fun `an odd crop converts RootEncoder's bottom-left y to a top-left screen rect`() {
        // 1350 - 1081 = 269 cropped rows; Java division puts GL y at -134, so 134 rows are cut
        // at the bottom and 135 at the top.
        val port = PreviewLayout.viewPort(FILL, 2400, 1081, 1280, 720)
        assertEquals(GlViewPort(0, -134, 2400, 1350), port)
        assertEquals(rootEncoder(FILL, 2400, 1081, 1280, 720), port)
        val shown = rect(FILL, 2400, 1081, 1280, 720)
        assertEquals(PixelRect(0, -135, 2400, 1350), shown)
        assertEquals(1081 + 134, shown.bottom)
    }

    @Test fun `fit gives a stream-aspect box and a control band on the thumb side`() {
        assertEquals(PixelRect(0, 0, 1920, 1080), PreviewLayout.surface(FIT, 2400, 1080, 1280, 720))
        assertEquals(PixelRect(0, 0, 1920, 1080), rect(FIT, 2400, 1080, 1280, 720))
        assertEquals(PixelRect(1920, 0, 480, 1080), PreviewLayout.controlBand(FIT, 2400, 1080, 1280, 720))

        assertEquals(PixelRect(0, 0, 1080, 1920), rect(FIT, 1080, 2400, 720, 1280))
        assertEquals(PixelRect(0, 1920, 1080, 480), PreviewLayout.controlBand(FIT, 1080, 2400, 720, 1280))

        assertNull("a 16:9 screen needs no band", PreviewLayout.controlBand(FIT, 1920, 1080, 1280, 720))
    }

    @Test fun `OD-3 keeps fill unless the owner chooses fit`() {
        assertFalse(PreviewLayout.PREVIEW_FIT)
        assertEquals(FILL, PreviewLayout.DEFAULT_MODE)
    }

    @Test fun `viewPort equals RootEncoder calculateViewPort for random sizes in both modes`() {
        val random = Random(20260925)
        repeat(20_000) {
            val surfaceW = random.nextInt(1, 5000)
            val surfaceH = random.nextInt(1, 5000)
            val streamW = random.nextInt(1, 4000)
            val streamH = random.nextInt(1, 4000)
            for (mode in PreviewMode.entries) {
                assertEquals(
                    "$mode ${surfaceW}x$surfaceH stream ${streamW}x$streamH",
                    rootEncoder(mode, surfaceW, surfaceH, streamW, streamH),
                    PreviewLayout.viewPort(mode, surfaceW, surfaceH, streamW, streamH),
                )
            }
        }
    }

    @Test fun `the displayed rect is never stretched and fill always covers the screen`() {
        val random = Random(7)
        val appStreams = listOf(1280 to 720, 720 to 1280)
        repeat(20_000) {
            // Any phone or tablet window, either orientation.
            val containerW = random.nextInt(480, 4097)
            val containerH = random.nextInt(480, 4097)
            // The app's two encoder sizes, and random streams from 9:21 to 21:9.
            val (streamW, streamH) = if (it % 2 == 0) {
                appStreams[random.nextInt(appStreams.size)]
            } else {
                val shortSide = random.nextInt(320, 2161)
                val longSide = (shortSide * random.nextDouble(1.0, 21.0 / 9.0)).toInt()
                if (random.nextBoolean()) longSide to shortSide else shortSide to longSide
            }
            for (mode in PreviewMode.entries) {
                val shown = rect(mode, containerW, containerH, streamW, streamH)
                val label = "$mode ${containerW}x$containerH stream ${streamW}x$streamH -> $shown"
                val error = aspectError(shown, streamW, streamH)
                // Integer pixels lose under one pixel on one side, so the error stays under 1/short side.
                assertTrue(label, error < 1.0 / minOf(shown.width, shown.height))
                if ((streamW to streamH) in appStreams || minOf(shown.width, shown.height) >= 200) {
                    assertTrue("$label error $error", error <= 0.005)
                }
                if (mode == FILL) {
                    // Covers the screen (one pixel of float tolerance) and crops both sides alike.
                    assertTrue(label, shown.left <= 0 && shown.top <= 0)
                    assertTrue(label, shown.right >= containerW - 1 && shown.bottom >= containerH - 1)
                    assertTrue(label, abs(-shown.left - (shown.right - containerW)) <= 1)
                    assertTrue(label, abs(-shown.top - (shown.bottom - containerH)) <= 1)
                } else {
                    // Fit shows the whole frame inside the box, which lies inside the screen.
                    val box = PreviewLayout.surface(FIT, containerW, containerH, streamW, streamH)
                    assertTrue(label, shown.left >= box.left && shown.top >= box.top)
                    assertTrue(label, shown.right <= box.right && shown.bottom <= box.bottom)
                    assertTrue(label, box.right <= containerW && box.bottom <= containerH)
                    val band = PreviewLayout.controlBand(FIT, containerW, containerH, streamW, streamH)
                    val bandArea = band?.let { it.width.toLong() * it.height } ?: 0L
                    assertEquals(label, containerW.toLong() * containerH, box.width.toLong() * box.height + bandArea)
                }
            }
        }
    }

    @Test fun `the stretch check catches a stretched rect`() {
        // Planted: AspectRatioMode.NONE would draw the whole 20:9 surface, a stretch of the 16:9 stream.
        assertTrue(aspectError(PixelRect(0, 0, 2400, 1080), 1280, 720) > 0.005)
        assertTrue(aspectError(PixelRect(0, 0, 1350, 2400), 1280, 720) > 0.005)
        assertTrue(aspectError(PixelRect(0, -135, 2400, 1350), 1280, 720) <= 0.005)
    }

    @Test fun `normalized and screen points round-trip`() {
        val random = Random(11)
        val layouts = listOf(
            rect(FILL, 2400, 1080, 1280, 720),
            rect(FILL, 1080, 2400, 720, 1280),
            rect(FIT, 2400, 1080, 1280, 720),
            rect(FILL, 2048, 1536, 1280, 720),
        )
        for (shown in layouts) {
            repeat(1_000) {
                val point = NormalizedPoint(random.nextDouble(-0.5, 1.5), random.nextDouble(-0.5, 1.5))
                val back = PreviewLayout.toNormalized(shown, PreviewLayout.toScreen(shown, point))
                assertEquals(point.x, back.x, 1e-9)
                assertEquals(point.y, back.y, 1e-9)
                val screen = ScreenPoint(random.nextDouble(-500.0, 3000.0), random.nextDouble(-500.0, 3000.0))
                val again = PreviewLayout.toScreen(shown, PreviewLayout.toNormalized(shown, screen))
                assertEquals(screen.x, again.x, 1e-6)
                assertEquals(screen.y, again.y, 1e-6)
            }
            assertEquals(ScreenPoint(shown.left.toDouble(), shown.top.toDouble()), PreviewLayout.toScreen(shown, NormalizedPoint(0.0, 0.0)))
            assertEquals(ScreenPoint(shown.right.toDouble(), shown.bottom.toDouble()), PreviewLayout.toScreen(shown, NormalizedPoint(1.0, 1.0)))
        }
    }

    @Test fun `points in the cropped margin map off screen and boxes are clipped to the screen`() {
        val shown = rect(FILL, 2400, 1080, 1280, 720) // 135 rows cropped at the top and bottom
        val inTopMargin = PreviewLayout.toScreen(shown, NormalizedPoint(0.5, 0.05))
        assertEquals(ScreenPoint(1200.0, -67.5), inTopMargin)
        assertFalse(PreviewLayout.isOnScreen(inTopMargin, 2400, 1080))
        assertFalse(PreviewLayout.isOnScreen(PreviewLayout.toScreen(shown, NormalizedPoint(0.5, 0.95)), 2400, 1080))
        assertTrue(PreviewLayout.isOnScreen(PreviewLayout.toScreen(shown, NormalizedPoint(0.5, 0.5)), 2400, 1080))

        // The live worker's athlete region: its top and bottom lie in the cropped margins.
        val region = LiveAthleteRegion(left = 0.2, top = 0.05, right = 0.8, bottom = 0.95)
        assertEquals(ScreenBox(480.0, 0.0, 1920.0, 1080.0), PreviewLayout.regionOnScreen(shown, 2400, 1080, region))

        // A box wholly inside the top margin is not drawn at all.
        assertNull(PreviewLayout.clippedBox(shown, 2400, 1080, NormalizedPoint(0.1, 0.0), NormalizedPoint(0.9, 0.09)))
        // Corners given in either order give the same box.
        assertEquals(
            PreviewLayout.clippedBox(shown, 2400, 1080, NormalizedPoint(0.8, 0.9), NormalizedPoint(0.2, 0.3)),
            PreviewLayout.clippedBox(shown, 2400, 1080, NormalizedPoint(0.2, 0.3), NormalizedPoint(0.8, 0.9)),
        )

        // Portrait crops the sides instead.
        val portrait = rect(FILL, 1080, 2400, 720, 1280)
        assertEquals(ScreenBox(135.0, 120.0, 945.0, 2280.0), PreviewLayout.regionOnScreen(portrait, 1080, 2400, region))
        assertNull(PreviewLayout.clippedBox(portrait, 1080, 2400, NormalizedPoint(0.0, 0.2), NormalizedPoint(0.09, 0.8)))

        // Fit shows the whole frame, so nothing is clipped.
        val fit = rect(FIT, 2400, 1080, 1280, 720)
        assertEquals(ScreenBox(384.0, 54.0, 1536.0, 1026.0), PreviewLayout.regionOnScreen(fit, 2400, 1080, region))
    }

    @Test fun `sizes must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { rect(FILL, 0, 1080, 1280, 720) }
        assertThrows(IllegalArgumentException::class.java) { rect(FIT, 2400, 1080, 1280, 0) }
        assertThrows(IllegalArgumentException::class.java) { PreviewLayout.viewPort(FILL, 2400, -1, 1280, 720) }
    }
}
