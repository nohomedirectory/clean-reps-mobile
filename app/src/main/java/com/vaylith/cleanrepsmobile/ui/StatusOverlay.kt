package com.vaylith.cleanrepsmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.vaylith.cleanrepsmobile.api.ServerHealth
import com.vaylith.cleanrepsmobile.model.CaptureReadiness
import com.vaylith.cleanrepsmobile.model.LiveAnalysisState
import com.vaylith.cleanrepsmobile.model.LiveAnalysisStatus
import com.vaylith.cleanrepsmobile.model.phoneText
import com.vaylith.cleanrepsmobile.session.AppState
import kotlinx.coroutines.delay

/** How a chip reads at a glance. */
enum class ChipTone { GOOD, WORKING, PROBLEM, UNAVAILABLE }

/** The analysis chip: the C3 phone text of the current state, and the Paused badge. */
data class AnalysisChip(val text: String, val paused: Boolean, val tone: ChipTone)

/** The server chip: reachable (with the release), unreachable, or not checked yet ([reachable] null). */
data class ReachabilityChip(val text: String, val reachable: Boolean?)

/**
 * The status overlay beside the pill, derived purely from the controller state and the last
 * `GET /health` answer. The analysis chip shows only while the video is LIVE, as M3c's rail hint
 * does; the counts are the server's per-session counts from the session stream, never a local count.
 */
data class StatusOverlayModel(val analysis: AnalysisChip?, val reachability: ReachabilityChip, val counts: String?) {
    companion object {
        const val PAUSED = "Paused"
        const val CHECKING = "Server: checking..."
        const val UNREACHABLE = "Server unreachable"

        /** A release that is a git SHA is shown by its first 7 characters (the full value is in Diagnostics). */
        private val SHA = Regex("[0-9a-f]{7,40}")

        fun from(state: AppState, health: ServerHealth?): StatusOverlayModel = StatusOverlayModel(
            analysis = state.liveAnalysis?.takeIf { state.readiness == CaptureReadiness.LIVE }?.let(::analysis),
            reachability = reachability(health),
            counts = state.sessionCountsText,
        )

        /**
         * The single `phoneText` table (model/LiveAnalysisModels.kt) gives the text. A stale status reads
         * "Analysis status unavailable"; a state or reason this app does not know reads as its safe
         * fallback ("Analysis status unknown", "Analysis stopped"). `practicePaused` adds the badge.
         */
        fun analysis(status: LiveAnalysisStatus): AnalysisChip {
            val state = if (status.available) status.state else LiveAnalysisState.STALE
            return AnalysisChip(phoneText(state, status.reasonCode), status.practicePaused, tone(state))
        }

        fun reachability(health: ServerHealth?): ReachabilityChip = when {
            health == null -> ReachabilityChip(CHECKING, reachable = null)
            health.reachable -> ReachabilityChip("Server reachable - release ${shortRelease(health.release)}", reachable = true)
            else -> ReachabilityChip(UNREACHABLE, reachable = false)
        }

        fun shortRelease(release: String?): String = when {
            release == null -> "not reported"
            SHA.matches(release) -> release.take(7)
            else -> release
        }

        private fun tone(state: LiveAnalysisState): ChipTone = when (state) {
            LiveAnalysisState.TRACKING -> ChipTone.GOOD
            LiveAnalysisState.STARTING, LiveAnalysisState.ACQUIRING -> ChipTone.WORKING
            LiveAnalysisState.NO_PERSON, LiveAnalysisState.SIDEWAYS, LiveAnalysisState.HEAD_CUT, LiveAnalysisState.FEET_CUT,
            LiveAnalysisState.TOO_SMALL, LiveAnalysisState.MULTIPLE_PEOPLE, LiveAnalysisState.BLOCKED -> ChipTone.PROBLEM
            LiveAnalysisState.STALE, LiveAnalysisState.UNKNOWN -> ChipTone.UNAVAILABLE
        }
    }
}

/** The reachability poll interval while the camera screen is visible. */
const val HEALTH_POLL_MS = 15_000L

/** Checks at once, then every [HEALTH_POLL_MS], until cancelled. */
suspend fun pollServerHealth(check: suspend () -> ServerHealth, onHealth: (ServerHealth) -> Unit): Nothing {
    while (true) {
        onHealth(check())
        delay(HEALTH_POLL_MS)
    }
}

/**
 * The last `GET /health` answer for [key] (the controller: a new connection starts over), polled
 * only while the screen is at least STARTED.
 */
@Composable
fun rememberServerHealth(key: Any, check: suspend () -> ServerHealth): ServerHealth? {
    var health by remember(key) { mutableStateOf<ServerHealth?>(null) }
    val currentCheck by rememberUpdatedState(check)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(key, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            pollServerHealth({ currentCheck() }) { health = it }
        }
    }
    return health
}

private val ChipScrim = Color.Black.copy(alpha = 0.6f)

@Composable
fun StatusOverlay(model: StatusOverlayModel, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        model.analysis?.let { chip ->
            Row(
                Modifier.background(ChipScrim, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(toneColor(chip.tone))
                Text(chip.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                if (chip.paused) {
                    Text(StatusOverlayModel.PAUSED, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.Black,
                        modifier = Modifier.background(Color(0xFFFFD54F), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
        Row(
            Modifier.background(ChipScrim, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val reachable = model.reachability.reachable
            Dot(if (reachable == null) Color(0xFF9E9E9E) else if (reachable) Color(0xFF66BB6A) else MaterialTheme.colorScheme.error)
            Text(model.reachability.text, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
        model.counts?.let { counts ->
            Text(counts, style = MaterialTheme.typography.titleMedium, color = Color.White,
                modifier = Modifier.background(ChipScrim, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun toneColor(tone: ChipTone): Color = when (tone) {
    ChipTone.GOOD -> Color(0xFF66BB6A)
    ChipTone.WORKING -> Color(0xFFFFB300)
    ChipTone.PROBLEM -> MaterialTheme.colorScheme.error
    ChipTone.UNAVAILABLE -> Color(0xFF9E9E9E)
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(12.dp).background(color, CircleShape))
}
