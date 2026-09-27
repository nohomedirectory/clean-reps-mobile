package com.vaylith.cleanrepsmobile.ui

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vaylith.cleanrepsmobile.media.CanonicalSourcePublisher
import com.vaylith.cleanrepsmobile.media.CaptureGeometry
import com.vaylith.cleanrepsmobile.model.ConnectionSettings
import com.vaylith.cleanrepsmobile.session.AppState
import com.vaylith.cleanrepsmobile.session.SessionController

private val Scrim = Color.Black.copy(alpha = 0.55f)

/**
 * Where the `SurfaceView` goes in a window of [windowW] x [windowH] px: M3c's
 * [PreviewLayout.surface] for [mode], which is the whole window for FILL and a stream-aspect box
 * at the top left for FIT. The publisher draws the same [PreviewLayout.DEFAULT_MODE] (Fill or
 * Adjust), so the one OD-3 constant switches both. The stream has the window's orientation: the
 * publisher prepares it for the display rotation, and OD-7 locks the window from the Go-live tap
 * until the video stops.
 */
internal fun previewSurface(windowW: Int, windowH: Int, mode: PreviewMode = PreviewLayout.DEFAULT_MODE): PixelRect {
    val landscape = windowW > windowH
    val streamW = if (landscape) CaptureGeometry.PREPARE_WIDTH else CaptureGeometry.PREPARE_HEIGHT
    val streamH = if (landscape) CaptureGeometry.PREPARE_HEIGHT else CaptureGeometry.PREPARE_WIDTH
    return PreviewLayout.surface(mode, windowW, windowH, streamW, streamH)
}

/** Sizes the preview to [previewSurface] in exact pixels; a window with no size yet is simply filled. */
private fun Modifier.previewSize(window: Constraints): Modifier {
    if (!window.hasBoundedWidth || !window.hasBoundedHeight || window.maxWidth == 0 || window.maxHeight == 0) return fillMaxSize()
    val surface = previewSurface(window.maxWidth, window.maxHeight)
    return layout { measurable, _ ->
        val placeable = measurable.measure(Constraints.fixed(surface.width, surface.height))
        layout(surface.right, surface.bottom) { placeable.place(surface.left, surface.top) }
    }
}

/**
 * The full-screen camera screen: in the default FILL mode the preview covers the whole window
 * (behind the cutout too) and the publisher draws it with RootEncoder's Fill, so it scales
 * uniformly and centre-crops like a camera app and is never stretched. The controls float over
 * it inside the `safeDrawing` insets. It takes the publisher INTERFACE and builds no RootEncoder
 * object, so a test can render it with a fake publisher.
 *
 * The window orientation is locked from the Go-live tap until the video stops (OD-7, applied by
 * MainActivity), so the filled preview always matches the stream's orientation while live.
 *
 * Over the preview: the status pill with a one-line status caption and the banners (top left),
 * the active cue (centre), the control rail, and the setup sheet when the gear, the drill chip or
 * Set up connection opens it. [onSaveConnection] stores and applies new connection settings; it
 * throws when they cannot be saved.
 */
@Composable
fun CameraScreen(
    controller: SessionController,
    publisher: CanonicalSourcePublisher,
    settings: ConnectionSettings,
    permission: Boolean,
    onRequestPermission: () -> Unit,
    onSaveConnection: (ConnectionSettings) -> Unit,
    onAudioTest: () -> Unit,
) {
    val state by controller.state.collectAsState()
    var cameraFacing by remember(publisher) { mutableStateOf(publisher.cameraFacing) }
    val cameraSwitch = CameraSwitchModel.from(state, permission, publisher.canSwitchCamera, cameraFacing)
    var sheet by rememberSaveable { mutableStateOf<SetupSection?>(null) }
    val rail = ControlRailModel.from(state, configured = settings.validationError() == null, permission = permission)
    // The rotation banner follows the geometry the running video was started with; OD-7 keeps the window locked to it.
    val turned = rememberTurnedBanner(if (state.videoRunning) publisher.preparedGeometry else null)
    val banners = Banners.select(state, turned)
    val health = rememberServerHealth(controller, controller::serverHealth)
    var portraitHintDismissed by rememberSaveable { mutableStateOf(false) }
    var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }
    var themesOpen by rememberSaveable { mutableStateOf(false) }
    var sessionCheckFor by rememberSaveable { mutableStateOf<String?>(null) }
    var sessionCheckShownFor by rememberSaveable { mutableStateOf<String?>(null) }
    // After Stop video: the Session check card opens once for the capture that just ended.
    LaunchedEffect(state.lastCaptureId) {
        val ended = state.lastCaptureId
        if (ended != null && ended != sessionCheckShownFor) {
            sessionCheckShownFor = ended
            sessionCheckFor = ended
        }
    }
    val onBannerAction: (BannerAction) -> Unit = { action ->
        when (action) {
            BannerAction.REOPEN_CAMERA -> controller.reopenCamera()
            BannerAction.STOP_VIDEO -> controller.stopVideo()
        }
    }
    val onOverflow: (OverflowAction) -> Unit = { action ->
        when (action) {
            OverflowAction.AUDIO_TEST -> onAudioTest()
            OverflowAction.VOICE_HINTS -> controller.toggleVoiceHints()
            OverflowAction.SPEAK_VERDICTS -> controller.toggleSpeakVerdicts()
            OverflowAction.MANUAL_MARKER -> controller.saveManualMarker()
            OverflowAction.LAST_SESSION_CHECK -> sessionCheckFor = state.lastCaptureId
            OverflowAction.DIAGNOSTICS -> diagnosticsOpen = true
            OverflowAction.THEMES -> themesOpen = true
        }
    }
    val onCommand: (RailCommand) -> Unit = { command ->
        when (command) {
            RailCommand.OPEN_CONNECTION_SETTINGS -> sheet = SetupSection.CONNECTION
            RailCommand.REQUEST_CAMERA_PERMISSION -> onRequestPermission()
            RailCommand.START_VIDEO -> controller.startVideo()
            RailCommand.START_PRACTICE -> controller.startPractice()
            RailCommand.PAUSE_PRACTICE -> controller.pausePractice()
            RailCommand.RESTART_VIDEO -> controller.restartVideo()
            RailCommand.NONE -> Unit
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight
        if (permission) {
            // One SurfaceView per publisher; its SurfaceHolder callback belongs to the publisher.
            key(publisher) {
                AndroidView(
                    factory = { context ->
                        SurfaceView(context).also { view ->
                            // Keep the video below the window so the Compose controls draw over it.
                            view.setZOrderMediaOverlay(false)
                            publisher.attachPreview(view)
                        }
                    },
                    onRelease = { publisher.releasePreview() },
                    modifier = Modifier.previewSize(constraints),
                )
            }
            // In window pixels, over the preview and under the controls; mapped with the preview's own mode.
            FramingBox(state, publisher.preparedGeometry, constraints.maxWidth, constraints.maxHeight, PreviewLayout.DEFAULT_MODE)
        }
        val content: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier) {
                StatusColumn(state, banners, onBannerAction, StatusOverlayModel.from(state, health),
                    Modifier.align(Alignment.TopStart).widthIn(max = 460.dp))
                state.activeCue?.let { cue ->
                    Text(cue.text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center).background(Scrim, RoundedCornerShape(16.dp)).padding(16.dp))
                }
                if (permission) {
                    val portraitHint = PortraitHint.shown(publisher.preparedGeometry?.orientation, windowPortrait = !landscape, dismissed = portraitHintDismissed)
                    FramingHints(state, portraitHint, onDismissPortraitHint = { portraitHintDismissed = true },
                        Modifier.align(Alignment.BottomCenter).widthIn(max = 520.dp))
                }
            }
        }
        val railView: @Composable (Modifier) -> Unit = { modifier ->
            ControlRail(rail, cameraSwitch, landscape, onCommand, controller::stopVideo, onSettings = { sheet = SetupSection.CONNECTION },
                onDrill = { sheet = SetupSection.DRILL }, onOverflow = onOverflow,
                onSwitchCamera = {
                    if (permission && controller.switchCamera()) cameraFacing = publisher.cameraFacing
                }, modifier = modifier)
        }
        val safe = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(RailText.EDGE_DP.dp)
        if (landscape) {
            Row(safe, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                content(Modifier.weight(1f).fillMaxHeight())
                railView(Modifier.align(Alignment.CenterVertically))
            }
        } else {
            Column(safe, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content(Modifier.weight(1f).fillMaxWidth())
                railView(Modifier)
            }
        }
        sheet?.let { section ->
            SetupSheet(
                section = section,
                onSection = { sheet = it },
                landscape = landscape,
                state = state,
                settings = settings,
                onSelectDrill = controller::selectDrill,
                onSaveConnection = onSaveConnection,
                onAudioTest = onAudioTest,
                onToggleVoiceHints = controller::toggleVoiceHints,
                onToggleSpeakVerdicts = controller::toggleSpeakVerdicts,
                onDismiss = { sheet = null },
            )
        }
        sessionCheckFor?.let { captureId ->
            SessionCheckCard(
                captureId = captureId,
                landscape = landscape,
                serverRelease = health?.release,
                fetchReport = { controller.qualityReport(captureId) },
                fetchThumbnail = { index -> controller.thumbnail(captureId, index) },
                onDismiss = { sessionCheckFor = null },
            )
        }
        if (diagnosticsOpen) {
            DiagnosticsSheet(landscape, publisher, health, controller::diagnosticEventLines, controller::diagnosticsExport,
                onDismiss = { diagnosticsOpen = false })
        }
        if (themesOpen) CameraThemePicker(landscape, onDismiss = { themesOpen = false })
    }
}

/**
 * The status pill, the analysis and server chips with the session counts, the status caption,
 * the challenge total and the banners, top left over the preview.
 */
@Composable
private fun StatusColumn(state: AppState, banners: List<Banner>, onBannerAction: (BannerAction) -> Unit, overlay: StatusOverlayModel, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusPill(state)
        StatusOverlay(overlay)
        Banners.caption(state, banners)?.let { caption ->
            Text(caption, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.background(Scrim, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
        }
        state.challengeOfficialAcceptedCount?.let { total ->
            Text("Challenge total: $total accepted", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.background(Scrim, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
        }
        BannerStack(banners, onBannerAction)
    }
}
