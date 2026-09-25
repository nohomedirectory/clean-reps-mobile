package com.vaylith.cleanrepsmobile.media

/** A preview surface whose validity the test controls, like `Surface.isValid`. */
class FakeSurface(val id: Int) {
    var valid = true
    override fun toString() = "surface#$id${if (valid) "" else "(invalid)"}"
}

/**
 * An [EncoderPort] that throws exactly where RootEncoder 2.7.0 throws (per its
 * bytecode) and also records each illegal call in [violations]. The recording
 * matters because the machine turns port exceptions into CAMERA_ERROR, so a
 * thrown exception alone could hide a broken invariant.
 *
 * [startStream], [stopStream], [startRecord] and [stopRecord] are the caller's
 * side (the publisher), not part of the port. The last three, with [release],
 * are its [StreamControl].
 */
class FakeEncoderPort : EncoderPort<FakeSurface>, StreamControl {
    override var isOnPreview = false
        private set
    override var isStreaming = false
        private set
    override var isRecording = false
        private set

    var preparedGeometry: CaptureGeometry? = null
        private set
    var previewSurface: FakeSurface? = null
        private set
    var successfulPreviewStarts = 0
        private set
    val violations = mutableListOf<String>()
    val calls = mutableListOf<String>()
    /** The prepared geometry at each `startStream`. */
    val streamStarts = mutableListOf<CaptureGeometry>()

    /** When set, `startPreview` fails like a camera held by another app. */
    var startPreviewFailure: String? = null
    /** When set, `prepareVideo` throws like `Camera2Source.create` for an unsupported size. */
    var prepareFailure: String? = null
    /** Geometries the hardware encoder refuses: `prepareVideo` returns false for them. */
    var encoderRefuses: (CaptureGeometry) -> Boolean = { false }
    var audioAvailable = true
    /** When set, `stopStream` throws after stopping, to prove a release goes on. */
    var stopStreamFailure: String? = null
    var released = false
        private set

    override fun prepareVideo(geometry: CaptureGeometry): Boolean {
        calls += "prepareVideo(${geometry.rotationArg})"
        if (released) violations += "prepareVideo after release"
        if (isStreaming || isRecording || isOnPreview) {
            violations += "I1: prepareVideo(${geometry.label}) with preview=$isOnPreview stream=$isStreaming record=$isRecording"
            throw IllegalStateException("Stream, record and preview must be stopped before prepareVideo")
        }
        prepareFailure?.let {
            preparedGeometry = null
            throw IllegalArgumentException(it)
        }
        if (encoderRefuses(geometry)) {
            preparedGeometry = null
            return false
        }
        preparedGeometry = geometry
        return true
    }

    override fun prepareAudio(): Boolean {
        calls += "prepareAudio"
        return audioAvailable
    }

    override fun startPreview(surface: FakeSurface, width: Int, height: Int) {
        calls += "startPreview($surface,${width}x$height)"
        if (released) violations += "startPreview after release"
        if (!surface.valid) {
            violations += "I2: startPreview with invalid $surface"
            throw IllegalArgumentException("Make sure the Surface is valid")
        }
        if (isOnPreview) {
            violations += "I2: startPreview while already on preview"
            throw IllegalStateException("Preview already started, stopPreview before startPreview again")
        }
        // Not a bytecode fact: the machine's own rule is never to start an unprepared preview.
        if (preparedGeometry == null && !isStreaming && !isRecording) {
            violations += "startPreview before a successful prepareVideo"
            throw IllegalStateException("Camera source is not initialised")
        }
        startPreviewFailure?.let { throw RuntimeException(it) }
        isOnPreview = true
        previewSurface = surface
        successfulPreviewStarts++
    }

    override fun stopPreview() {
        calls += "stopPreview"
        isOnPreview = false
        previewSurface = null
    }

    override fun setPreviewResolution(width: Int, height: Int) {
        calls += "setPreviewResolution(${width}x$height)"
    }

    override fun isSurfaceValid(surface: FakeSurface) = surface.valid

    fun startStream() {
        calls += "startStream"
        check(!isStreaming) { "already streaming" }
        val geometry = checkNotNull(preparedGeometry) { "startStream before prepareVideo" }
        isStreaming = true
        streamStarts += geometry
    }

    /** RootEncoder re-prepares the encoders with the stored parameters: the geometry is unchanged. */
    override fun stopStream() {
        calls += "stopStream"
        isStreaming = false
        stopStreamFailure?.let { throw IllegalStateException(it) }
    }

    fun startRecord() {
        calls += "startRecord"
        check(isStreaming) { "the safety recording runs only while streaming" }
        isRecording = true
    }

    override fun stopRecord() {
        calls += "stopRecord"
        isRecording = false
    }

    /** StreamBase.release(): the stream is unusable afterwards. */
    override fun release() {
        calls += "release"
        // Not bytecode facts (release() would stop them itself): the publisher's own rules.
        if (released) violations += "release called twice"
        if (isStreaming || isRecording || isOnPreview) {
            violations += "release order: preview=$isOnPreview stream=$isStreaming record=$isRecording still on"
        }
        released = true
    }
}
