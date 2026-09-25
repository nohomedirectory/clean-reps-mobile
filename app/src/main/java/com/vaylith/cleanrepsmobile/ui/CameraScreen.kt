package com.vaylith.cleanrepsmobile.ui

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.vaylith.cleanrepsmobile.media.CanonicalSourcePublisher
import com.vaylith.cleanrepsmobile.media.PreviewStatus
import com.vaylith.cleanrepsmobile.model.KickSide
import com.vaylith.cleanrepsmobile.model.KickTarget
import com.vaylith.cleanrepsmobile.model.KickTechnique
import com.vaylith.cleanrepsmobile.model.TargetHeight
import com.vaylith.cleanrepsmobile.session.AppState
import com.vaylith.cleanrepsmobile.session.SessionController

private val Accent = Color(0xFFFF6D00)
private val Scrim = Color.Black.copy(alpha = 0.55f)

/** The camera screen's dark theme, with type sized to read from about 3 m while live. */
@Composable
fun CleanRepsTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Accent, onPrimary = Color.Black, background = Color.Black, surface = Color(0xFF121212)),
        typography = base.copy(
            titleLarge = base.titleLarge.copy(fontSize = 24.sp),
            titleMedium = base.titleMedium.copy(fontSize = 20.sp),
            bodyLarge = base.bodyLarge.copy(fontSize = 18.sp),
        ),
        content = content,
    )
}

/**
 * The full-screen camera screen: the preview fills the whole window (behind the cutout too) and
 * RootEncoder draws it with `AspectRatioMode.Fill` (set by the publisher), so it scales uniformly
 * and centre-crops like a camera app and is never stretched. The controls float over it inside
 * the `safeDrawing` insets. It takes the publisher INTERFACE and builds no RootEncoder object, so
 * a test can render it with a fake publisher.
 *
 * The window orientation is locked from the Go-live tap until the video stops (OD-7, applied by
 * MainActivity), so the filled preview always matches the stream's orientation while live.
 */
@Composable
fun CameraScreen(
    controller: SessionController,
    publisher: CanonicalSourcePublisher,
    configured: Boolean,
    permission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenConnectionSettings: () -> Unit,
    onAudioTest: () -> Unit,
) {
    val state by controller.state.collectAsState()
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    val rail = ControlRailModel.from(state, configured, permission)
    val onCommand: (RailCommand) -> Unit = { command ->
        when (command) {
            RailCommand.OPEN_CONNECTION_SETTINGS -> onOpenConnectionSettings()
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
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val content: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier) {
                StatusOverlay(state, controller::reopenCamera, Modifier.align(Alignment.TopStart).widthIn(max = 420.dp))
                state.activeCue?.let { cue ->
                    Text(cue.text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center).background(Scrim, RoundedCornerShape(16.dp)).padding(16.dp))
                }
                if (moreOpen) InterimControls(state, controller, onAudioTest, Modifier.align(Alignment.BottomStart).widthIn(max = 480.dp))
            }
        }
        val railView: @Composable (Modifier) -> Unit = { modifier ->
            ControlRail(rail, landscape, onCommand, controller::stopVideo, onOpenConnectionSettings, onMore = { moreOpen = !moreOpen }, modifier = modifier)
        }
        val safe = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)
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
    }
}

/** Video state and the banners, over the preview. M7b replaces this with the status pill and banners. */
@Composable
private fun StatusOverlay(state: AppState, onReopenCamera: () -> Unit, modifier: Modifier) {
    Column(modifier.background(Scrim, RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(state.livePill ?: "Video: ${state.readiness.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold)
        Text(state.statusDetail, style = MaterialTheme.typography.bodyLarge)
        val error = MaterialTheme.colorScheme.error
        state.banner?.let { Text(it, color = error, style = MaterialTheme.typography.bodyLarge) }
        state.previewBanner?.let { Text(it, color = error, style = MaterialTheme.typography.bodyLarge) }
        if (state.preview is PreviewStatus.CameraError) OutlinedButton(onClick = onReopenCamera) { Text("Reopen camera") }
        state.feedbackBanner?.let { Text(it, color = error, style = MaterialTheme.typography.bodyLarge) }
        state.challengeOfficialAcceptedCount?.let { Text("Challenge total: $it accepted") }
    }
}

/**
 * The drill, audio and manual-marker controls of the old form, behind More so the camera screen
 * stays clear. Interim: M7b's SetupSheet and overflow menu replace this panel.
 */
@Composable
private fun InterimControls(state: AppState, controller: SessionController, onAudioTest: () -> Unit, modifier: Modifier) {
    val choosable = !state.practiceActive && !state.requestInFlight
    Column(
        modifier.background(Scrim, RoundedCornerShape(16.dp)).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Private rehearsal - no official challenge credit", style = MaterialTheme.typography.titleMedium)
        if (state.practiceActive) Text("Practice active. Pause to change the drill; video continues.")
        ChoiceRow(KickTechnique.entries, state.selection.technique, choosable, { it.label }) { controller.selectDrill(state.selection.copy(technique = it)) }
        ChoiceRow(KickSide.entries, state.selection.side, choosable, { it.label }) { controller.selectDrill(state.selection.copy(side = it)) }
        ChoiceRow(KickTarget.entries, state.selection.targetContext, choosable, { it.label }) { controller.selectDrill(state.selection.copy(targetContext = it)) }
        ChoiceRow(listOf<TargetHeight?>(null) + TargetHeight.entries, state.selection.targetHeight, choosable, { it?.label ?: "Any height" }) {
            controller.selectDrill(state.selection.copy(targetHeight = it))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onAudioTest) { Text("Audio test") }
            FilterChip(selected = state.debugSpeakVerdicts, onClick = controller::toggleSpeakVerdicts, label = { Text("Speak verdicts") })
            FilterChip(selected = state.voiceHints, onClick = controller::toggleVoiceHints, label = { Text("Voice hints") })
        }
        if (state.practiceActive) TextButton(onClick = controller::saveManualMarker) { Text("Save manual review marker") }
        Text("Video live means this phone reached the video server. It does not confirm a public broadcast or automatic judging.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun <T> ChoiceRow(choices: List<T>, selected: T, enabled: Boolean, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        choices.forEach { value -> FilterChip(selected = value == selected, enabled = enabled, onClick = { onSelect(value) }, label = { Text(label(value)) }) }
    }
}
