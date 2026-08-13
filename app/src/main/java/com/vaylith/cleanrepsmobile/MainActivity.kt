package com.vaylith.cleanrepsmobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
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
import com.vaylith.cleanrepsmobile.capture.CameraCaptureController
import com.vaylith.cleanrepsmobile.feedback.AthleteFeedback
import com.vaylith.cleanrepsmobile.media.UnavailableCanonicalPublisher
import com.vaylith.cleanrepsmobile.model.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var camera: CameraCaptureController
    private lateinit var feedback: AthleteFeedback
    private val api = ChallengeApi(BuildConfig.CHALLENGE_API_BASE_URL)
    private val eventClient = ChallengeEventClient(BuildConfig.CHALLENGE_API_BASE_URL)
    private val requiredPermissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        camera = CameraCaptureController(this); feedback = AthleteFeedback(this)
        if (requiredPermissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) requestPermissions.launch(requiredPermissions)
        setContent { MaterialTheme { MobileScreen() } }
    }

    @Composable private fun MobileScreen() {
        var state by remember { mutableStateOf(AppState()) }
        var preview by remember { mutableStateOf<PreviewView?>(null) }
        val publisher = remember {
            val missing = buildList { if (BuildConfig.MEDIAMTX_SRT_HOST.isBlank()) add("MEDIAMTX_SRT_HOST"); if (BuildConfig.MEDIAMTX_SRT_PASSPHRASE.isBlank()) add("MEDIAMTX_SRT_PASSPHRASE") }
            UnavailableCanonicalPublisher(missing)
        }
        LaunchedEffect(preview) { preview?.let { camera.bind(this@MainActivity, it) { error -> state = state.copy(readiness = CaptureReadiness.ERROR, statusDetail = error) } } }
        Scaffold(topBar = { TopAppBar(title = { Text("Clean Reps · Gym Baseline") }) }) { pad ->
            Column(Modifier.padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AndroidView(factory = { PreviewView(it).also { pv -> preview = pv } }, modifier = Modifier.fillMaxWidth().height(340.dp))
                Text("${state.selection.technique.replace('_', ' ')} / ${state.selection.side} · epoch ${state.epoch.id.take(8)}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { state = state.copy(selection = state.selection.copy(side = KickSide.RIGHT), epoch = SourceEpoch(), blockId = null, statusDetail = "Right block selected. Stop and hold still for server reacquisition.") }) { Text("Side kick · Right") }
                    Button(onClick = { state = state.copy(selection = state.selection.copy(side = KickSide.LEFT), epoch = SourceEpoch(), blockId = null, statusDetail = "Left block selected. Stop and hold still for server reacquisition.") }) { Text("Side kick · Left") }
                }
                Text("Source: ${state.readiness} — ${state.statusDetail}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        lifecycleScope.launch {
                            try {
                                val session = state.sessionId ?: api.createSession("million-kicks-launch")
                                val block = api.createBlock(session, state.selection)
                                val capture = state.captureId ?: api.attachCapture(session, "android-camera-${state.epoch.id}", state.epoch)
                                state = state.copy(sessionId = session, blockId = block, captureId = capture, statusDetail = "Block created. Reacquisition required before kick analysis.")
                                eventClient.start(lifecycleScope, session, { tone, reason -> feedback.verdict(tone); if (state.debugSpeakVerdicts) feedback.speakWhenSafe(reason) }, { cue -> state = state.copy(activeCue = cue); feedback.speakWhenSafe(cue.text) }, { message -> state = state.copy(statusDetail = message) })
                            } catch (e: Exception) { state = state.copy(readiness = CaptureReadiness.ERROR, statusDetail = e.message ?: "API failed") }
                        }
                    }, enabled = api.configured) { Text("Create / switch block") }
                    Button(onClick = { camera.startSafetyRecording({ uri -> state = state.copy(statusDetail = "Safety clip saved: $uri") }, { error -> state = state.copy(statusDetail = error) }); lifecycleScope.launch { when (val result = publisher.start(state.epoch)) { is com.vaylith.cleanrepsmobile.media.PublisherResult.Blocked -> { state.captureId?.let { api.reportSourceHealth(it, state.epoch, "blocked", result.reason) }; state = state.copy(readiness = CaptureReadiness.PUBLISHER_UNAVAILABLE, statusDetail = result.reason) }; else -> Unit } } }) { Text("Start capture") }
                    OutlinedButton(onClick = { camera.stopSafetyRecording(); lifecycleScope.launch { publisher.stop() }; state = state.copy(readiness = CaptureReadiness.STOPPED, statusDetail = "Capture stopped; local spool remains on device cache.") }) { Text("Stop") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = feedback::audioTest) { Text("Audio test") }
                    FilterChip(selected = state.debugSpeakVerdicts, onClick = { state = state.copy(debugSpeakVerdicts = !state.debugSpeakVerdicts) }, label = { Text("Debug speak verdicts") })
                }
                state.activeCue?.let { Card { Text("Active coaching cue: ${it.text}", Modifier.padding(12.dp)) } }
                Text("Truthful status: the included client does not publish a canonical source until a vetted Android SRT/WebRTC publisher is integrated. Do not begin official counting while source is blocked.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    override fun onDestroy() { eventClient.stop(); feedback.close(); super.onDestroy() }
}
