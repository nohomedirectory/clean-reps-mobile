package com.vaylith.cleanrepsmobile.media

import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticStep
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticsLog
import com.vaylith.cleanrepsmobile.diagnostics.FailureKind
import com.vaylith.cleanrepsmobile.diagnostics.StepMessages

/**
 * The publisher's preview and geometry logic, free of RootEncoder and Android
 * types so it runs in JVM tests over a fake [EncoderPort].
 *
 * It resolves the upright [CaptureGeometry] from the display rotation and the
 * selected camera's sensor orientation, drives the [PreviewStateMachine], and reports
 * preview statuses only through [PublisherListener.onPreviewStatus] (P-SEP):
 * nothing here calls `onPublisherStatus`, so a preview event can never move
 * capture readiness or tear down a live capture. Camera callbacks are preview
 * inputs too: a camera failure is CAMERA_ERROR, even while streaming. Port and
 * camera failures are recorded in [diagnostics] with their exception class and
 * cause; the listener only ever gets fixed owner-facing text. Every input must
 * arrive on one thread (the main thread in the app).
 */
internal class PreviewCoordinator<S>(
    private val port: EncoderPort<S>,
    private val listener: PublisherListener,
    private val diagnostics: DiagnosticsLog?,
    /** SENSOR_ORIENTATION of the selected camera; null when it could not be read. */
    sensorOrientationDeg: Int?,
    /** Runs an action after a delay in ms, on the input thread: the automatic retry after "camera in use". */
    private val schedule: (Long, () -> Unit) -> Unit,
    initialCameraFacing: CameraFacing = CameraFacing.BACK,
    /** A fresh read of the display rotation (0..3); null when it is unavailable. */
    private val displayRotation: () -> Int?,
) {
    var sensorOrientationDeg: Int? = sensorOrientationDeg
        private set
    var cameraFacing: CameraFacing = initialCameraFacing
        private set
    private val machine = PreviewStateMachine(LoggingPort(port), ::report)
    private var released = false
    /** The one automatic retry after "camera in use" is spent until the camera opens again. */
    private var inUseRetryUsed = false
    /** Incremented to cancel a scheduled automatic retry. */
    private var retryGeneration = 0

    val preparedGeometry: CaptureGeometry? get() = machine.preparedGeometry
    val hasSurface: Boolean get() = machine.hasSurface
    val canChangeCamera: Boolean get() = !released && machine.canChangeCamera

    /**
     * Accept a lens change only while video and recording are off. A true result means
     * the requested lens is selected; asynchronous camera/prepare failures still use
     * the existing preview-error path. No capture or source epoch is changed here.
     */
    fun selectCamera(facing: CameraFacing, sensorOrientation: Int?, changeSource: () -> Unit): Boolean {
        if (!canChangeCamera || facing == cameraFacing) return false
        val geometry = geometryFor(displayRotation(), sensorOrientation, facing) ?: return false
        retryGeneration++
        inUseRetryUsed = false
        var selected = false
        guarded {
            diagnostics?.info(DiagnosticStep.CAMERA_OPEN, "select ${facing.label.lowercase()} camera; sensor orientation $sensorOrientation")
            machine.changeCamera(geometry) {
                changeSource()
                sensorOrientationDeg = sensorOrientation
                cameraFacing = facing
                selected = true
            }
        }
        return selected
    }

    fun surfaceAvailable(surface: S, width: Int, height: Int) = guarded { machine.surfaceAvailable(surface, width, height) }

    fun surfaceChanged(width: Int, height: Int) = guarded { machine.surfaceChanged(width, height) }

    fun surfaceLost() = guarded { machine.surfaceLost() }

    /** The preview was attached or the display rotated; applied now, or kept pending while streaming (I3). */
    fun displayRotationChanged(rotation: Int?) = guarded {
        geometryFor(rotation)?.let(machine::geometryRequested)
    }

    /**
     * Go live (I8): the display rotation is read now, not taken from the last
     * rotation callback. The encoder is re-prepared first when that geometry
     * differs from the prepared one; call `startStream` only on [StreamGate.Ready].
     */
    fun goLive(): StreamGate {
        if (released) return StreamGate.NotReady("The camera was released")
        val geometry = geometryFor(displayRotation())
            ?: return StreamGate.NotReady(machine.cameraError ?: "The display rotation could not be read")
        return try {
            machine.streamRequested(geometry)
        } catch (error: Exception) {
            unexpected(error)
            StreamGate.NotReady(machine.cameraError ?: "The video encoder could not be prepared")
        }
    }

    fun streamStarted() = guarded { machine.streamStarted() }

    /** After both the stream and the local recording have stopped, including after a failed start. */
    fun streamStopped() = guarded { machine.streamStopped() }

    /** RootEncoder's `onCameraOpened`: the camera works, so the automatic retry is available again. */
    fun cameraOpened() = guarded {
        inUseRetryUsed = false
        diagnostics?.ok(DiagnosticStep.CAMERA_OPEN, "camera opened")
    }

    /**
     * RootEncoder's `onCameraError` text, e.g. `Open camera failed: 1`. Only the
     * diagnostics log sees it, redacted; the preview status gets owner text.
     */
    fun cameraError(detail: String) = guarded {
        diagnostics?.fail(DiagnosticStep.CAMERA_OPEN, "camera error: $detail")
        cameraFailed(inUse = CAMERA_IN_USE.matches(detail))
    }

    /** RootEncoder's `onCameraDisconnected`: another app took the camera, which counts as in use. */
    fun cameraDisconnected() = guarded {
        diagnostics?.fail(DiagnosticStep.CAMERA_OPEN, "camera disconnected")
        cameraFailed(inUse = true)
    }

    /** "Reopen camera": one attempt now. A pending automatic retry is cancelled. */
    fun reopenCamera() {
        retryGeneration++
        retry("reopen camera requested")
    }

    /**
     * Ends the capture graph in this order: local recording, stream, preview,
     * then [StreamControl.release]. A failed step is logged and the next one
     * still runs, so the camera is always freed. Idempotent. Later inputs are
     * ignored and a scheduled automatic retry is cancelled. Nothing is reported
     * to the listener: the caller owns the end of the capture.
     */
    fun release(control: StreamControl) {
        if (released) return
        released = true
        retryGeneration++
        releaseStep(DiagnosticStep.PUBLISHER_START, "stopRecord") { if (port.isRecording) control.stopRecord() }
        releaseStep(DiagnosticStep.PUBLISHER_START, "stopStream") { if (port.isStreaming) control.stopStream() }
        // stopPreview(false), before StreamBase.release() could stop it with its own default.
        releaseStep(DiagnosticStep.PREVIEW_START, "stopPreview") { if (port.isOnPreview) port.stopPreview() }
        releaseStep(DiagnosticStep.CAMERA_OPEN, "release") { control.release() }
        diagnostics?.info(DiagnosticStep.PUBLISHER_START, "publisher released")
    }

    private fun cameraFailed(inUse: Boolean) {
        machine.cameraError(CAMERA_FAILED)
        if (!inUse || inUseRetryUsed) return
        // The other app is often still closing the camera: try once more shortly.
        inUseRetryUsed = true
        val generation = ++retryGeneration
        diagnostics?.info(DiagnosticStep.CAMERA_OPEN, "camera in use: one automatic retry in $CAMERA_IN_USE_RETRY_MS ms")
        schedule(CAMERA_IN_USE_RETRY_MS) {
            if (generation == retryGeneration) retry("automatic retry after camera in use")
        }
    }

    private fun retry(reason: String) = guarded {
        diagnostics?.info(DiagnosticStep.CAMERA_OPEN, if (machine.cameraError == null) "$reason: no camera error" else reason)
        machine.retry()
    }

    private fun geometryFor(
        rotation: Int?,
        sensor: Int? = sensorOrientationDeg,
        facing: CameraFacing = cameraFacing,
    ): CaptureGeometry? {
        if (rotation == null) {
            diagnostics?.info(DiagnosticStep.PREVIEW_START, "display rotation unavailable")
            return null
        }
        val geometry = if (sensor == null) {
            Result.failure(IllegalStateException("The ${if (facing == CameraFacing.BACK) "back" else "front"} camera's orientation could not be read"))
        } else {
            CaptureGeometry.forDisplayRotation(rotation, sensor, facing)
        }
        return geometry.getOrElse { error ->
            val reason = error.message ?: "Unsupported camera orientation"
            // A fixed property of the phone: report it once, not on every rotation.
            if (machine.cameraError != reason) {
                diagnostics?.fail(DiagnosticStep.CAMERA_OPEN, reason)
                machine.cameraError(reason)
            }
            null
        }
    }

    private fun report(status: PreviewStatus) {
        when (status) {
            PreviewStatus.Ready -> diagnostics?.ok(DiagnosticStep.PREVIEW_START, "preview ${preparedGeometry?.label.orEmpty()}")
            PreviewStatus.Lost -> diagnostics?.info(DiagnosticStep.PREVIEW_START, "preview surface lost")
            is PreviewStatus.RotationPending ->
                diagnostics?.info(DiagnosticStep.PREVIEW_START, "rotation to ${status.geometry.label} waits until video stops")
            // Failures are recorded where they happen (LoggingPort, geometryFor, unexpected).
            PreviewStatus.Starting, is PreviewStatus.CameraError -> Unit
        }
        listener.onPreviewStatus(status, detail(status))
    }

    private fun detail(status: PreviewStatus): String = when (status) {
        PreviewStatus.Starting -> "Starting the camera preview."
        PreviewStatus.Ready -> "Camera preview ready" + preparedGeometry?.let { " (${it.label})" }.orEmpty() + "."
        PreviewStatus.Lost -> "Camera preview paused while the screen is away."
        is PreviewStatus.CameraError -> status.reason
        is PreviewStatus.RotationPending -> CaptureGeometry.turnedText(preparedGeometry?.orientation)
    }

    /** Runs one input unless the publisher was released; an unexpected exception becomes a camera error. */
    private inline fun guarded(block: () -> Unit) {
        if (released) return
        try {
            block()
        } catch (error: Exception) {
            unexpected(error)
        }
    }

    private inline fun releaseStep(step: DiagnosticStep, action: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            diagnostics?.fail(step, "release: $action failed", error)
        }
    }

    /** A port call the machine does not guard failed; show it as a camera error instead of crashing. */
    private fun unexpected(error: Exception) {
        diagnostics?.fail(DiagnosticStep.PREVIEW_START, "unexpected camera preview failure", error)
        machine.cameraError("Camera preview failed (${error.javaClass.simpleName})")
    }

    /** Records each failed port call with its exception class before the machine turns it into CAMERA_ERROR. */
    private inner class LoggingPort(private val port: EncoderPort<S>) : EncoderPort<S> by port {
        override fun prepareVideo(geometry: CaptureGeometry): Boolean {
            val prepared = logged(DiagnosticStep.CAMERA_OPEN, "prepareVideo ${geometry.label}") { port.prepareVideo(geometry) }
            if (!prepared) diagnostics?.fail(DiagnosticStep.CAMERA_OPEN, "prepareVideo ${geometry.label} returned false")
            return prepared
        }

        override fun prepareAudio(): Boolean {
            val prepared = logged(DiagnosticStep.PUBLISHER_START, "prepareAudio") { port.prepareAudio() }
            if (!prepared) diagnostics?.fail(DiagnosticStep.PUBLISHER_START, "prepareAudio returned false")
            return prepared
        }

        override fun startPreview(surface: S, width: Int, height: Int) =
            logged(DiagnosticStep.PREVIEW_START, "startPreview ${width}x$height") { port.startPreview(surface, width, height) }

        private inline fun <T> logged(step: DiagnosticStep, action: String, block: () -> T): T = try {
            block()
        } catch (error: Exception) {
            diagnostics?.fail(step, "$action failed", error)
            throw error
        }
    }

    companion object {
        /** Delay before the one automatic retry after "camera in use". */
        const val CAMERA_IN_USE_RETRY_MS = 1_000L

        /** Owner text for every camera callback failure; the cause goes only to the diagnostics log. */
        val CAMERA_FAILED: String = StepMessages.message(DiagnosticStep.CAMERA_OPEN, FailureKind.Camera)

        /**
         * RootEncoder's `onError(device, code)` text for `CameraDevice.StateCallback`
         * ERROR_CAMERA_IN_USE (1) and ERROR_MAX_CAMERAS_IN_USE (2).
         */
        private val CAMERA_IN_USE = Regex("""Open camera failed: [12]""")
    }
}
