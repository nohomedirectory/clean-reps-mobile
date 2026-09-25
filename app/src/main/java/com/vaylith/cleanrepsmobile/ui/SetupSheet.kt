package com.vaylith.cleanrepsmobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vaylith.cleanrepsmobile.model.BlockSelection
import com.vaylith.cleanrepsmobile.model.ConnectionSettings
import com.vaylith.cleanrepsmobile.model.KickSide
import com.vaylith.cleanrepsmobile.model.KickTarget
import com.vaylith.cleanrepsmobile.model.KickTechnique
import com.vaylith.cleanrepsmobile.model.TargetHeight
import com.vaylith.cleanrepsmobile.session.AppState

/** The parts of the setup sheet; the gear opens CONNECTION and the drill chip opens DRILL. */
enum class SetupSection(val title: String) {
    DRILL("Drill"),
    CONNECTION("Connection"),
    AUDIO("Audio"),
}

/** A technique in the drill picker. Only an auto-judged technique can be enabled (OD-5). */
data class TechniqueOption(val technique: KickTechnique, val enabled: Boolean, val note: String?)

/** What the setup sheet allows, from the controller state. Pure. */
object SetupRules {
    /** OD-5: switching technique while live would quarantine the capture, so the others are shown but never selectable. */
    const val NOT_AUTO_JUDGED = "not auto-judged yet"
    const val DRILL_LOCKED = "Pause practice to change the drill."
    const val CONNECTION_LOCKED = "Stop video to change the connection."
    const val SAVE_FAILED = "Could not save settings on this phone."

    /** The drill changes only while practice is not active (the controller refuses it too). */
    fun drillEditable(state: AppState): Boolean = !state.practiceActive && !state.requestInFlight

    /** Connection settings change only while video and practice are stopped. */
    fun connectionEditable(state: AppState): Boolean = !state.videoRunning && !state.practiceActive && !state.requestInFlight

    fun techniques(state: AppState): List<TechniqueOption> = KickTechnique.entries.map { technique ->
        TechniqueOption(technique, enabled = technique.autoJudged && drillEditable(state), note = if (technique.autoJudged) null else NOT_AUTO_JUDGED)
    }

    /** The selection a tap on [technique] asks for; null for a technique that is not judged automatically. */
    fun withTechnique(selection: BlockSelection, technique: KickTechnique): BlockSelection? =
        if (technique.autoJudged) selection.copy(technique = technique) else null

    /** The drill chip, e.g. "Teep - Right - Hanging bag", with the target height when one is chosen. */
    fun drillLabel(selection: BlockSelection): String =
        listOfNotNull(selection.technique.label, selection.side.label, selection.targetContext.label, selection.targetHeight?.let { "${it.label} target" })
            .joinToString(" - ")
}

private val SheetScrim = Color.Black.copy(alpha = 0.45f)

/**
 * Connection settings, the drill picker and the audio options, over the preview: a bottom sheet
 * in portrait, a side sheet on the right in landscape, inside the `safeDrawing` insets. A tap on
 * the scrim or Back closes it.
 */
@Composable
fun SetupSheet(
    section: SetupSection,
    onSection: (SetupSection) -> Unit,
    landscape: Boolean,
    state: AppState,
    settings: ConnectionSettings,
    onSelectDrill: (BlockSelection) -> Unit,
    onSaveConnection: (ConnectionSettings) -> Unit,
    onAudioTest: () -> Unit,
    onToggleVoiceHints: () -> Unit,
    onToggleSpeakVerdicts: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(SheetScrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
        val shape = RoundedCornerShape(20.dp)
        val panel = if (landscape) {
            Modifier.align(Alignment.CenterEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp).width(440.dp).fillMaxHeight()
        } else {
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp).fillMaxWidth()
                .heightIn(max = maxHeight * 0.8f)
        }
        Surface(panel, shape = shape, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SetupSection.entries.forEach { entry ->
                            FilterChip(selected = entry == section, onClick = { onSection(entry) }, label = { Text(entry.title) })
                        }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close setup") }
                }
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (section) {
                        SetupSection.DRILL -> DrillSection(state, onSelectDrill)
                        SetupSection.CONNECTION -> ConnectionSection(state, settings, onSaveConnection, onDismiss)
                        SetupSection.AUDIO -> AudioSection(state, onAudioTest, onToggleVoiceHints, onToggleSpeakVerdicts)
                    }
                }
            }
        }
    }
}

@Composable
private fun DrillSection(state: AppState, onSelectDrill: (BlockSelection) -> Unit) {
    val selection = state.selection
    val editable = SetupRules.drillEditable(state)
    Text("Private rehearsal - no official challenge credit", style = MaterialTheme.typography.titleMedium)
    if (state.practiceActive) Text(SetupRules.DRILL_LOCKED, color = MaterialTheme.colorScheme.error)
    SheetLabel("Technique")
    ChipRow {
        SetupRules.techniques(state).forEach { option ->
            FilterChip(
                selected = option.technique == selection.technique,
                enabled = option.enabled,
                onClick = { SetupRules.withTechnique(selection, option.technique)?.let(onSelectDrill) },
                label = {
                    Column {
                        Text(option.technique.label)
                        option.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                },
            )
        }
    }
    SheetLabel("Side")
    Choices(KickSide.entries, selection.side, editable, { it.label }) { onSelectDrill(selection.copy(side = it)) }
    SheetLabel("Target")
    Choices(KickTarget.entries, selection.targetContext, editable, { it.label }) { onSelectDrill(selection.copy(targetContext = it)) }
    SheetLabel("Target height (optional)")
    Choices(listOf<TargetHeight?>(null) + TargetHeight.entries, selection.targetHeight, editable, { it?.label ?: "Any height" }) {
        onSelectDrill(selection.copy(targetHeight = it))
    }
}

/** The connection fields and validation of the old connection dialog; passwords are masked. */
@Composable
private fun ConnectionSection(state: AppState, settings: ConnectionSettings, onSave: (ConnectionSettings) -> Unit, onDone: () -> Unit) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var error by remember(settings) { mutableStateOf<String?>(null) }
    val editable = SetupRules.connectionEditable(state)
    Text("Use the existing server settings. Tailscale must be connected. These settings stay on this phone.")
    if (!editable) Text(SetupRules.CONNECTION_LOCKED, color = MaterialTheme.colorScheme.error)
    OutlinedTextField(value = draft.apiBaseUrl, onValueChange = { draft = draft.copy(apiBaseUrl = it.trim()) }, enabled = editable,
        label = { Text("Clean Reps address") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = draft.srtHost, onValueChange = { draft = draft.copy(srtHost = it.trim()) }, enabled = editable,
        label = { Text("Video host and port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = draft.srtPassphrase, onValueChange = { draft = draft.copy(srtPassphrase = it) }, enabled = editable,
        label = { Text("Video passphrase") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = draft.publishPassword, onValueChange = { draft = draft.copy(publishPassword = it) }, enabled = editable,
        label = { Text("Publish password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = editable, onClick = {
            error = draft.validationError()
            if (error == null) {
                runCatching { onSave(draft) }.onSuccess { onDone() }.onFailure { error = SetupRules.SAVE_FAILED }
            }
        }) { Text("Save connection") }
        OutlinedButton(onClick = onDone) { Text("Cancel") }
    }
}

@Composable
private fun AudioSection(state: AppState, onAudioTest: () -> Unit, onToggleVoiceHints: () -> Unit, onToggleSpeakVerdicts: () -> Unit) {
    Button(onClick = onAudioTest) { Text("Audio test") }
    SwitchRow("Voice hints", state.voiceHints, onToggleVoiceHints)
    SwitchRow("Speak verdicts", state.debugSpeakVerdicts, onToggleSpeakVerdicts)
    Text("Video live means this phone reached the video server. It does not confirm a public broadcast or automatic judging.",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun <T> Choices(choices: List<T>, selected: T, enabled: Boolean, label: (T) -> String, onSelect: (T) -> Unit) {
    ChipRow {
        choices.forEach { value ->
            FilterChip(selected = value == selected, enabled = enabled, onClick = { onSelect(value) }, label = { Text(label(value)) })
        }
    }
}
