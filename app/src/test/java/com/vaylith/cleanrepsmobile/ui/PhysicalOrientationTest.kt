package com.vaylith.cleanrepsmobile.ui

import android.view.OrientationEventListener
import com.vaylith.cleanrepsmobile.model.CaptureOrientation
import com.vaylith.cleanrepsmobile.ui.PhysicalOrientation.Companion.ORIENTATION_UNKNOWN
import com.vaylith.cleanrepsmobile.ui.Quadrant.DEG_0
import com.vaylith.cleanrepsmobile.ui.Quadrant.DEG_180
import com.vaylith.cleanrepsmobile.ui.Quadrant.DEG_270
import com.vaylith.cleanrepsmobile.ui.Quadrant.DEG_90
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class PhysicalOrientationTest {
    /** Feeds `degrees` every 100 ms from `startMs` for `durationMs` and returns the time after. */
    private fun PhysicalOrientation.hold(degrees: Int, startMs: Long, durationMs: Long): Long {
        var now = startMs
        while (now < startMs + durationMs) {
            update(degrees, now)
            now += 100
        }
        return now
    }

    @Test fun `each quadrant owns plus or minus 30 degrees and the gaps between belong to none`() {
        val expected = (0 until 360).associateWith { degrees ->
            when (degrees) {
                in 0..30, in 330..359 -> DEG_0
                in 60..120 -> DEG_90
                in 150..210 -> DEG_180
                in 240..300 -> DEG_270
                else -> null
            }
        }
        assertEquals(expected, (0 until 360).associateWith { PhysicalOrientation.zoneOf(it) })
        assertEquals(30, PhysicalOrientation.HYSTERESIS_DEGREES)
    }

    @Test fun `a turn is confirmed only after 1_5 s of stable readings`() {
        val orientation = PhysicalOrientation(DEG_0)
        orientation.update(90, 10_000)
        orientation.update(90, 11_499)
        assertEquals("not yet 1.5 s", DEG_0, orientation.stable)
        orientation.update(90, 11_500)
        assertEquals(DEG_90, orientation.stable)
        assertEquals(1_500L, PhysicalOrientation.STABILITY_MS)
    }

    @Test fun `readings in the gap keep the last orientation for any time`() {
        val orientation = PhysicalOrientation(DEG_0)
        var now = orientation.hold(44, 0, 10_000)
        now = orientation.hold(59, now, 10_000)
        assertEquals(DEG_0, orientation.stable)
        now = orientation.hold(60, now, 1_600)
        assertEquals("60 degrees is inside the 90 zone", DEG_90, orientation.stable)
        now = orientation.hold(31, now, 10_000)
        assertEquals("31 degrees is not yet back in the 0 zone", DEG_90, orientation.stable)
        orientation.hold(30, now, 1_600)
        assertEquals(DEG_0, orientation.stable)
    }

    @Test fun `any reading outside the new zone restarts the stability wait`() {
        val orientation = PhysicalOrientation(DEG_0)
        var now = orientation.hold(90, 0, 1_400)
        orientation.update(45, now) // a diagonal, in no zone
        now += 100
        now = orientation.hold(90, now, 1_400)
        assertEquals(DEG_0, orientation.stable)
        orientation.update(10, now) // back in the stable zone cancels too
        now += 100
        now = orientation.hold(90, now, 1_400)
        assertEquals(DEG_0, orientation.stable)
        orientation.hold(90, now, 200)
        assertEquals(DEG_90, orientation.stable)
    }

    @Test fun `alternating between two new quadrants never confirms either`() {
        val orientation = PhysicalOrientation(DEG_0)
        var now = 0L
        repeat(100) {
            now = orientation.hold(if (it % 2 == 0) 90 else 270, now, 1_000)
        }
        assertEquals(DEG_0, orientation.stable)
    }

    @Test fun `a phone lying flat is ignored and cancels a pending turn`() {
        assertEquals(OrientationEventListener.ORIENTATION_UNKNOWN, ORIENTATION_UNKNOWN)
        val flat = PhysicalOrientation()
        flat.hold(ORIENTATION_UNKNOWN, 0, 60_000)
        assertNull("flat readings never produce an orientation", flat.stable)

        val orientation = PhysicalOrientation(DEG_90)
        var now = orientation.hold(ORIENTATION_UNKNOWN, 0, 60_000)
        assertEquals("flat keeps the last orientation", DEG_90, orientation.stable)
        now = orientation.hold(0, now, 1_000)
        orientation.update(ORIENTATION_UNKNOWN, now)
        now += 100
        now = orientation.hold(0, now, 1_000)
        assertEquals("the flat reading restarted the wait", DEG_90, orientation.stable)
        orientation.hold(0, now, 600)
        assertEquals(DEG_0, orientation.stable)
    }

    @Test fun `the first orientation also needs 1_5 s`() {
        val orientation = PhysicalOrientation()
        assertNull(orientation.update(270, 0))
        assertNull(orientation.update(270, 1_499))
        assertEquals(DEG_270, orientation.update(270, 1_500))
    }

    @Test fun `a clock that goes backwards restarts the wait`() {
        val orientation = PhysicalOrientation(DEG_0)
        orientation.update(180, 5_000)
        orientation.update(180, 1_000)
        orientation.update(180, 2_499)
        assertEquals(DEG_0, orientation.stable)
        orientation.update(180, 2_500)
        assertEquals(DEG_180, orientation.stable)
    }

    @Test fun `jittery readings around a held landscape confirm it`() {
        val random = Random(5)
        val orientation = PhysicalOrientation(DEG_0)
        var now = 0L
        while (now < 1_400) {
            orientation.update(90 + random.nextInt(-12, 13), now)
            now += 200
        }
        assertEquals("1.2 s of readings is not enough", DEG_0, orientation.stable)
        while (now <= 1_600) {
            orientation.update(90 + random.nextInt(-12, 13), now)
            now += 200
        }
        assertEquals(DEG_90, orientation.stable)
    }

    @Test fun `quadrants map to the display rotation the phone takes`() {
        // OrientationEventListener degrees to Surface.ROTATION_*: left side up (90) is ROTATION_270.
        assertEquals(mapOf(DEG_0 to 0, DEG_90 to 3, DEG_180 to 2, DEG_270 to 1), Quadrant.entries.associateWith { it.displayRotation })
        for (rotation in 0..3) assertEquals(rotation, Quadrant.forDisplayRotation(rotation).displayRotation)
        assertThrows(IllegalArgumentException::class.java) { Quadrant.forDisplayRotation(4) }
    }

    @Test fun `the turned banner shows only while live and turned away from the start orientation`() {
        val landscapeStart = 1 // ROTATION_90: the phone's right side up (270 degrees)
        val text = "Phone turned - video stays landscape. Stop video to switch."
        assertNull(PhysicalOrientation.turnedBanner(live = true, landscapeStart, CaptureOrientation.LANDSCAPE, DEG_270))
        assertEquals(text, PhysicalOrientation.turnedBanner(live = true, landscapeStart, CaptureOrientation.LANDSCAPE, DEG_0))
        assertEquals("upside-down landscape too", text, PhysicalOrientation.turnedBanner(live = true, landscapeStart, CaptureOrientation.LANDSCAPE, DEG_90))
        assertNull(PhysicalOrientation.turnedBanner(live = false, landscapeStart, CaptureOrientation.LANDSCAPE, DEG_0))
        assertNull("no stable reading yet", PhysicalOrientation.turnedBanner(live = true, landscapeStart, CaptureOrientation.LANDSCAPE, null))
        assertEquals(
            "Phone turned - video stays portrait. Stop video to switch.",
            PhysicalOrientation.turnedBanner(live = true, 0, CaptureOrientation.PORTRAIT, DEG_90),
        )
    }
}
