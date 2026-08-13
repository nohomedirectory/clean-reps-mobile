package com.vaylith.cleanrepsmobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vaylith.cleanrepsmobile.api.ChallengeApi
import com.vaylith.cleanrepsmobile.api.ChallengeEventClient
import com.vaylith.cleanrepsmobile.feedback.AthleteFeedback
import com.vaylith.cleanrepsmobile.media.MediaMtxSrtConfig
import com.vaylith.cleanrepsmobile.media.MediaMtxSrtPublisher
import com.vaylith.cleanrepsmobile.media.PublisherListener
import com.vaylith.cleanrepsmobile.media.PublisherResult
import com.vaylith.cleanrepsmobile.media.PublisherStatus
import com.vaylith.cleanrepsmobile.model.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var feedback: AthleteFeedback
    private val api = ChallengeApi(BuildConfig.CHALLENGE_API_BASE_URL)
    private val eventClient = ChallengeEventClient(BuildConfig.CHALLENGE_API_BASE_URL)
    private val requiredPermissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private var permissionGeneration by mutableIntStateOf(0)
    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionGeneration++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        feedback = AthleteFeedback(this)
        if (!hasPermissions()) requestPermissions.launch(requiredPermissions)
        setContent { MaterialTheme { MobileScreen() } }
    }

    private fun hasPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    @Composable private fun MobileScreen() {
        @Suppress("UNUSED_VARIABLE") val permissionRefresh = permissionGeneration
        var state by remember { mutableStateOf(AppState()) }
        val publisher = remember {
            MediaMtxSrtPublisher(
                this@MainActivity,
                MediaMtxSrtConfig(BuildConfig.MEDIAMTX_SRT_HOST, BuildConfig.MEDIAMTX_SRT_PASSPHRASE, BuildConfig.MEDIAMTX_PUBLISH_PASSWORD, BuildConfig.MEDIAMTX_STREAM_PATH),
                object : PublisherListener {
                    override fun onPublisherStatus(status: PublisherStatus, detail: String) = runOnUiThread {
                        val readiness = when (status) {
                            PublisherStatus.PREVIEW_READY -> CaptureReadiness.NOT_CONFIGURED
                            PublisherStatus.CONNECTING -> CaptureReadiness.CONNECTING
                            PublisherStatus.LIVE -> CaptureReadiness.LIVE
                            PublisherStatus.RECONNECTING -> CaptureReadiness.RECONNECTING
                            PublisherStatus.STOPPED -> CaptureReadiness.STOPPED
                            PublisherStatus.ERROR -> CaptureReadiness.ERROR
                        }
                        state = state.copy(readiness = readiness, statusDetail = detail)
                        if (state.captureId != null && readiness in setOf(CaptureReadiness.LIVE, CaptureReadiness.RECONNECTING, CaptureReadiness.ERROR)) {
                            lifecycleScope.launch { runCatching { api.reportSourceHealth(state.captureId!!, state.epoch, readiness.name.lowercase(), detail) } }
                        }
                    }
                    override fun onSourceDiscontinuity(detail: String) = runOnUiThread {
                        val nextEpoch = SourceEpoch()
                        state = state.copy(epoch = nextEpoch, readiness = CaptureReadiness.RECONNECTING, statusDetail = "New source epoch ${nextEpoch.id.take(8)}: $detail")
                        state.captureId?.let { captureId -> lifecycleScope.launch { runCatching { api.reportSourceHealth(captureId, nextEpoch, "reconnecting", detail) } } }
                    }
                    override fun onSafetyRecording(detail: String) = runOnUiThread { state = state.copy(statusDetail = detail) }
                },
            )
        }
        Scaffold(topBar = { TopAppBar(title = { Text("Clean Reps · Gym Baseline") }) }) { pad ->
            Column(Modifier.padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasPermissions()) {
                    AndroidView(factory = { SurfaceView(it).also(publisher::attachPreview) }, modifier = Modifier.fillMaxWidth().height(340.dp))
                } else {
                    Card { Text("Camera and microphone permission are required. Grant permission, then reopen this screen.", Modifier.padding(12.dp)) }
                }
                Text("${state.selection.technique.replace('_', ' ')} / ${state.selection.side} · epoch ${state.epoch.id.take(8)}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { state = state.copy(selection = state.selection.copy(side = KickSide.RIGHT), blockId = null, statusDetail = "Right block selected. Stop and hold still for server reacquisition.") }) { Text("Side kick · Right") }
                    Button(onClick = { state = state.copy(selection = state.selection.copy(side = KickSide.LEFT), blockId = null, statusDetail = "Left block selected. Stop and hold still for server reacquisition.") }) { Text("Side kick · Left") }
                }
                Text("Source: ${state.readiness} — ${state.statusDetail}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        lifecycleScope.launch {
                            try {
                                val session = state.sessionId ?: api.createSession("million-kicks-launch")
                                val block = api.createBlock(session, state.selection)
                                val capture = state.captureId ?: api.attachCapture(session, "android-camera-${state.epoch.id}", state.epoch)
                                state = state.copy(sessionId = session, blockId = block, captureId = capture, statusDetail = "Block created. Hold a still full-body stance for server reacquisition.")
                                eventClient.start(lifecycleScope, session, { tone, reason -> feedback.verdict(tone); if (state.debugSpeakVerdicts) feedback.speakWhenSafe(reason) }, { cue -> state = state.copy(activeCue = cue); feedback.speakWhenSafe(cue.text) }, { message -> state = state.copy(statusDetail = message) })
                            } catch (e: Exception) { state = state.copy(readiness = CaptureReadiness.ERROR, statusDetail = e.message ?: "API failed") }
                        }
                    }, enabled = api.configured) { Text("Create / switch block") }
                    Button(onClick = {
                        lifecycleScope.launch {
                            when (val result = publisher.start(state.epoch)) {
                                is PublisherResult.Connecting -> {
                                    state.captureId?.let { api.reportSourceHealth(it, state.epoch, "connecting", "SRT handshaking to ${result.sourceId}") }
                                    state = state.copy(readiness = CaptureReadiness.CONNECTING, statusDetail = "SRT publisher connecting…")
                                }
                                is PublisherResult.Blocked -> state = state.copy(readiness = CaptureReadiness.PUBLISHER_UNAVAILABLE, statusDetail = result.reason)
                                is PublisherResult.Failed -> state = state.copy(readiness = CaptureReadiness.ERROR, statusDetail = result.reason)
                                is PublisherResult.Live -> state = state.copy(readiness = CaptureReadiness.LIVE, statusDetail = "Canonical source live")
                            }
                        }
                    }, enabled = state.captureId != null && publisher.isAvailable && hasPermissions()) { Text("Start capture") }
                    OutlinedButton(onClick = { lifecycleScope.launch { publisher.stop() }; state = state.copy(readiness = CaptureReadiness.STOPPED, statusDetail = "Capture stopping; wait for local spool finalization.") }) { Text("Stop") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = feedback::audioTest) { Text("Audio test") }
                    FilterChip(selected = state.debugSpeakVerdicts, onClick = { state = state.copy(debugSpeakVerdicts = !state.debugSpeakVerdicts) }, label = { Text("Debug speak verdicts") })
                }
                state.activeCue?.let { Card { Text("Active coaching cue: ${it.text}", Modifier.padding(12.dp)) } }
                Text("One H.264/AAC SRT source publishes only to MediaMTX. A disconnect opens a new source epoch; server fan-out and the authoritative ledger remain server-owned.", style = MaterialTheme.typography.bodySmall)
            }
        }
        DisposableEffect(Unit) { onDispose { publisher.releasePreview() } }
    }

    override fun onDestroy() { eventClient.stop(); feedback.close(); super.onDestroy() }
}
