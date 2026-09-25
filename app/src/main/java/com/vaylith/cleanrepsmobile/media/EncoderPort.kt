package com.vaylith.cleanrepsmobile.media

/**
 * The RootEncoder 2.7.0 calls that [PreviewStateMachine] may make, and nothing else.
 * [S] is the opaque preview surface (`android.view.Surface` in the app), so the
 * machine and its tests need no Android types.
 *
 * Behaviour the machine relies on, read from the library's bytecode rather than
 * from a device:
 * - [prepareVideo] throws `IllegalStateException("Stream, record and preview must
 *   be stopped before prepareVideo")` unless preview, stream and record are all
 *   off. An unsupported camera size throws `IllegalArgumentException` from
 *   `Camera2Source.create`.
 * - [startPreview] throws unless the surface is valid ("Make sure the Surface is
 *   valid") and the preview is off ("Preview already started, stopPreview before
 *   startPreview again"). While streaming it skips the GL and camera start and
 *   only re-attaches, so re-attaching does not interrupt a live stream.
 * - [stopPreview] always means `stopPreview(false)`. While streaming or recording
 *   it only detaches the preview surface; the camera and GL keep running. It
 *   never calls `previewCallback.removeCallbacks()`, which is what left the
 *   preview black after `surfaceDestroyed` under `startPreview(view, true)`.
 */
interface EncoderPort<S> {
    val isOnPreview: Boolean
    val isStreaming: Boolean
    val isRecording: Boolean

    /**
     * `prepareVideo(CaptureGeometry.PREPARE_WIDTH, PREPARE_HEIGHT, bitrate, fps,
     * iFrameInterval, geometry.rotationArg)`; false when the device cannot prepare.
     */
    fun prepareVideo(geometry: CaptureGeometry): Boolean

    /** Prepared once; `stopStream()` keeps the audio encoder prepared. */
    fun prepareAudio(): Boolean

    /** `startPreview(surface, width, height)`. */
    fun startPreview(surface: S, width: Int, height: Int)

    /** `stopPreview(false)`. */
    fun stopPreview()

    fun setPreviewResolution(width: Int, height: Int)

    /** The surface's own validity check (`Surface.isValid`), not a RootEncoder call. */
    fun isSurfaceValid(surface: S): Boolean
}

/**
 * The calls that end a capture, which the state machine never makes: the
 * publisher's [PreviewCoordinator.release] uses them, in its documented order.
 */
interface StreamControl {
    /** `stopRecord()`: finalises the local safety recording. */
    fun stopRecord()

    /** `stopStream()`: ends the SRT stream. */
    fun stopStream()

    /**
     * `release()`: stops the camera, GL and microphone sources and releases them.
     * The stream cannot be used afterwards.
     */
    fun release()
}
