package com.vaylith.cleanrepsmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vaylith.cleanrepsmobile.model.CaptureReadiness
import com.vaylith.cleanrepsmobile.session.AppState

/** How the pill's dot reads at a glance. */
enum class PillTone { LIVE, WAITING, ERROR, OFF }

/**
 * The status pill over the preview: the video state and, while LIVE, the geometry the encoder was
 * prepared with (the controller's [AppState.livePill], e.g. "LIVE - landscape 1280x720").
 */
object StatusPill {
    const val CONNECTING = "Connecting..."
    const val RECONNECTING = "Reconnecting..."
    const val OFF = "Video off"
    const val UNAVAILABLE = "Video unavailable"
    const val ERROR = "Video error"

    fun label(state: AppState): String = state.livePill ?: when (state.readiness) {
        CaptureReadiness.LIVE -> "LIVE"
        CaptureReadiness.CONNECTING -> CONNECTING
        CaptureReadiness.RECONNECTING -> RECONNECTING
        // A configured phone also starts NOT_CONFIGURED until its first video; the primary button says what is missing.
        CaptureReadiness.NOT_CONFIGURED, CaptureReadiness.STOPPED -> OFF
        CaptureReadiness.PUBLISHER_UNAVAILABLE -> UNAVAILABLE
        CaptureReadiness.ERROR -> ERROR
    }

    fun tone(readiness: CaptureReadiness): PillTone = when (readiness) {
        CaptureReadiness.LIVE -> PillTone.LIVE
        CaptureReadiness.CONNECTING, CaptureReadiness.RECONNECTING -> PillTone.WAITING
        CaptureReadiness.PUBLISHER_UNAVAILABLE, CaptureReadiness.ERROR -> PillTone.ERROR
        CaptureReadiness.NOT_CONFIGURED, CaptureReadiness.STOPPED -> PillTone.OFF
    }
}

private val PillScrim = Color.Black.copy(alpha = 0.6f)

@Composable
fun StatusPill(state: AppState, modifier: Modifier = Modifier) {
    val dot = when (StatusPill.tone(state.readiness)) {
        PillTone.LIVE -> Color(0xFFE53935)
        PillTone.WAITING -> Color(0xFFFFB300)
        PillTone.ERROR -> MaterialTheme.colorScheme.error
        PillTone.OFF -> Color(0xFF9E9E9E)
    }
    Row(
        modifier.background(PillScrim, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).background(dot, CircleShape))
        Text(StatusPill.label(state), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
