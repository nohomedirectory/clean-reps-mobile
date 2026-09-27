package com.vaylith.cleanrepsmobile.media

import android.view.Surface
import com.pedro.library.srt.SrtStream

/**
 * [EncoderPort] and [StreamControl] over RootEncoder 2.7.0's [SrtStream]. Video is
 * always prepared at [CaptureGeometry.PREPARE_WIDTH] x [CaptureGeometry.PREPARE_HEIGHT]
 * with the geometry's rotation argument; the library swaps the encoder size itself.
 * [stopPreview] is always `stopPreview(false)`, never the variant that removes
 * the preview callbacks.
 */
internal class SrtStreamEncoderPort(private val stream: SrtStream) : EncoderPort<Surface>, StreamControl {
    override val isOnPreview: Boolean get() = stream.isOnPreview
    override val isStreaming: Boolean get() = stream.isStreaming
    override val isRecording: Boolean get() = stream.isRecording

    override fun prepareVideo(geometry: CaptureGeometry): Boolean {
        val prepared = stream.prepareVideo(
            CaptureGeometry.PREPARE_WIDTH,
            CaptureGeometry.PREPARE_HEIGHT,
            VIDEO_BITRATE,
            VIDEO_FPS,
            VIDEO_KEYFRAME_INTERVAL_SECONDS,
            geometry.rotationArg,
        )
        if (prepared) {
            // OutputConfiguration defaults to MIRROR_MODE_AUTO for front Camera2.
            // Correct the framework texture mirror after RootEncoder's camera rotation.
            // takePhoto uses the stream flags too, keeping Frame check and recording identical.
            stream.getGlInterface().apply {
                setIsPreviewHorizontalFlip(geometry.compensateMirrorHorizontally)
                setIsPreviewVerticalFlip(geometry.compensateMirrorVertically)
                setIsStreamHorizontalFlip(geometry.compensateMirrorHorizontally)
                setIsStreamVerticalFlip(geometry.compensateMirrorVertically)
            }
        }
        return prepared
    }

    override fun prepareAudio(): Boolean = stream.prepareAudio(48_000, true, 128_000, false, false)

    override fun startPreview(surface: Surface, width: Int, height: Int) = stream.startPreview(surface, width, height)

    override fun stopPreview() = stream.stopPreview(false)

    override fun setPreviewResolution(width: Int, height: Int) = stream.getGlInterface().setPreviewResolution(width, height)

    override fun isSurfaceValid(surface: Surface): Boolean = surface.isValid

    override fun stopRecord() {
        stream.stopRecord()
    }

    override fun stopStream() {
        stream.stopStream()
    }

    override fun release() = stream.release()

    private companion object {
        const val VIDEO_BITRATE = 2_500_000
        const val VIDEO_FPS = 30
        const val VIDEO_KEYFRAME_INTERVAL_SECONDS = 2
    }
}
