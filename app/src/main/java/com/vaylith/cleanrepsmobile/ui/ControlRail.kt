package com.vaylith.cleanrepsmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * The size of the rail and the line budget of its reason and hint lines. The lines wrap and are
 * never cut; the budget keeps every string short enough to take at most [MAX_LINES] lines at the
 * default font scale, so the rail stays compact. [lines] estimates the wrap conservatively:
 * [AVERAGE_CHAR_EM] is wider than Roboto's average advance for English text.
 */
object RailText {
    /** The landscape rail's content width, and the narrowest width the lines get (see [portraitWidthDp]). */
    const val LANDSCAPE_WIDTH_DP = 300

    /** The scrim's inner padding. */
    const val PADDING_DP = 12

    /** The camera screen's margin inside the `safeDrawing` insets. */
    const val EDGE_DP = 8
    const val SIZE_SP = 18
    const val MAX_LINES = 3
    private const val AVERAGE_CHAR_EM = 0.6

    /** The width of the lines in the portrait band, which spans the window. */
    fun portraitWidthDp(windowWidthDp: Int): Int = windowWidthDp - 2 * EDGE_DP - 2 * PADDING_DP

    /** Characters that surely fit on one line of [widthDp] at [SIZE_SP] sp. */
    fun charsPerLine(widthDp: Int): Int = (widthDp / (SIZE_SP * AVERAGE_CHAR_EM)).toInt()

    /** [text] wrapped greedily at word boundaries; a word longer than a line stays one over-long line. */
    fun lines(text: String, widthDp: Int = LANDSCAPE_WIDTH_DP): List<String> {
        val budget = charsPerLine(widthDp)
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.trim().split(Regex("""\s+"""))) {
            line = when {
                line.isEmpty() -> word
                line.length + 1 + word.length <= budget -> "$line $word"
                else -> {
                    lines += line
                    word
                }
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    fun fits(text: String, widthDp: Int = LANDSCAPE_WIDTH_DP): Boolean {
        val wrapped = lines(text, widthDp)
        return wrapped.size <= MAX_LINES && wrapped.all { it.length <= charsPerLine(widthDp) }
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
    val scrim = modifier.background(RailScrim, RoundedCornerShape(20.dp)).padding(RailText.PADDING_DP.dp)
    if (landscape) {
        // Scrolls, so a wrapped reason, the hint, Restart video and Stop video never overflow a short landscape window.
        Column(
            scrim.width(RailText.LANDSCAPE_WIDTH_DP.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SettingsButton(model.settingsEnabled, onSettings)
                MoreButton(onMore)
            }
            PrimaryButton(model, onCommand, Modifier.fillMaxWidth())
            RailLines(model, onCommand)
            if (model.stopVideoShown) StopVideoButton(model.stopVideoEnabled, onStopVideo, Modifier.fillMaxWidth())
        }
    } else {
        Column(scrim.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsButton(model.settingsEnabled, onSettings)
                PrimaryButton(model, onCommand, Modifier.weight(1f))
                MoreButton(onMore)
            }
            // Below the button row, the lines get the band's full width rather than the button's share.
            RailLines(model, onCommand)
            if (model.stopVideoShown) StopVideoButton(model.stopVideoEnabled, onStopVideo, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PrimaryButton(model: ControlRailModel, onCommand: (RailCommand) -> Unit, modifier: Modifier) {
    Button(
        onClick = { onCommand(model.primary.action.command()) },
        enabled = model.primary.enabled,
        modifier = modifier.heightIn(min = 64.dp),
    ) {
        Text(model.primary.action.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

/** The reason under a disabled button, the live-analysis hint, and the hint's one-tap action. */
@Composable
private fun RailLines(model: ControlRailModel, onCommand: (RailCommand) -> Unit) {
    model.reason?.let { RailLine(it) }
    model.hint?.let { RailLine(it) }
    if (model.hintAction != null) {
        TextButton(onClick = { onCommand(model.hintAction.command()) }) { Text(model.hintAction.label) }
    }
}

/** Wraps onto as many lines as it needs and is never cut; [RailText] keeps every string within its budget. */
@Composable
private fun RailLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, fontSize = RailText.SIZE_SP.sp, textAlign = TextAlign.Center)
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
