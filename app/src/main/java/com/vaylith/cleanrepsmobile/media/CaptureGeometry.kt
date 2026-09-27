package com.vaylith.cleanrepsmobile.media

import com.vaylith.cleanrepsmobile.model.CaptureOrientation

/**
 * Encoder geometry that keeps transmitted frames upright for the current display
 * rotation. Pure; the caller reads the display rotation (`Surface.ROTATION_0` to
 * `ROTATION_270`, i.e. 0..3) and the selected camera's `SENSOR_ORIENTATION`.
 *
 * This models RootEncoder 2.7.0 as read from its bytecode, not yet from a device:
 * `prepareVideo(PREPARE_WIDTH, PREPARE_HEIGHT, ..., rotationArg)` gives the
 * encoder (height, width) for a rotation of 90 or 270 and (width, height)
 * otherwise, and the upright rotation is `CameraHelper.getCameraOrientation`:
 * display 0 -> 90, 1 -> 0, 2 -> 270, 3 -> 180.
 *
 * That mapping depends on the display rotation alone, for either sensor mounting.
 * `CameraRender.draw()` samples the camera texture through
 * `SurfaceTexture.getTransformMatrix`, and Camera2 already bakes the sensor
 * orientation into that matrix. Within RootEncoder, `SENSOR_ORIENTATION` is read
 * only by `Camera2ApiManager.enableFaceDetection`. A 270-degree sensor therefore
 * uses the 90-degree mapping. Adding 180 would double-correct and send the video
 * upside down. The 270 case is still unverified on hardware and is flagged for
 * diagnostics. Sensors at 0 or 180 degrees are refused.
 *
 * The constructor is private: every geometry comes from [forDisplayRotation].
 */
@ConsistentCopyVisibility
data class CaptureGeometry private constructor(
    val displayRotation: Int,
    val sensorOrientationDeg: Int,
    val rotationArg: Int,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val orientation: CaptureOrientation,
    val sensorCompensationUnverified: Boolean,
    val cameraFacing: CameraFacing,
) {
    val displayRotationDeg: Int get() = displayRotation * 90

    /**
     * Camera2's default front-camera mirror is part of SurfaceTexture's transform.
     * RootEncoder 2.7.0 StreamBase rotates that texture by rotationArg - 90, so
     * removing its mirror AFTER that rotation needs H in portrait and V in
     * landscape (R H R^-1). Apply the same correction to preview, encoder and photo.
     */
    val compensateMirrorHorizontally: Boolean
        get() = cameraFacing == CameraFacing.FRONT && orientation == CaptureOrientation.PORTRAIT
    val compensateMirrorVertically: Boolean
        get() = cameraFacing == CameraFacing.FRONT && orientation == CaptureOrientation.LANDSCAPE

    /** Shown on the LIVE pill, e.g. `landscape 1280x720`. */
    val label: String get() = "${orientation.wireValue} ${encodedWidth}x$encodedHeight"

    companion object {
        /** The only size ever passed to `prepareVideo`; the library itself swaps it for 90 and 270. */
        const val PREPARE_WIDTH = 1280
        const val PREPARE_HEIGHT = 720

        /** `CameraHelper.getCameraOrientation`, indexed by display rotation. */
        private val UPRIGHT_ROTATION = intArrayOf(90, 0, 270, 180)

        /**
         * The one "phone turned" text, for both the physical-rotation banner and the preview's
         * RotationPending detail, naming the orientation the running video keeps.
         */
        fun turnedText(videoOrientation: CaptureOrientation?): String =
            "Phone turned - video stays ${videoOrientation?.wireValue ?: "as it started"}. Stop video to switch."

        fun forDisplayRotation(
            displayRotation: Int,
            sensorOrientation: Int,
            cameraFacing: CameraFacing = CameraFacing.BACK,
        ): Result<CaptureGeometry> {
            if (displayRotation !in 0..3) {
                return Result.failure(IllegalArgumentException("Unsupported display rotation ($displayRotation)"))
            }
            if (sensorOrientation != 90 && sensorOrientation != 270) {
                return Result.failure(IllegalArgumentException("Unsupported camera orientation ($sensorOrientation degrees)"))
            }
            val rotationArg = UPRIGHT_ROTATION[displayRotation]
            val portrait = rotationArg == 90 || rotationArg == 270
            return Result.success(
                CaptureGeometry(
                    displayRotation = displayRotation,
                    sensorOrientationDeg = sensorOrientation,
                    rotationArg = rotationArg,
                    encodedWidth = if (portrait) PREPARE_HEIGHT else PREPARE_WIDTH,
                    encodedHeight = if (portrait) PREPARE_WIDTH else PREPARE_HEIGHT,
                    orientation = if (portrait) CaptureOrientation.PORTRAIT else CaptureOrientation.LANDSCAPE,
                    sensorCompensationUnverified = sensorOrientation == 270,
                    cameraFacing = cameraFacing,
                ),
            )
        }
    }
}
