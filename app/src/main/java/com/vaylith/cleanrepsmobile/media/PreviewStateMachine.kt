package com.vaylith.cleanrepsmobile.media

/** Preview-only statuses. They never describe the transport (P-SEP). */
sealed interface PreviewStatus {
    /** PREVIEW_STARTING: `startPreview` is about to be called. */
    data object Starting : PreviewStatus

    /** PREVIEW_READY: `startPreview` returned normally. */
    data object Ready : PreviewStatus

    /** PREVIEW_LOST: the preview surface went away. */
    data object Lost : PreviewStatus

    /** CAMERA_ERROR: the camera or encoder failed; [PreviewStateMachine.retry] tries again once. */
    data class CameraError(val reason: String) : PreviewStatus

    /** ROTATION_PENDING: a new geometry waits until streaming and recording stop. */
    data class RotationPending(val geometry: CaptureGeometry) : PreviewStatus
}

/** The answer to a Go-live request: the caller may call `startStream` only on [Ready]. */
sealed interface StreamGate {
    data class Ready(val geometry: CaptureGeometry) : StreamGate
    data class NotReady(val reason: String) : StreamGate
}

/**
 * Owns the preview surface lifecycle and the prepared encoder geometry over an
 * [EncoderPort]. Pure: no Android types, no threads, no timers. The caller
 * delivers every input on one thread.
 *
 * Invariants (enforced by `PreviewStateMachineTest`, including a seeded fuzz):
 * - I1: `prepareVideo` only while preview, stream and record are all off.
 * - I2: `startPreview` only with a valid surface and while the preview is off.
 * - I3: while streaming or recording, the prepared geometry never changes; a
 *   requested geometry is kept as pending.
 * - I4: after [surfaceLost] the preview is off and the surface is not retained.
 * - I5: after [surfaceChanged] with a valid surface, a known geometry and no
 *   camera error, the preview is on, whatever came before.
 * - I6: [PreviewStatus.Ready] is emitted exactly when `startPreview` returned.
 * - I7: [streamStopped] applies any pending geometry.
 * - I8: [streamRequested] answers [StreamGate.Ready] only when the prepared
 *   geometry equals the requested one, re-preparing first if needed, and later
 *   geometry requests stay pending until [streamStopped], so a stream never
 *   starts with a stale geometry.
 *
 * Every [streamRequested] that answered Ready must be closed by [streamStopped],
 * after both the stream and the local recording have stopped, including when
 * `startStream` itself failed.
 */
class PreviewStateMachine<S>(
    private val port: EncoderPort<S>,
    private val onStatus: (PreviewStatus) -> Unit,
) {
    /** The geometry `prepareVideo` last succeeded with; null before that or after a failed prepare. */
    var preparedGeometry: CaptureGeometry? = null
        private set

    /** The latest geometry from [geometryRequested] or [streamRequested]. */
    var requestedGeometry: CaptureGeometry? = null
        private set

    /** The geometry of the Go-live request that is open until [streamStopped]. */
    var streamGeometry: CaptureGeometry? = null
        private set

    var cameraError: String? = null
        private set

    private var surface: S? = null
    private var width = 0
    private var height = 0
    private var audioPrepared = false

    val hasSurface: Boolean get() = surface != null

    /** A requested geometry that cannot be applied yet because a stream is open or running. */
    val pendingGeometry: CaptureGeometry?
        get() = requestedGeometry?.takeIf { it != preparedGeometry && (busy || streamGeometry != null) }

    private val busy: Boolean get() = port.isStreaming || port.isRecording

    fun surfaceAvailable(surface: S, width: Int, height: Int) {
        if (surface != this.surface && port.isOnPreview) {
            // A new surface without a loss callback: detach the old one first (I2).
            port.stopPreview()
        }
        this.surface = surface
        this.width = width
        this.height = height
        ensurePreview()
    }

    fun surfaceChanged(width: Int, height: Int) {
        if (surface == null) return
        val resized = width != this.width || height != this.height
        this.width = width
        this.height = height
        if (port.isOnPreview) {
            if (resized && width > 0 && height > 0) port.setPreviewResolution(width, height)
        } else {
            ensurePreview()
        }
    }

    fun surfaceLost() {
        val hadSurface = surface != null
        surface = null
        width = 0
        height = 0
        // stopPreview(false): while streaming this only detaches; the stream keeps running.
        if (port.isOnPreview) port.stopPreview()
        if (hadSurface) onStatus(PreviewStatus.Lost)
    }

    /** The display rotation changed while the orientation is not locked. */
    fun geometryRequested(geometry: CaptureGeometry) {
        requestedGeometry = geometry
        if (geometry == preparedGeometry) return
        if (busy || streamGeometry != null) {
            onStatus(PreviewStatus.RotationPending(geometry))
            return
        }
        applyGeometry(geometry)
    }

    /**
     * Go live, with the geometry read after the orientation lock. Re-prepares first
     * when it differs from the prepared geometry (preview stop, `prepareVideo`,
     * preview start); the caller calls `startStream` only after [StreamGate.Ready].
     */
    fun streamRequested(geometry: CaptureGeometry): StreamGate {
        if (port.isStreaming) {
            return if (geometry == preparedGeometry) {
                StreamGate.Ready(geometry)
            } else {
                StreamGate.NotReady("A stream is already running with ${preparedGeometry?.label ?: "an unknown geometry"}")
            }
        }
        requestedGeometry = geometry
        // A new Go-live request supersedes an earlier one that never started.
        streamGeometry = null
        if (geometry != preparedGeometry || !audioPrepared) {
            if (port.isRecording) return StreamGate.NotReady("Recording must stop before the video can be prepared")
            if (!applyGeometry(geometry)) {
                return StreamGate.NotReady(cameraError ?: "The video encoder could not be prepared")
            }
        }
        streamGeometry = geometry
        return StreamGate.Ready(geometry)
    }

    fun streamStarted() {
        if (streamGeometry == null) streamGeometry = preparedGeometry
    }

    /** Called after both the stream and the recording have stopped. */
    fun streamStopped() {
        streamGeometry = null
        if (busy) return
        val target = requestedGeometry
        if (target != null && target != preparedGeometry) applyGeometry(target) else ensurePreview()
    }

    fun cameraError(message: String) {
        cameraError = message
        onStatus(PreviewStatus.CameraError(message))
    }

    /** One attempt to bring the camera back; a new failure waits for the next call. */
    fun retry() {
        if (cameraError == null) return
        cameraError = null
        if (port.isOnPreview) port.stopPreview()
        ensurePreview()
    }

    /** Prepares [target] (I1: never while busy) and restarts the preview; false if it failed. */
    private fun applyGeometry(target: CaptureGeometry): Boolean {
        if (busy) {
            onStatus(PreviewStatus.RotationPending(target))
            return false
        }
        if (port.isOnPreview) port.stopPreview()
        val prepared = try {
            port.prepareVideo(target)
        } catch (error: Exception) {
            preparedGeometry = null
            fail("The camera cannot provide ${target.label}: ${error.message ?: error.javaClass.simpleName}")
            return false
        }
        if (!prepared) {
            preparedGeometry = null
            fail("This device cannot prepare the video encoder for ${target.label}")
            return false
        }
        preparedGeometry = target
        if (!audioPrepared) {
            audioPrepared = try {
                port.prepareAudio()
            } catch (_: Exception) {
                false
            }
            if (!audioPrepared) {
                fail("This device cannot prepare the microphone encoder")
                return false
            }
        }
        ensurePreview()
        return true
    }

    private fun ensurePreview() {
        if (cameraError != null || port.isOnPreview) return
        val current = surface ?: return
        if (width <= 0 || height <= 0 || !port.isSurfaceValid(current)) return
        if (preparedGeometry == null) {
            val target = streamGeometry ?: requestedGeometry ?: return
            if (!busy) applyGeometry(target)
            return
        }
        onStatus(PreviewStatus.Starting)
        try {
            port.startPreview(current, width, height)
        } catch (error: Exception) {
            fail("Camera preview failed: ${error.message ?: error.javaClass.simpleName}")
            return
        }
        onStatus(PreviewStatus.Ready)
    }

    private fun fail(reason: String) {
        cameraError = reason
        onStatus(PreviewStatus.CameraError(reason))
    }
}
