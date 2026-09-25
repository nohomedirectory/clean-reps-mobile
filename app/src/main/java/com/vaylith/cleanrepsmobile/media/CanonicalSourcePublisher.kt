package com.vaylith.cleanrepsmobile.media

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceView
import com.pedro.common.ConnectChecker
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.library.base.recording.RecordController
import com.pedro.library.srt.SrtStream
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticStep
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticsLog
import com.vaylith.cleanrepsmobile.model.SourceEpoch
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The only object permitted to publish phone video. MediaMTX performs fan-out.
 * Every member that code outside `media/` uses is declared here, so tests can
 * fake the publisher without constructing RootEncoder objects.
 */
interface CanonicalSourcePublisher {
    suspend fun start(epoch: SourceEpoch): PublisherResult
    suspend fun stop()
    val isAvailable: Boolean

    /** From now on the publisher owns [view]'s surface through its own SurfaceHolder.Callback. */
    fun attachPreview(view: SurfaceView)

    /** Removes that callback and detaches the preview; a running stream keeps sending. */
    fun releasePreview()

    /** The geometry the encoder is prepared with (LIVE pill, diagnostics, C2); null before the first prepare. */
    val preparedGeometry: CaptureGeometry?

    /** SENSOR_ORIENTATION of the first back camera; null when it could not be read. */
    val sensorOrientationDeg: Int?
}

sealed interface PublisherResult {
    data class Connecting(val sourceId: String) : PublisherResult
    data class Live(val sourceId: String) : PublisherResult
    data class Blocked(val reason: String) : PublisherResult
    data class Failed(val reason: String) : PublisherResult
}

/** Small pure configuration object so the MediaMTX SRT URL is deterministic and testable. */
data class MediaMtxSrtConfig(
    val host: String,
    val passphrase: String,
    val publishPassword: String,
    val path: String = "million-kicks-camera",
) {
    override fun toString() = "MediaMtxSrtConfig(configured=${validationError() == null}, path=$path)"
    fun validationError(): String? = when {
        host.isBlank() -> "MEDIAMTX_SRT_HOST is not configured"
        host.contains("://") || host.contains('/') || host.contains('?') -> "MEDIAMTX_SRT_HOST must be host or host:port only"
        passphrase.length !in 10..79 -> "MEDIAMTX_SRT_PASSPHRASE must be 10–79 characters"
        publishPassword.isBlank() -> "MEDIAMTX_PUBLISH_PASSWORD is not configured"
        path != "million-kicks-camera" -> "Canonical source path must be million-kicks-camera"
        else -> null
    }

    /** Standard MediaMTX SRT caller/publish URL. Values are runtime-only BuildConfig secrets. */
    fun endpoint(): String {
        require(validationError() == null) { validationError()!! }
        val hostWithPort = if (host.substringAfterLast(':', "").all(Char::isDigit) && host.contains(':')) host else "$host:8890"
        val streamId = "#!::m=publish,r=$path,u=publisher,s=$publishPassword"
        return "srt://$hostWithPort?mode=caller&streamid=$streamId&passphrase=$passphrase&latency=1000000&pkt_size=1316"
    }
}

interface PublisherListener {
    /** Transport and start/stop only: the only statuses that may change capture readiness. */
    fun onPublisherStatus(status: PublisherStatus, detail: String)
    /** A transport disconnect is a genuine discontinuity and needs a new server SourceEpoch. */
    fun onSourceDiscontinuity(detail: String)
    fun onSafetyRecording(detail: String)

    /**
     * Preview-only statuses (P-SEP). They never describe the transport, so they
     * must never pause practice, report health or clear the capture.
     */
    fun onPreviewStatus(status: PreviewStatus, detail: String) {}
}

enum class PublisherStatus {
    /** No longer sent: preview state is reported through [PublisherListener.onPreviewStatus]. */
    PREVIEW_READY,
    CONNECTING,
    LIVE,
    RECONNECTING,
    STOPPED,
    ERROR,
}

/**
 * RootEncoder 2.7.0 supplies camera2, H.264/AAC hardware encoding and SRT in one
 * capture graph. Keeping this graph singular prevents the phone from creating
 * independent recorder/analyzer/broadcast contributions.
 */
class MediaMtxSrtPublisher(
    private val context: Context,
    private val config: MediaMtxSrtConfig,
    private val listener: PublisherListener,
    private val diagnostics: DiagnosticsLog? = null,
) : CanonicalSourcePublisher, ConnectChecker {
    private val stream = SrtStream(context, this).apply {
        // Camera-app-like preview: scale uniformly to cover the view and centre-crop, never stretch.
        getGlInterface().setAspectRatioMode(AspectRatioMode.Fill)
    }
    private val mainThread = Handler(Looper.getMainLooper())
    private val rotationWatcher: DisplayRotationWatcher =
        DisplayRotationWatcher(context) { rotation -> coordinator.displayRotationChanged(rotation) }
    private val coordinator: PreviewCoordinator<Surface> = PreviewCoordinator(
        SrtStreamEncoderPort(stream),
        listener,
        diagnostics,
        backCameraSensorOrientation(),
        rotationWatcher::currentRotation,
    )
    private val binder = PreviewSurfaceBinder(coordinator)
    private var intentionallyStopped = false
    private var discontinuityReported = false

    override val isAvailable get() = config.validationError() == null
    override val preparedGeometry: CaptureGeometry? get() = coordinator.preparedGeometry
    override val sensorOrientationDeg: Int? get() = coordinator.sensorOrientationDeg

    /** Prepares for the current display rotation, then follows rotations and the view's surface. */
    override fun attachPreview(view: SurfaceView) {
        coordinator.displayRotationChanged(rotationWatcher.start())
        binder.install(view.holder)
    }

    override fun releasePreview() {
        binder.uninstall()
        rotationWatcher.stop()
    }

    override suspend fun start(epoch: SourceEpoch): PublisherResult = withContext(Dispatchers.Main.immediate) {
        config.validationError()?.let { return@withContext PublisherResult.Blocked(it) }
        try {
            intentionallyStopped = false
            discontinuityReported = false
            stream.getStreamClient().setReTries(8)
            if (!stream.isStreaming) {
                // I8: the geometry is read now and prepared before startStream, never a stale one.
                when (val gate = coordinator.goLive()) {
                    is StreamGate.NotReady -> {
                        intentionallyStopped = true
                        diagnostics?.fail(DiagnosticStep.PUBLISHER_START, "not ready: ${gate.reason}")
                        listener.onPublisherStatus(PublisherStatus.ERROR, "Video could not start: ${gate.reason}")
                        return@withContext PublisherResult.Failed("Video could not start: ${gate.reason}")
                    }
                    is StreamGate.Ready -> Unit
                }
                listener.onPublisherStatus(PublisherStatus.CONNECTING, "Connecting one SRT source for epoch ${epoch.displayId}…")
                stream.startStream(config.endpoint())
                coordinator.streamStarted()
                startSafetySpool(epoch)
            }
            diagnostics?.ok(DiagnosticStep.PUBLISHER_START, "stream ${coordinator.preparedGeometry?.label.orEmpty()}")
            PublisherResult.Connecting(config.path)
        } catch (error: Exception) {
            diagnostics?.fail(DiagnosticStep.PUBLISHER_START, "video start failed", error)
            // A partial start must not leave a hidden stream after the UI says stopped.
            intentionallyStopped = true
            runCatching { if (stream.isRecording) stream.stopRecord() }
            runCatching { if (stream.isStreaming) stream.stopStream() }
            coordinator.streamStopped()
            listener.onPublisherStatus(PublisherStatus.ERROR, "Video could not start. Check the connection and camera permissions.")
            PublisherResult.Failed("Video could not start. Check the connection and camera permissions.")
        }
    }

    override suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        intentionallyStopped = true
        runCatching { if (stream.isRecording) stream.stopRecord() }
        runCatching { if (stream.isStreaming) stream.stopStream() }
        // Applies a rotation that waited while streaming (I7) and restores the preview.
        coordinator.streamStopped()
        listener.onPublisherStatus(PublisherStatus.STOPPED, "Capture stopped. Local safety spool is retained in app cache.")
    }

    override fun onConnectionStarted(url: String) = listener.onPublisherStatus(PublisherStatus.CONNECTING, "SRT handshake started.")
    override fun onConnectionSuccess() {
        discontinuityReported = false
        listener.onPublisherStatus(PublisherStatus.LIVE, "Canonical SRT source is live: ${config.path}")
    }
    override fun onDisconnect() {
        if (intentionallyStopped) return
        if (!discontinuityReported) {
            discontinuityReported = true
            listener.onSourceDiscontinuity("SRT publisher disconnected.")
        }
        listener.onPublisherStatus(PublisherStatus.RECONNECTING, "Publisher disconnected; retrying canonical source.")
    }
    override fun onAuthError() = connectionFailed("MediaMTX rejected publisher authentication.", retry = false)
    override fun onAuthSuccess() = Unit
    override fun onNewBitrate(bitrate: Long) = Unit
    override fun onConnectionFailed(reason: String) = connectionFailed(reason, retry = true)

    private fun connectionFailed(reason: String, retry: Boolean) {
        if (intentionallyStopped) return
        if (!discontinuityReported) {
            discontinuityReported = true
            listener.onSourceDiscontinuity("Video transport interrupted.")
        }
        val retrying = retry && stream.getStreamClient().reTry(1_500, reason)
        if (!retrying) {
            intentionallyStopped = true
            runCatching { if (stream.isRecording) stream.stopRecord() }
            runCatching { if (stream.isStreaming) stream.stopStream() }
            // Transport callbacks arrive off the main thread; the preview machine lives on it.
            mainThread.post { coordinator.streamStopped() }
        }
        listener.onPublisherStatus(
            if (retrying) PublisherStatus.RECONNECTING else PublisherStatus.ERROR,
            if (retrying) "Video unavailable; reconnecting with a new source epoch." else "Video connection failed. Check the connection settings and retry.",
        )
    }

    /** SENSOR_ORIENTATION of the first back-facing camera id: the camera RootEncoder opens by default. */
    private fun backCameraSensorOrientation(): Int? = try {
        val cameras = context.getSystemService(CameraManager::class.java)
        val backCamera = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        backCamera?.let { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.SENSOR_ORIENTATION) }
            .also { diagnostics?.info(DiagnosticStep.CAMERA_OPEN, "back camera sensor orientation ${it ?: "unavailable"}") }
    } catch (error: Exception) {
        diagnostics?.fail(DiagnosticStep.CAMERA_OPEN, "could not read the back camera orientation", error)
        null
    }

    private fun startSafetySpool(epoch: SourceEpoch) {
        if (stream.isRecording) return
        val directory = File(context.cacheDir, "safety-spool").apply { mkdirs() }
        val output = File(directory, "kick-${epoch.displayId}-${System.currentTimeMillis()}.mp4")
        try {
            stream.startRecord(output.absolutePath) { status ->
                when (status) {
                    RecordController.Status.RECORDING -> listener.onSafetyRecording("Local safety recording is active.")
                    RecordController.Status.STOPPED -> listener.onSafetyRecording("Local safety recording finalized.")
                    else -> Unit
                }
            }
        } catch (_: Exception) {
            listener.onSafetyRecording("Local safety recording is unavailable. Video contribution continues.")
        }
    }
}
