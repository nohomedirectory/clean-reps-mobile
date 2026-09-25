package com.vaylith.cleanrepsmobile.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vaylith.cleanrepsmobile.media.CaptureGeometry
import com.vaylith.cleanrepsmobile.model.CaptureOrientation
import com.vaylith.cleanrepsmobile.model.CaptureReadiness
import com.vaylith.cleanrepsmobile.model.LiveAthleteRegion
import com.vaylith.cleanrepsmobile.model.LiveSourceGeometry
import com.vaylith.cleanrepsmobile.model.LiveSourceOrientation
import com.vaylith.cleanrepsmobile.session.AppState
import kotlin.math.roundToInt

/**
 * The framing guide: the analyzer's athlete region (`liveAnalysis.athleteRegion`, normalized to the
 * FULL transmitted frame) mapped through M3c's [PreviewLayout] crop mapping and clipped to the
 * window, labelled [LABEL], with the [HINT]. There is no separate safe-area inset: the fill crop
 * already builds in a margin. Pure.
 */
object FramingGuide {
    const val LABEL = "Stay in this area"
    const val HINT = "Head and feet on screen - about 3-4 m away - phone at waist height"

    /**
     * The transmitted frame: the geometry the encoder is prepared with, or before the first
     * prepare the 16:9 frame of the window's orientation, as the preview surface assumes.
     */
    fun streamSize(prepared: CaptureGeometry?, windowW: Int, windowH: Int): Pair<Int, Int> = when {
        prepared != null -> prepared.encodedWidth to prepared.encodedHeight
        windowW > windowH -> CaptureGeometry.PREPARE_WIDTH to CaptureGeometry.PREPARE_HEIGHT
        else -> CaptureGeometry.PREPARE_HEIGHT to CaptureGeometry.PREPARE_WIDTH
    }

    /**
     * The region on screen in window pixels, clipped to the window, for the preview [mode] the
     * screen uses. Null when there is no region, when all of it lies in a cropped margin, or when
     * the analyzer decodes a frame of the other orientation (a sideways video), where the box would
     * mislead.
     */
    fun box(
        region: LiveAthleteRegion?,
        source: LiveSourceGeometry?,
        prepared: CaptureGeometry?,
        windowW: Int,
        windowH: Int,
        mode: PreviewMode,
    ): ScreenBox? {
        if (region == null || windowW <= 0 || windowH <= 0) return null
        val (streamW, streamH) = streamSize(prepared, windowW, windowH)
        val sourceOrientation = source?.orientation ?: LiveSourceOrientation.UNKNOWN
        if (sourceOrientation != LiveSourceOrientation.UNKNOWN && (sourceOrientation == LiveSourceOrientation.PORTRAIT) != (streamH > streamW)) return null
        val shown = PreviewLayout.compute(mode, windowW, windowH, streamW, streamH)
        return PreviewLayout.regionOnScreen(shown, windowW, windowH, region)
    }

    /** Shown before practice; faded out while practice is active. */
    fun alpha(practiceActive: Boolean): Float = if (practiceActive) 0f else 1f
}

/** "Landscape works best for kicks", shown while the prepared geometry is portrait until the athlete dismisses it. */
object PortraitHint {
    const val TEXT = "Landscape works best for kicks"

    /** The window must be portrait too, so a geometry read before a rotation is applied never shows it in landscape. */
    fun shown(prepared: CaptureOrientation?, windowPortrait: Boolean, dismissed: Boolean): Boolean =
        !dismissed && windowPortrait && prepared == CaptureOrientation.PORTRAIT
}

/**
 * The region box over the preview, in window pixels, while the video is LIVE and the analyzer
 * has reported a region. It fades during practice. [mode] is the screen's preview mode.
 */
@Composable
fun FramingBox(state: AppState, prepared: CaptureGeometry?, windowW: Int, windowH: Int, mode: PreviewMode) {
    val alpha by animateFloatAsState(FramingGuide.alpha(state.practiceActive), tween(600), label = "framing box")
    val live = state.liveAnalysis?.takeIf { state.readiness == CaptureReadiness.LIVE } ?: return
    val box = FramingGuide.box(live.athleteRegion, live.sourceGeometry, prepared, windowW, windowH, mode) ?: return
    if (alpha == 0f) return
    val color = Color.White.copy(alpha = 0.85f)
    Box(Modifier.fillMaxSize().alpha(alpha)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                color = color,
                topLeft = Offset(box.left.toFloat(), box.top.toFloat()),
                size = Size((box.right - box.left).toFloat(), (box.bottom - box.top).toFloat()),
                style = Stroke(width = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 16f))),
            )
        }
        Text(
            FramingGuide.LABEL, style = MaterialTheme.typography.labelLarge, color = Color.White,
            modifier = Modifier.offset { IntOffset(box.left.roundToInt() + 8.dp.roundToPx(), box.top.roundToInt() + 8.dp.roundToPx()) }
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * The framing hint (faded during practice) and the dismissible portrait hint, for the bottom of
 * the area beside the rail.
 */
@Composable
fun FramingHints(state: AppState, portraitHint: Boolean, onDismissPortraitHint: () -> Unit, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(FramingGuide.alpha(state.practiceActive), tween(600), label = "framing hint")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (portraitHint) {
            Row(
                Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(14.dp)).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(PortraitHint.TEXT, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                IconButton(onClick = onDismissPortraitHint) { Icon(Icons.Filled.Close, contentDescription = "Dismiss landscape hint") }
            }
        }
        if (alpha > 0f) {
            Text(
                FramingGuide.HINT, style = MaterialTheme.typography.bodyLarge, color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.alpha(alpha).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
