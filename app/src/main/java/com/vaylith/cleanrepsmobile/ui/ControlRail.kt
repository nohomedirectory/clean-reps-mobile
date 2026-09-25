package com.vaylith.cleanrepsmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vaylith.cleanrepsmobile.session.AppState

/** What a tap on the primary button does; the screen maps each command to one controller or host call. */
enum class RailCommand {
    OPEN_CONNECTION_SETTINGS,
    REQUEST_CAMERA_PERMISSION,
    START_VIDEO,
    START_PRACTICE,
    PAUSE_PRACTICE,
    RESTART_VIDEO,

    /** Connecting... is shown disabled; a tap does nothing. */
    NONE,
}

fun PrimaryAction.command(): RailCommand = when (this) {
    PrimaryAction.SET_UP_CONNECTION -> RailCommand.OPEN_CONNECTION_SETTINGS
    PrimaryAction.ALLOW_CAMERA -> RailCommand.REQUEST_CAMERA_PERMISSION
    PrimaryAction.GO_LIVE -> RailCommand.START_VIDEO
    PrimaryAction.CONNECTING -> RailCommand.NONE
    PrimaryAction.START_PRACTICE, PrimaryAction.RESUME -> RailCommand.START_PRACTICE
    PrimaryAction.PAUSE -> RailCommand.PAUSE_PRACTICE
    PrimaryAction.RESTART_VIDEO -> RailCommand.RESTART_VIDEO
}

/**
 * Everything the control rail shows, derived purely from the controller state: the one primary
 * button (M3c's [PrimaryActionState]), the line under it, Stop video while the video runs, and
 * the connection-settings gear, which is disabled while live.
 */
data class ControlRailModel(
    val primary: PrimaryActionState,
    /** The one-line reason under a disabled primary button; null when it is enabled. */
    val reason: String?,
    /** The live-analysis line, and the one-tap action it offers (only Restart video). */
    val hint: String?,
    val hintAction: PrimaryAction?,
    val stopVideoShown: Boolean,
    val stopVideoEnabled: Boolean,
    val settingsEnabled: Boolean,
) {
    companion object {
        fun from(state: AppState, configured: Boolean, permission: Boolean): ControlRailModel {
            val primary = PrimaryActionState.from(
                PrimaryActionInputs(
                    readiness = state.readiness,
                    practice = state.practice,
                    inFlight = state.requestInFlight,
                    configured = configured,
                    permission = permission,
                    captureAttached = state.captureId != null,
                    liveAnalysis = state.liveAnalysis,
                ),
            )
            return ControlRailModel(
                primary = primary,
                reason = reason(primary),
                hint = primary.hint,
                hintAction = primary.hintAction,
                // Stop video also ends a stuck Connecting or Reconnecting video.
                stopVideoShown = state.videoRunning,
                stopVideoEnabled = state.videoRunning && !state.requestInFlight,
                settingsEnabled = !state.videoRunning && !state.requestInFlight && !state.practiceActive,
            )
        }

        /** A disabled button never appears without its reason: the old screen ignored taps silently. */
        fun reason(primary: PrimaryActionState): String? {
            if (primary.enabled) return null
            val reason = primary.reason
            require(!reason.isNullOrBlank() && '\n' !in reason) { "disabled ${primary.action} needs a one-line reason, was $reason" }
            return reason
        }
    }
}

private val RailScrim = Color.Black.copy(alpha = 0.55f)

/**
 * The controls over the preview on a translucent scrim, along the edge nearest the thumb: a
 * column on the right in landscape, a band along the bottom in portrait. The caller places it
 * inside the `safeDrawing` insets. [onMore] opens the interim panel that keeps the drill, audio
 * and manual-marker controls reachable until M7b's sheets and overflow menu replace it.
 */
@Composable
fun ControlRail(
    model: ControlRailModel,
    landscape: Boolean,
    onCommand: (RailCommand) -> Unit,
    onStopVideo: () -> Unit,
    onSettings: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrim = modifier.background(RailScrim, RoundedCornerShape(20.dp)).padding(12.dp)
    if (landscape) {
        Column(scrim.width(260.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SettingsButton(model.settingsEnabled, onSettings)
                MoreButton(onMore)
            }
            PrimaryBlock(model, onCommand, Modifier.fillMaxWidth())
            if (model.stopVideoShown) StopVideoButton(model.stopVideoEnabled, onStopVideo, Modifier.fillMaxWidth())
        }
    } else {
        Column(scrim.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsButton(model.settingsEnabled, onSettings)
                PrimaryBlock(model, onCommand, Modifier.weight(1f))
                MoreButton(onMore)
            }
            if (model.stopVideoShown) StopVideoButton(model.stopVideoEnabled, onStopVideo, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PrimaryBlock(model: ControlRailModel, onCommand: (RailCommand) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = { onCommand(model.primary.action.command()) },
            enabled = model.primary.enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        ) {
            Text(model.primary.action.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        model.reason?.let { RailLine(it) }
        model.hint?.let { RailLine(it) }
        if (model.hintAction != null) {
            TextButton(onClick = { onCommand(model.hintAction.command()) }) { Text(model.hintAction.label) }
        }
    }
}

@Composable
private fun RailLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun StopVideoButton(enabled: Boolean, onStopVideo: () -> Unit, modifier: Modifier) {
    OutlinedButton(onClick = onStopVideo, enabled = enabled, modifier = modifier.heightIn(min = 56.dp)) {
        Text("Stop video", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SettingsButton(enabled: Boolean, onSettings: () -> Unit) {
    IconButton(onClick = onSettings, enabled = enabled) { Icon(Icons.Filled.Settings, contentDescription = "Connection settings") }
}

@Composable
private fun MoreButton(onMore: () -> Unit) {
    IconButton(onClick = onMore) { Icon(Icons.Filled.MoreVert, contentDescription = "More controls") }
}
