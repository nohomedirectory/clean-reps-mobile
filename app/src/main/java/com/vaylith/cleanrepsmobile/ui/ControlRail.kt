package com.vaylith.cleanrepsmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/** The entries of the rail's overflow menu; each maps to one controller or host call. */
enum class OverflowAction(val label: String) {
    AUDIO_TEST("Audio test"),
    VOICE_HINTS("Voice hints"),
    SPEAK_VERDICTS("Speak verdicts"),
    MANUAL_MARKER("Save manual review marker"),
}

/** An overflow entry; [checked] is the on/off state of a toggle and null for an action. */
data class OverflowItem(val action: OverflowAction, val checked: Boolean?)

/**
 * Everything the control rail shows, derived purely from the controller state: the one primary
 * button (M3c's [PrimaryActionState]), the line under it, Stop video while the video runs, the
 * connection-settings gear, which is disabled while live, the drill chip, which is disabled while
 * practising, and the overflow menu.
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
    /** "Teep - Right - Hanging bag"; opens the drill part of the setup sheet. */
    val drillLabel: String,
    val drillEnabled: Boolean,
    val overflow: List<OverflowItem>,
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
                settingsEnabled = SetupRules.connectionEditable(state),
                drillLabel = SetupRules.drillLabel(state.selection),
                drillEnabled = SetupRules.drillEditable(state),
                overflow = overflow(state),
            )
        }

        /** OD-4 toggles show their state; the manual review marker is offered only while practice is active. */
        fun overflow(state: AppState): List<OverflowItem> = buildList {
            add(OverflowItem(OverflowAction.AUDIO_TEST, checked = null))
            add(OverflowItem(OverflowAction.VOICE_HINTS, checked = state.voiceHints))
            add(OverflowItem(OverflowAction.SPEAK_VERDICTS, checked = state.debugSpeakVerdicts))
            if (state.practiceActive) add(OverflowItem(OverflowAction.MANUAL_MARKER, checked = null))
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
 * inside the `safeDrawing` insets. Stop video sits above the reason and hint lines, so it stays
 * in view however far they wrap.
 */
@Composable
fun ControlRail(
    model: ControlRailModel,
    landscape: Boolean,
    onCommand: (RailCommand) -> Unit,
    onStopVideo: () -> Unit,
    onSettings: () -> Unit,
    onDrill: () -> Unit,
    onOverflow: (OverflowAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrim = modifier.background(RailScrim, RoundedCornerShape(20.dp)).padding(RailText.PADDING_DP.dp)
    if (landscape) {
        // Scrolls, so a wrapped reason, the hint and Restart video never overflow a short landscape window.
        Column(
            scrim.width(RailText.LANDSCAPE_WIDTH_DP.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SettingsButton(model.settingsEnabled, onSettings)
                OverflowMenu(model.overflow, onOverflow)
            }
            DrillChip(model, onDrill, Modifier.fillMaxWidth())
            PrimaryButton(model, onCommand, Modifier.fillMaxWidth())
            if (model.stopVideoShown) StopVideoButton(model.stopVideoEnabled, onStopVideo, Modifier.fillMaxWidth())
            RailLines(model, onCommand)
        }
    } else {
        Column(scrim.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            DrillChip(model, onDrill, Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsButton(model.settingsEnabled, onSettings)
                PrimaryButton(model, onCommand, Modifier.weight(1f))
                OverflowMenu(model.overflow, onOverflow)
            }
            if (model.stopVideoShown) StopVideoButton(model.stopVideoEnabled, onStopVideo, Modifier.fillMaxWidth())
            // Below the button row, the lines get the band's full width rather than the button's share.
            RailLines(model, onCommand)
        }
    }
}

@Composable
private fun DrillChip(model: ControlRailModel, onDrill: () -> Unit, modifier: Modifier) {
    AssistChip(
        onClick = onDrill,
        enabled = model.drillEnabled,
        label = { Text(model.drillLabel, style = MaterialTheme.typography.titleMedium) },
        modifier = modifier.semantics { contentDescription = "Drill: ${model.drillLabel}" },
    )
}

@Composable
private fun OverflowMenu(items: List<OverflowItem>, onOverflow: (OverflowAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More controls") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.action.label) },
                    onClick = {
                        expanded = false
                        onOverflow(item.action)
                    },
                    trailingIcon = item.checked?.let { on -> { Text(if (on) "On" else "Off") } },
                )
            }
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
