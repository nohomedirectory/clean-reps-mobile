package com.vaylith.cleanrepsmobile

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.vaylith.cleanrepsmobile.api.ChallengeApi
import com.vaylith.cleanrepsmobile.api.ChallengeEventClient
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticsLog
import com.vaylith.cleanrepsmobile.diagnostics.LogcatSink
import com.vaylith.cleanrepsmobile.diagnostics.Redaction
import com.vaylith.cleanrepsmobile.feedback.AthleteFeedback
import com.vaylith.cleanrepsmobile.media.MediaMtxSrtConfig
import com.vaylith.cleanrepsmobile.media.MediaMtxSrtPublisher
import com.vaylith.cleanrepsmobile.model.ConnectionSettings
import com.vaylith.cleanrepsmobile.model.DrillSelectionStore
import com.vaylith.cleanrepsmobile.model.SharedPreferencesDrillSelectionStore
import com.vaylith.cleanrepsmobile.session.AppScope
import com.vaylith.cleanrepsmobile.session.AppState
import com.vaylith.cleanrepsmobile.session.ClientBuild
import com.vaylith.cleanrepsmobile.session.PendingLostStore
import com.vaylith.cleanrepsmobile.session.SessionController
import com.vaylith.cleanrepsmobile.session.SharedPreferencesPendingLostStore
import com.vaylith.cleanrepsmobile.session.lostServerKey
import com.vaylith.cleanrepsmobile.ui.CameraScreen
import com.vaylith.cleanrepsmobile.ui.CleanRepsTheme
import java.time.Instant

/**
 * Window flags, permissions, the OD-7 orientation lock and the controller's lifetime. The screen
 * itself is [CameraScreen]. The window is edge to edge and, through the theme's short-edges cutout
 * mode (res/values-v28/themes.xml), also covers the display cutout, with the system bars hidden
 * until a swipe.
 */
class MainActivity : ComponentActivity() {
    private lateinit var feedback: AthleteFeedback
    private lateinit var diagnostics: DiagnosticsLog
    private lateinit var settingsStore: ConnectionSettingsStore
    private lateinit var pendingLost: PendingLostStore
    private lateinit var drills: DrillSelectionStore
    private var settings by mutableStateOf(ConnectionSettings())
    private var showSettings by mutableStateOf(false)
    /** One per connection settings; replaced (and the old one closed) when they change. */
    private lateinit var activeController: MutableState<SessionController>
    private val requiredPermissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private var permissionGeneration by mutableIntStateOf(0)
    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionGeneration++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Opt in to edge to edge explicitly: targetSdk 35 enforces it only on Android 15.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
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
        setContent {
            // Read so a permission answer recomposes the screen with the new hasPermissions().
            @Suppress("UNUSED_VARIABLE") val permissionRefresh = permissionGeneration
            val controller = activeController.value
            CleanRepsTheme {
                CameraScreen(
                    controller = controller,
                    publisher = controller.publisher,
                    configured = settings.validationError() == null,
                    permission = hasPermissions(),
                    onRequestPermission = { requestPermissions.launch(requiredPermissions) },
                    onOpenConnectionSettings = { showSettings = true },
                    onAudioTest = feedback::audioTest,
                )
                if (showSettings) ConnectionDialog(settings, onDismiss = { showSettings = false }, onSave = { value ->
                    settingsStore.save(value)
                    applySettings(value)
                    showSettings = false
                })
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A dialog or the permission prompt can bring the bars back; hide them again.
        if (hasFocus) hideSystemBars()
    }

    /** Immersive: the bars stay hidden and a swipe shows them briefly over the screen. */
    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
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
        // LOCKED keeps the current orientation, so the filled preview matches the stream while live.
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

    override fun onDestroy() {
        // The capture already ended at ON_STOP; this frees the camera, GL and encoders.
        activeController.value.close()
        feedback.close()
        super.onDestroy()
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
