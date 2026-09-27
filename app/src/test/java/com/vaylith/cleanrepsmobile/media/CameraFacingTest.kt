package com.vaylith.cleanrepsmobile.media

import org.junit.Assert.*
import org.junit.Test

class CameraFacingTest {
    @Test fun `saved front survives recreation while missing or unavailable lens falls back`() {
        val both = CameraFacing.entries.toSet()
        assertEquals(CameraFacing.FRONT, CameraFacing.restored("FRONT", both))
        assertEquals(CameraFacing.BACK, CameraFacing.restored(null, both))
        assertEquals(CameraFacing.BACK, CameraFacing.restored("obsolete", both))
        assertEquals(CameraFacing.BACK, CameraFacing.restored("FRONT", setOf(CameraFacing.BACK)))
        assertEquals(CameraFacing.FRONT, CameraFacing.restored(null, setOf(CameraFacing.FRONT)))
    }

    @Test fun `front uses its own sensor metadata without double rotating the camera texture`() {
        for (rotation in 0..3) {
            val back = CaptureGeometry.forDisplayRotation(rotation, 90).getOrThrow()
            val front = CaptureGeometry.forDisplayRotation(rotation, 270, CameraFacing.FRONT).getOrThrow()
            assertEquals(270, front.sensorOrientationDeg)
            assertEquals(CameraFacing.FRONT, front.cameraFacing)
            assertEquals(back.rotationArg, front.rotationArg)
            assertEquals(back.encodedWidth to back.encodedHeight, front.encodedWidth to front.encodedHeight)
            assertNotEquals(back, front)
            // Even identical sensor mountings require a fresh preparation when the lens changes.
            assertNotEquals(back, CaptureGeometry.forDisplayRotation(rotation, 90, CameraFacing.FRONT).getOrThrow())
        }
    }

    @Test fun `front mirror compensation preserves asymmetric scene coordinates in all four rotations`() {
        fun rotate(point: Pair<Int, Int>, degrees: Int): Pair<Int, Int> = when ((degrees + 360) % 360) {
            0 -> point
            90 -> -point.second to point.first
            180 -> -point.first to -point.second
            270 -> point.second to -point.first
            else -> error("quarter turns only")
        }
        for (displayRotation in 0..3) {
            val geometry = CaptureGeometry.forDisplayRotation(displayRotation, 270, CameraFacing.FRONT).getOrThrow()
            // StreamBase 2.7.0: cameraOrientation = if (rotation == 0) 270 else rotation - 90.
            val cameraRotation = (geometry.rotationArg + 270) % 360
            val scene = 2 to 5
            val expected = rotate(scene, cameraRotation)
            val frameworkMirrored = -scene.first to scene.second
            val transformed = rotate(frameworkMirrored, cameraRotation)
            val corrected =
                (if (geometry.compensateMirrorHorizontally) -transformed.first else transformed.first) to
                    (if (geometry.compensateMirrorVertically) -transformed.second else transformed.second)
            assertEquals("display rotation $displayRotation", expected, corrected)

            val back = CaptureGeometry.forDisplayRotation(displayRotation, 90).getOrThrow()
            assertFalse(back.compensateMirrorHorizontally)
            assertFalse(back.compensateMirrorVertically)
        }
    }
}
