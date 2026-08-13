package com.vaylith.cleanrepsmobile.media

import android.content.Context
import android.view.SurfaceView
import com.pedro.common.ConnectChecker
import com.pedro.library.base.recording.RecordController
import com.pedro.library.srt.SrtStream
import com.vaylith.cleanrepsmobile.model.SourceEpoch
import java.io.File

/** The only object permitted to publish phone video. MediaMTX performs fan-out. */
interface CanonicalSourcePublisher {
    suspend fun start(epoch: SourceEpoch): PublisherResult
    suspend fun stop()
    val isAvailable: Boolean
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
    fun onPublisherStatus(status: PublisherStatus, detail: String)
    /** A transport disconnect is a genuine discontinuity and needs a new server SourceEpoch. */
    fun onSourceDiscontinuity(detail: String)
    fun onSafetyRecording(detail: String)
}

enum class PublisherStatus { PREVIEW_READY, CONNECTING, LIVE, RECONNECTING, STOPPED, ERROR }

/**
 * RootEncoder 2.8.0 supplies camera2, H.264/AAC hardware encoding and SRT in one
 * capture graph. Keeping this graph singular prevents the phone from creating
 * independent recorder/analyzer/broadcast contributions.
 */
class MediaMtxSrtPublisher(
    private val context: Context,
    private val config: MediaMtxSrtConfig,
    private val listener: PublisherListener,
) : CanonicalSourcePublisher, ConnectChecker {
    private val stream = SrtStream(context, this)
    private var prepared = false
    private var previewAttached = false
    private var intentionallyStopped = false
    private var discontinuityReported = false

    override val isAvailable get() = config.validationError() == null

    fun attachPreview(view: SurfaceView) {
        try {
            prepareIfNeeded()
            if (!previewAttached) {
                stream.startPreview(view, true)
                previewAttached = true
            }
            listener.onPublisherStatus(PublisherStatus.PREVIEW_READY, "Camera preview ready. Source not yet live.")
        } catch (error: Exception) {
            listener.onPublisherStatus(PublisherStatus.ERROR, "Camera preview failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    override suspend fun start(epoch: SourceEpoch): PublisherResult {
        config.validationError()?.let { return PublisherResult.Blocked(it) }
        return try {
            intentionallyStopped = false
            discontinuityReported = false
            prepareIfNeeded()
            stream.getStreamClient().setReTries(8)
            if (!stream.isStreaming) {
                listener.onPublisherStatus(PublisherStatus.CONNECTING, "Connecting one SRT source for epoch ${epoch.displayId}…")
                stream.startStream(config.endpoint())
                startSafetySpool(epoch)
            }
            PublisherResult.Connecting(config.path)
        } catch (error: Exception) {
            listener.onPublisherStatus(PublisherStatus.ERROR, "Publisher start failed: ${error.message ?: error.javaClass.simpleName}")
            PublisherResult.Failed(error.message ?: "Publisher start failed")
        }
    }

    override suspend fun stop() {
        intentionallyStopped = true
        runCatching { if (stream.isRecording) stream.stopRecord() }
        runCatching { if (stream.isStreaming) stream.stopStream() }
        listener.onPublisherStatus(PublisherStatus.STOPPED, "Capture stopped. Local safety spool is retained in app cache.")
    }

    fun releasePreview() {
        runCatching { if (previewAttached) stream.stopPreview(true) }
        previewAttached = false
    }

    override fun onConnectionStarted(url: String) = listener.onPublisherStatus(PublisherStatus.CONNECTING, "SRT handshake started.")
    override fun onConnectionSuccess() {
        discontinuityReported = false
        listener.onPublisherStatus(PublisherStatus.LIVE, "Canonical SRT source is live: ${config.path}")
    }
    override fun onDisconnect() {
        if (!intentionallyStopped) listener.onPublisherStatus(PublisherStatus.RECONNECTING, "Publisher disconnected; retrying canonical source.")
    }
    override fun onAuthError() = connectionFailed("MediaMTX rejected publisher authentication.", retry = false)
    override fun onAuthSuccess() = Unit
    override fun onNewBitrate(bitrate: Long) = Unit
    override fun onConnectionFailed(reason: String) = connectionFailed(reason, retry = true)

    private fun connectionFailed(reason: String, retry: Boolean) {
        if (intentionallyStopped) return
        if (!discontinuityReported) {
            discontinuityReported = true
            listener.onSourceDiscontinuity(reason)
        }
        val retrying = retry && stream.getStreamClient().reTry(1_500, reason)
        listener.onPublisherStatus(
            if (retrying) PublisherStatus.RECONNECTING else PublisherStatus.ERROR,
            if (retrying) "SRT unavailable; reconnecting with a new source epoch." else "Publisher failed: $reason",
        )
    }

    private fun prepareIfNeeded() {
        if (prepared) return
        check(stream.prepareVideo(1280, 720, 2_500_000, 30, 2)) { "Device cannot prepare 720p H.264 camera encoder" }
        check(stream.prepareAudio(48_000, true, 128_000, false, false)) { "Device cannot prepare AAC microphone encoder" }
        prepared = true
    }

    private fun startSafetySpool(epoch: SourceEpoch) {
        if (stream.isRecording) return
        val directory = File(context.cacheDir, "safety-spool").apply { mkdirs() }
        val output = File(directory, "kick-${epoch.displayId}-${System.currentTimeMillis()}.mp4")
        stream.startRecord(output.absolutePath) { status ->
            when (status) {
                RecordController.Status.RECORDING -> listener.onSafetyRecording("Local safety spool recording: ${output.name}")
                RecordController.Status.STOPPED -> listener.onSafetyRecording("Local safety spool finalized: ${output.name}")
                else -> Unit
            }
        }
    }
}
