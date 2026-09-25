package com.vaylith.cleanrepsmobile

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
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
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticsLog
import com.vaylith.cleanrepsmobile.diagnostics.LogcatSink
import com.vaylith.cleanrepsmobile.diagnostics.Redaction
import com.vaylith.cleanrepsmobile.feedback.AthleteFeedback
import com.vaylith.cleanrepsmobile.media.*
import com.vaylith.cleanrepsmobile.model.*
import com.vaylith.cleanrepsmobile.session.AppScope
import com.vaylith.cleanrepsmobile.session.AppState
import com.vaylith.cleanrepsmobile.session.ClientBuild
import com.vaylith.cleanrepsmobile.session.PendingLostStore
import com.vaylith.cleanrepsmobile.session.SessionController
import com.vaylith.cleanrepsmobile.session.SharedPreferencesPendingLostStore
import com.vaylith.cleanrepsmobile.session.lostServerKey
import java.time.Instant

class MainActivity : ComponentActivity() {
    private lateinit var feedback: AthleteFeedback
    private lateinit var diagnostics: DiagnosticsLog
    private lateinit var settingsStore: ConnectionSettingsStore
    private lateinit var pendingLost: PendingLostStore
    private lateinit var drills: DrillSelectionStore
    private var settings by mutableStateOf(ConnectionSettings())
    /** One per connection settings; replaced (and the old one closed) when they change. */
    private lateinit var activeController: MutableState<SessionController>
    private val requiredPermissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private var permissionGeneration by mutableIntStateOf(0)
    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionGeneration++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        feedback = AthleteFeedback(this)
        settingsStore = ConnectionSettingsStore(this)
        settings = settingsStore.load()
        diagnostics = DiagnosticsLog(System::currentTimeMillis, LogcatSink, Redaction.forSettings(settings))
        pendingLost = SharedPreferencesPendingLostStore(this)
        drills = SharedPreferencesDrillSelectionStore(this)
        activeController = mutableStateOf(newController(settings, AppState()))
        // ON_STOP still stops video: there is no background capture.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) activeController.value.onLeftScreen()
        })
        if (!hasPermissions()) requestPermissions.launch(requiredPermissions)
        setContent { MaterialTheme { MobileScreen(activeController.value) } }
    }

    private fun hasPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun newController(connection: ConnectionSettings, initial: AppState) = SessionController(
        backend = ChallengeApi(connection.apiBaseUrl, diagnostics),
        events = ChallengeEventClient(connection.apiBaseUrl, diagnostics),
        newPublisher = { listener ->
            MediaMtxSrtPublisher(this,
                MediaMtxSrtConfig(connection.srtHost, connection.srtPassphrase, connection.publishPassword, BuildConfig.MEDIAMTX_STREAM_PATH),
                listener, diagnostics)
        },
        signals = feedback,
        diagnostics = diagnostics,
        pendingLost = pendingLost,
        lostDeliveries = AppScope.lostDeliveries,
        serverKey = lostServerKey(connection.apiBaseUrl),
        drills = drills,
        // OD-7: set synchronously on the main thread, so the lock holds before the publisher reads the rotation.
        orientationLock = { locked ->
            requestedOrientation = if (locked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_SENSOR
        },
        appScope = AppScope.scope,
        uiScope = lifecycleScope,
        isMainThread = { Looper.myLooper() == Looper.getMainLooper() },
        elapsedRealtime = SystemClock::elapsedRealtime,
        wallClock = Instant::now,
        isScreenVisible = { lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
        build = ClientBuild(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GIT_SHA, Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT),
        sourceId = BuildConfig.MEDIAMTX_STREAM_PATH,
        initial = initial,
    ).also { it.start() }

    /** New settings: the old controller ends its capture and releases its publisher before the new one starts. */
    private fun applySettings(value: ConnectionSettings) {
        activeController.value.close()
        settings = value
        diagnostics.redaction = Redaction.forSettings(value)
        activeController.value = newController(value, AppState(statusDetail = "Connection saved. Start video to check it."))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun MobileScreen(controller: SessionController) {
        @Suppress("UNUSED_VARIABLE") val permissionRefresh = permissionGeneration
        val state by controller.state.collectAsState()
        var showSettings by remember { mutableStateOf(false) }
        val requestInFlight = state.requestInFlight
        val configured = settings.validationError() == null
        val videoRunning = state.videoRunning

        Scaffold(topBar = { TopAppBar(title = { Text("Clean Reps") }) }) { pad ->
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Private rehearsal", style = MaterialTheme.typography.titleLarge)
                Text("This session adds no official challenge credit. Automatic judging readiness must be verified separately.")
                OutlinedButton(onClick = { showSettings = true }, enabled = !videoRunning && !requestInFlight && !state.practiceActive) {
                    Text(if (configured) "Connection setup" else "Set up connection")
                }
                if (!configured) Text("Add the private server settings to connect this phone.")
                if (hasPermissions()) {
                    key(controller) { AndroidView(factory = { SurfaceView(it).also(controller.publisher::attachPreview) }, modifier = Modifier.fillMaxWidth().height(280.dp)) }
                } else {
                    Button(onClick = { requestPermissions.launch(requiredPermissions) }) { Text("Allow camera and microphone") }
                }
                state.banner?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.previewBanner?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.preview is PreviewStatus.CameraError) OutlinedButton(onClick = controller::reopenCamera) { Text("Reopen camera") }
                if (state.restartOffered) OutlinedButton(enabled = !requestInFlight, onClick = controller::restartVideo) { Text("Restart video") }
                Text("Video: ${state.readiness.name.lowercase().replace('_', ' ')}")
                Text(state.statusDetail)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = configured && hasPermissions() && !videoRunning && !requestInFlight, onClick = controller::startVideo) { Text("Start video") }
                    OutlinedButton(enabled = videoRunning && !requestInFlight, onClick = controller::stopVideo) { Text("Stop video") }
                }
                Text("Keep this app open while streaming. Use your laptop for broadcast and chat controls.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("Practice", style = MaterialTheme.typography.titleLarge)
                if (state.practiceActive) Text("Practice active. Pause to change the drill; video continues.")
                ChoiceRow(KickTechnique.entries, state.selection.technique, !state.practiceActive && !requestInFlight, { it.label }) { controller.selectDrill(state.selection.copy(technique = it)) }
                ChoiceRow(KickSide.entries, state.selection.side, !state.practiceActive && !requestInFlight, { it.label }) { controller.selectDrill(state.selection.copy(side = it)) }
                ChoiceRow(KickTarget.entries, state.selection.targetContext, !state.practiceActive && !requestInFlight, { it.label }) { controller.selectDrill(state.selection.copy(targetContext = it)) }
                Text("Target height (optional)")
                ChoiceRow(listOf<TargetHeight?>(null) + TargetHeight.entries, state.selection.targetHeight, !state.practiceActive && !requestInFlight, { it?.label ?: "Any" }) { controller.selectDrill(state.selection.copy(targetHeight = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = state.readiness == CaptureReadiness.LIVE && state.captureId != null && !state.practiceActive && !requestInFlight, onClick = controller::startPractice) {
                        // The block exists from LIVE (warm start); Resume means practice already ran on it.
                        Text(if (state.practiceStarted) "Resume practice" else "Start practice")
                    }
                    OutlinedButton(enabled = state.practiceActive && !requestInFlight, onClick = controller::pausePractice) { Text("Pause practice") }
                }
                Text("Check the preview before starting or resuming. Tempo, pauses and held phases are your choice.", style = MaterialTheme.typography.bodySmall)
                state.challengeOfficialAcceptedCount?.let { Text("Challenge total: $it accepted") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = feedback::audioTest) { Text("Audio test") }
                    FilterChip(selected = state.debugSpeakVerdicts, onClick = controller::toggleSpeakVerdicts, label = { Text("Speak verdicts") })
                    FilterChip(selected = state.voiceHints, onClick = controller::toggleVoiceHints, label = { Text("Voice hints") })
                }
                state.feedbackBanner?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.activeCue?.let { Card { Text(it.text, Modifier.padding(12.dp)) } }
                if (state.practiceActive) OutlinedButton(onClick = controller::saveManualMarker) { Text("Save manual review marker") }
                Text("Video live means this phone reached the video server. It does not confirm a public broadcast or automatic judging.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (showSettings) ConnectionDialog(settings, onDismiss = { showSettings = false }, onSave = { value ->
            settingsStore.save(value)
            applySettings(value)
            showSettings = false
        })
        DisposableEffect(controller) {
            onDispose { controller.publisher.releasePreview() }
        }
    }

    override fun onDestroy() {
        // The capture already ended at ON_STOP; this frees the camera, GL and encoders.
        activeController.value.close()
        feedback.close()
        super.onDestroy()
    }
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
