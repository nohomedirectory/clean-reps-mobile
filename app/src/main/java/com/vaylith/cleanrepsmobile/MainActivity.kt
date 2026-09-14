package com.vaylith.cleanrepsmobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.vaylith.cleanrepsmobile.api.ChallengeApi
import com.vaylith.cleanrepsmobile.api.ChallengeEventClient
import com.vaylith.cleanrepsmobile.feedback.AthleteFeedback
import com.vaylith.cleanrepsmobile.media.*
import com.vaylith.cleanrepsmobile.model.*
import java.time.Instant
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var feedback: AthleteFeedback
    private val requiredPermissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private var permissionGeneration by mutableIntStateOf(0)
    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionGeneration++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        feedback = AthleteFeedback(this)
        if (!hasPermissions()) requestPermissions.launch(requiredPermissions)
        setContent { MaterialTheme { MobileScreen() } }
    }

    private fun hasPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun MobileScreen() {
        @Suppress("UNUSED_VARIABLE") val permissionRefresh = permissionGeneration
        val settingsStore = remember { ConnectionSettingsStore(this) }
        var settings by remember { mutableStateOf(settingsStore.load()) }
        var showSettings by remember { mutableStateOf(false) }
        var state by remember { mutableStateOf(AppState()) }
        var requestInFlight by remember { mutableStateOf(false) }
        val api = remember(settings) { ChallengeApi(settings.apiBaseUrl) }
        val eventClient = remember(settings) { ChallengeEventClient(settings.apiBaseUrl) }
        val configured = settings.validationError() == null
        val videoRunning = state.readiness in setOf(CaptureReadiness.CONNECTING, CaptureReadiness.LIVE, CaptureReadiness.RECONNECTING)

        suspend fun pausePractice() {
            val session = state.sessionId
            val block = state.blockId
            if (session != null && block != null) api.pausePractice(session, block)
            state = state.copy(practiceActive = false, blockReady = false)
        }

        val publisher = remember(settings) {
            MediaMtxSrtPublisher(this@MainActivity,
                MediaMtxSrtConfig(settings.srtHost, settings.srtPassphrase, settings.publishPassword, BuildConfig.MEDIAMTX_STREAM_PATH),
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
                        val capture = state.captureId
                        val stopped = readiness == CaptureReadiness.STOPPED || readiness == CaptureReadiness.ERROR
                        state = state.copy(
                            readiness = readiness, statusDetail = detail,
                            captureStartedAtElapsedMs = if (stopped) null else if (readiness == CaptureReadiness.LIVE) state.captureStartedAtElapsedMs ?: SystemClock.elapsedRealtime() else state.captureStartedAtElapsedMs,
                        )
                        val health = when (readiness) {
                            CaptureReadiness.LIVE -> "healthy"
                            CaptureReadiness.CONNECTING, CaptureReadiness.RECONNECTING -> "degraded"
                            CaptureReadiness.STOPPED, CaptureReadiness.ERROR -> "lost"
                            else -> null
                        }
                        if (capture != null && health != null) lifecycleScope.launch { runCatching { api.reportSourceHealth(capture, health, detail) } }
                        if (stopped && capture != null) {
                            // A later restart begins a different encoded time origin.
                            state = state.copy(captureId = null, epoch = state.epoch.next(), blockReady = false, practiceActive = false)
                            lifecycleScope.launch { runCatching { pausePractice() } }
                        }
                    }
                    override fun onSourceDiscontinuity(detail: String) = runOnUiThread {
                        val epoch = state.epoch.next()
                        val session = state.sessionId
                        state = state.copy(epoch = epoch, blockReady = false, practiceActive = false, captureId = null, captureStartedAtElapsedMs = null,
                            readiness = CaptureReadiness.RECONNECTING, statusDetail = "Video reconnecting. Resume practice after checking the preview.")
                        if (session != null) lifecycleScope.launch {
                            try {
                                runCatching { pausePractice() }
                                val capture = api.attachCapture(session, BuildConfig.MEDIAMTX_STREAM_PATH, epoch)
                                if (state.sessionId == session && state.epoch == epoch) {
                                    state = state.copy(captureId = capture)
                                    api.reportSourceHealth(capture, if (state.readiness == CaptureReadiness.LIVE) "healthy" else "degraded", detail)
                                }
                            } catch (_: Exception) { state = state.copy(statusDetail = "Could not attach the reconnected video. Stop and restart video.") }
                        }
                    }
                    override fun onSafetyRecording(detail: String) = runOnUiThread { state = state.copy(statusDetail = detail) }
                })
        }

        fun subscribe(session: String) {
            eventClient.start(lifecycleScope, session,
                { verdict -> feedback.verdict(verdict.tone); if (state.debugSpeakVerdicts) feedback.speakWhenSafe(verdict.reasonCode) },
                { cue -> state = state.copy(activeCue = cue) },
                { cue -> feedback.speakWhenSafe(cue.text) },
                { message -> state = state.copy(statusDetail = message) },
                { total -> state = state.copy(challengeOfficialAcceptedCount = total) })
        }

        fun selectDrill(selection: BlockSelection) {
            if (!state.practiceActive && !requestInFlight && selection != state.selection) {
                state = state.copy(selection = selection, blockId = null, blockReady = false, statusDetail = "Drill selected. Start practice when ready.")
            }
        }

        suspend fun stopVideo() {
            // Camera shutdown must never wait for an unavailable API.
            publisher.stop()
            runCatching { pausePractice() }
        }

        Scaffold(topBar = { TopAppBar(title = { Text("Clean Reps") }) }) { pad ->
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Private rehearsal", style = MaterialTheme.typography.titleLarge)
                Text("This session adds no official challenge credit. Automatic judging readiness must be verified separately.")
                OutlinedButton(onClick = { showSettings = true }, enabled = !videoRunning && !requestInFlight && !state.practiceActive) {
                    Text(if (configured) "Connection setup" else "Set up connection")
                }
                if (!configured) Text("Add the private server settings to connect this phone.")
                if (hasPermissions()) {
                    key(publisher) { AndroidView(factory = { SurfaceView(it).also(publisher::attachPreview) }, modifier = Modifier.fillMaxWidth().height(280.dp)) }
                } else {
                    Button(onClick = { requestPermissions.launch(requiredPermissions) }) { Text("Allow camera and microphone") }
                }
                Text("Video: ${state.readiness.name.lowercase().replace('_', ' ')}")
                Text(state.statusDetail)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = configured && hasPermissions() && !videoRunning && !requestInFlight, onClick = {
                        requestInFlight = true
                        lifecycleScope.launch {
                            try {
                                val session = state.sessionId ?: api.createSession("million-kicks-launch").also { state = state.copy(sessionId = it); subscribe(it) }
                                val capture = state.captureId ?: api.attachCapture(session, BuildConfig.MEDIAMTX_STREAM_PATH, state.epoch)
                                state = state.copy(captureId = capture)
                                check(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { "Camera screen is not visible" }
                                when (val result = publisher.start(state.epoch)) {
                                    is PublisherResult.Connecting -> Unit // Only the transport callback can claim LIVE.
                                    is PublisherResult.Blocked -> state = state.copy(readiness = CaptureReadiness.PUBLISHER_UNAVAILABLE, statusDetail = result.reason)
                                    is PublisherResult.Failed -> state = state.copy(readiness = CaptureReadiness.ERROR, statusDetail = result.reason)
                                    is PublisherResult.Live -> Unit
                                }
                            } catch (_: Exception) { state = state.copy(statusDetail = "Could not start video. Check Tailscale and the connection settings.") }
                            finally { requestInFlight = false }
                        }
                    }) { Text("Start video") }
                    OutlinedButton(enabled = videoRunning && !requestInFlight, onClick = { lifecycleScope.launch { stopVideo() } }) { Text("Stop video") }
                }
                Text("Keep this app open while streaming. Use your laptop for broadcast and chat controls.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("Practice", style = MaterialTheme.typography.titleLarge)
                if (state.practiceActive) Text("Practice active. Pause to change the drill; video continues.")
                ChoiceRow(KickTechnique.entries, state.selection.technique, !state.practiceActive && !requestInFlight, { it.label }) { selectDrill(state.selection.copy(technique = it)) }
                ChoiceRow(KickSide.entries, state.selection.side, !state.practiceActive && !requestInFlight, { it.label }) { selectDrill(state.selection.copy(side = it)) }
                ChoiceRow(KickTarget.entries, state.selection.targetContext, !state.practiceActive && !requestInFlight, { it.label }) { selectDrill(state.selection.copy(targetContext = it)) }
                Text("Target height (optional)")
                ChoiceRow(listOf<TargetHeight?>(null) + TargetHeight.entries, state.selection.targetHeight, !state.practiceActive && !requestInFlight, { it?.label ?: "Any" }) { selectDrill(state.selection.copy(targetHeight = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = state.readiness == CaptureReadiness.LIVE && state.captureId != null && !state.practiceActive && !requestInFlight, onClick = {
                        val session = state.sessionId
                        val capture = state.captureId
                        if (session != null) {
                            requestInFlight = true
                            lifecycleScope.launch {
                                try {
                                    val previousBlock = state.blockId
                                    val block = previousBlock ?: api.createBlock(session, state.selection)
                                    state = state.copy(blockId = block)
                                    api.markReacquired(session, block)
                                    if (previousBlock != null) api.resumePractice(session, block)
                                    if (state.captureId == capture && state.readiness == CaptureReadiness.LIVE && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                        state = state.copy(blockReady = true, practiceActive = true, statusDetail = "Practice active. Video continues independently.")
                                    } else {
                                        api.pausePractice(session, block)
                                        state = state.copy(blockReady = false, practiceActive = false)
                                    }
                                } catch (_: Exception) { state = state.copy(statusDetail = "Could not start practice. Video is still available; check the server connection.") }
                                finally { requestInFlight = false }
                            }
                        }
                    }) { Text(if (state.blockId == null) "Start practice" else "Resume practice") }
                    OutlinedButton(enabled = state.practiceActive && !requestInFlight, onClick = {
                        requestInFlight = true
                        lifecycleScope.launch {
                            try { pausePractice(); state = state.copy(statusDetail = "Practice paused. Video continues.") }
                            catch (_: Exception) { state = state.copy(statusDetail = "Pause was not confirmed. Retry or stop video before resting.") }
                            finally { requestInFlight = false }
                        }
                    }) { Text("Pause practice") }
                }
                Text("Check the preview before starting or resuming. Tempo, pauses and held phases are your choice.", style = MaterialTheme.typography.bodySmall)
                state.challengeOfficialAcceptedCount?.let { Text("Challenge total: $it accepted") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = feedback::audioTest) { Text("Audio test") }
                    FilterChip(selected = state.debugSpeakVerdicts, onClick = { state = state.copy(debugSpeakVerdicts = !state.debugSpeakVerdicts) }, label = { Text("Speak verdicts") })
                }
                state.activeCue?.let { Card { Text(it.text, Modifier.padding(12.dp)) } }
                if (state.practiceActive) OutlinedButton(onClick = {
                    val session = state.sessionId; val block = state.blockId; val capture = state.captureId; val start = state.captureStartedAtElapsedMs
                    if (session != null && block != null && capture != null && start != null) lifecycleScope.launch {
                        try {
                            val event = api.logManualAttempt(session, block, capture, BuildConfig.MEDIAMTX_STREAM_PATH, state.epoch,
                                Instant.now().toString(), manualEvidenceWindow(SystemClock.elapsedRealtime() - start))
                            state = state.copy(lastManualKickEventId = event, statusDetail = "Review marker saved. This does not accept a kick.")
                        } catch (_: Exception) { state = state.copy(statusDetail = "Could not save review marker.") }
                    }
                }) { Text("Save manual review marker") }
                Text("Video live means this phone reached the video server. It does not confirm a public broadcast or automatic judging.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (showSettings) ConnectionDialog(settings, onDismiss = { showSettings = false }, onSave = { value ->
            settingsStore.save(value)
            eventClient.stop()
            state = AppState(statusDetail = "Connection saved. Start video to check it.")
            settings = value
            showSettings = false
        })
        DisposableEffect(publisher, eventClient) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) lifecycleScope.launch { stopVideo() }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer); eventClient.stop(); publisher.releasePreview() }
        }
    }

    override fun onDestroy() { feedback.close(); super.onDestroy() }
}

@Composable private fun <T> ChoiceRow(choices: List<T>, selected: T, enabled: Boolean, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        choices.forEach { value -> FilterChip(selected = value == selected, enabled = enabled, onClick = { onSelect(value) }, label = { Text(label(value)) }) }
    }
}

@Composable private fun ConnectionDialog(initial: ConnectionSettings, onDismiss: () -> Unit, onSave: (ConnectionSettings) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Private connection") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Use the existing server settings. Tailscale must be connected. These settings stay on this phone.")
            OutlinedTextField(value = draft.apiBaseUrl, onValueChange = { draft = draft.copy(apiBaseUrl = it.trim()) }, label = { Text("Clean Reps address") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(value = draft.srtHost, onValueChange = { draft = draft.copy(srtHost = it.trim()) }, label = { Text("Video host and port") }, singleLine = true)
            OutlinedTextField(value = draft.srtPassphrase, onValueChange = { draft = draft.copy(srtPassphrase = it) }, label = { Text("Video passphrase") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            OutlinedTextField(value = draft.publishPassword, onValueChange = { draft = draft.copy(publishPassword = it) }, label = { Text("Publish password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            error = draft.validationError()
            if (error == null) runCatching { onSave(draft) }.onFailure { error = "Could not save settings on this phone." }
        }) { Text("Save connection") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
