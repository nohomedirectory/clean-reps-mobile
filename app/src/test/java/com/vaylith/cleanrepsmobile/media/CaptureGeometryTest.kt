package com.vaylith.cleanrepsmobile.media

import com.vaylith.cleanrepsmobile.model.CaptureOrientation
import com.vaylith.cleanrepsmobile.model.CaptureOrientation.LANDSCAPE
import com.vaylith.cleanrepsmobile.model.CaptureOrientation.PORTRAIT
import org.junit.Assert.*
import org.junit.Test

class CaptureGeometryTest {
    private data class Expected(
        val rotationArg: Int,
        val encodedWidth: Int,
        val encodedHeight: Int,
        val orientation: CaptureOrientation,
        val label: String,
    )

    private fun geometry(displayRotation: Int, sensorOrientation: Int) =
        CaptureGeometry.forDisplayRotation(displayRotation, sensorOrientation).getOrThrow()

    private fun CaptureGeometry.observed() = Expected(rotationArg, encodedWidth, encodedHeight, orientation, label)

    @Test fun `sensor 90 gives the upright rotation argument and encoded size for every display rotation`() {
        val expected = mapOf(
            0 to Expected(90, 720, 1280, PORTRAIT, "portrait 720x1280"),
            1 to Expected(0, 1280, 720, LANDSCAPE, "landscape 1280x720"),
            2 to Expected(270, 720, 1280, PORTRAIT, "portrait 720x1280"),
            3 to Expected(180, 1280, 720, LANDSCAPE, "landscape 1280x720"),
        )
        expected.forEach { (displayRotation, want) ->
            val geometry = geometry(displayRotation, 90)
            assertEquals("display rotation $displayRotation", want, geometry.observed())
            assertFalse(geometry.sensorCompensationUnverified)
            assertEquals(displayRotation, geometry.displayRotation)
            assertEquals(displayRotation * 90, geometry.displayRotationDeg)
            assertEquals(90, geometry.sensorOrientationDeg)
        }
    }

    @Test fun `rotation argument equals RootEncoder CameraHelper getCameraOrientation for both sensors`() {
        // RootEncoder 2.7.0 CameraHelper.getCameraOrientation(context), read from its bytecode.
        // It depends only on the display rotation.
        val rootEncoderGetCameraOrientation = mapOf(0 to 90, 1 to 0, 2 to 270, 3 to 180)
        for (sensorOrientation in listOf(90, 270)) {
            assertEquals(
                "sensor $sensorOrientation",
                rootEncoderGetCameraOrientation,
                (0..3).associateWith { geometry(it, sensorOrientation).rotationArg },
            )
        }
    }

    // Deliberate change (clean-reps-pcp-epic-k4r.66): the first version expected sensor 270 to add
    // 180 degrees. RootEncoder 2.7.0's CameraRender.draw() samples the camera texture through
    // SurfaceTexture.getTransformMatrix, and Camera2 already bakes the sensor orientation into that
    // matrix. CameraHelper.getCameraOrientation reads only the display rotation, and within the
    // library SENSOR_ORIENTATION is read only by Camera2ApiManager.enableFaceDetection. So a
    // 270-degree sensor needs the same rotation argument as a 90-degree one; adding 180 would send
    // the video upside down. Hardware has not confirmed this, so the result stays flagged.
    @Test fun `sensor 270 uses the sensor 90 mapping and is flagged unverified`() {
        val expected = mapOf(
            0 to Expected(90, 720, 1280, PORTRAIT, "portrait 720x1280"),
            1 to Expected(0, 1280, 720, LANDSCAPE, "landscape 1280x720"),
            2 to Expected(270, 720, 1280, PORTRAIT, "portrait 720x1280"),
            3 to Expected(180, 1280, 720, LANDSCAPE, "landscape 1280x720"),
        )
        expected.forEach { (displayRotation, want) ->
            val geometry = geometry(displayRotation, 270)
            assertEquals("display rotation $displayRotation", want, geometry.observed())
            assertTrue(geometry.sensorCompensationUnverified)
            assertEquals(270, geometry.sensorOrientationDeg)
        }
    }

    @Test fun `sensor 270 and sensor 90 differ only in the unverified flag`() {
        for (displayRotation in 0..3) {
            val sensor90 = geometry(displayRotation, 90)
            val sensor270 = geometry(displayRotation, 270)
            assertEquals("display rotation $displayRotation", sensor90.observed(), sensor270.observed())
            assertFalse(sensor90.sensorCompensationUnverified)
            assertTrue(sensor270.sensorCompensationUnverified)
        }
    }

    @Test fun `prepareVideo always gets 1280x720 and the encoded size is the library's swap of it`() {
        assertEquals(1280, CaptureGeometry.PREPARE_WIDTH)
        assertEquals(720, CaptureGeometry.PREPARE_HEIGHT)
        for (sensorOrientation in listOf(90, 270)) for (displayRotation in 0..3) {
            val geometry = geometry(displayRotation, sensorOrientation)
            // StreamBase.prepareVideo: setEncoderSize(height, width) for rotation 90 or 270, else (width, height).
            val librarySwapped = geometry.rotationArg == 90 || geometry.rotationArg == 270
            val librarySize = if (librarySwapped) {
                CaptureGeometry.PREPARE_HEIGHT to CaptureGeometry.PREPARE_WIDTH
            } else {
                CaptureGeometry.PREPARE_WIDTH to CaptureGeometry.PREPARE_HEIGHT
            }
            assertEquals(geometry.toString(), librarySize, geometry.encodedWidth to geometry.encodedHeight)
            assertEquals(geometry.toString(), librarySwapped, geometry.orientation == PORTRAIT)
        }
    }

    @Test fun `unsupported camera orientations fail and never give a geometry`() {
        for (sensorOrientation in listOf(0, 180, 45, 360, -90)) for (displayRotation in 0..3) {
            val result = CaptureGeometry.forDisplayRotation(displayRotation, sensorOrientation)
            assertNull(result.getOrNull())
            val error = result.exceptionOrNull()
            assertTrue("$error", error is IllegalArgumentException)
            assertTrue("$error", error!!.message!!.startsWith("Unsupported camera orientation"))
        }
    }

    @Test fun `out-of-range display rotations fail and never give a geometry`() {
        // 90 and 270 are degrees passed where a Surface.ROTATION_* index belongs.
        for (displayRotation in listOf(4, -1, 90, 270, Int.MIN_VALUE)) for (sensorOrientation in listOf(90, 270, 0)) {
            val result = CaptureGeometry.forDisplayRotation(displayRotation, sensorOrientation)
            assertNull(result.getOrNull())
            val error = result.exceptionOrNull()
            assertTrue("$error", error is IllegalArgumentException)
            assertTrue("$error", error!!.message!!.startsWith("Unsupported display rotation"))
        }
    }

    @Test fun `a 180-degree flip is a different geometry although its size and label are unchanged`() {
        val landscape = geometry(1, 90)
        val reverseLandscape = geometry(3, 90)
        assertEquals(landscape.label, reverseLandscape.label)
        assertNotEquals(landscape, reverseLandscape)
        assertEquals(geometry(1, 90), landscape)
    }
}
