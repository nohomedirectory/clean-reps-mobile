package com.vaylith.cleanrepsmobile.capture

import android.content.Context
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.video.ExperimentalVideo
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

/** CameraX preview + local safety spool. The canonical publisher is intentionally separate. */
@OptIn(ExperimentalVideo::class)
class CameraCaptureController(private val context: Context) {
    private var recording: Recording? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    fun bind(owner: LifecycleOwner, previewView: PreviewView, onError: (String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD)).build()
                videoCapture = VideoCapture.withOutput(recorder)
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture)
            } catch (error: Exception) { onError(error.message ?: "Camera failed to bind") }
        }, ContextCompat.getMainExecutor(context))
    }

    fun startSafetyRecording(onSaved: (Uri) -> Unit, onError: (String) -> Unit) {
        val capture = videoCapture ?: return onError("Camera is not ready")
        val spool = File(context.cacheDir, "safety-spool").apply { mkdirs() }
        val output = File(spool, "kick-${System.currentTimeMillis()}.mp4")
        val options = FileOutputOptions.Builder(output).build()
        recording = capture.output.prepareRecording(context, options).start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Finalize -> if (event.hasError()) onError("Safety recording: ${event.error}") else onSaved(Uri.fromFile(output))
            }
        }
    }

    fun stopSafetyRecording() { recording?.stop(); recording = null }
}
