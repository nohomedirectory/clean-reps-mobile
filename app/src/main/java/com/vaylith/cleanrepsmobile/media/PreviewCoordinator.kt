package com.vaylith.cleanrepsmobile.media

import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticStep
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticsLog

/**
 * The publisher's preview and geometry logic, free of RootEncoder and Android
 * types so it runs in JVM tests over a fake [EncoderPort].
 *
 * It resolves the upright [CaptureGeometry] from the display rotation and the
 * back camera's sensor orientation, drives the [PreviewStateMachine], and reports
 * preview statuses only through [PublisherListener.onPreviewStatus] (P-SEP):
 * nothing here calls `onPublisherStatus`, so a preview event can never move
 * capture readiness or tear down a live capture. Port failures are recorded in
 * [diagnostics] with their exception class. Every input must arrive on one
 * thread (the main thread in the app).
 */
internal class PreviewCoordinator<S>(
    port: EncoderPort<S>,
    private val listener: PublisherListener,
    private val diagnostics: DiagnosticsLog?,
    /** SENSOR_ORIENTATION of the first back camera; null when it could not be read. */
    val sensorOrientationDeg: Int?,
    /** A fresh read of the display rotation (0..3); null when it is unavailable. */
    private val displayRotation: () -> Int?,
) {
    private val machine = PreviewStateMachine(LoggingPort(port), ::report)

    val preparedGeometry: CaptureGeometry? get() = machine.preparedGeometry
    val hasSurface: Boolean get() = machine.hasSurface

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

    private fun geometryFor(rotation: Int?): CaptureGeometry? {
        if (rotation == null) {
            diagnostics?.info(DiagnosticStep.PREVIEW_START, "display rotation unavailable")
            return null
        }
        val sensor = sensorOrientationDeg
        val geometry = if (sensor == null) {
            Result.failure(IllegalStateException("The back camera's orientation could not be read"))
        } else {
            CaptureGeometry.forDisplayRotation(rotation, sensor)
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
        is PreviewStatus.RotationPending ->
            "Phone turned — video stays ${preparedGeometry?.orientation?.wireValue ?: "as it started"}. Stop video to switch."
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            unexpected(error)
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
}
