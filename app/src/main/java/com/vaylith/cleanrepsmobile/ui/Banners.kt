package com.vaylith.cleanrepsmobile.ui

import android.os.SystemClock
import android.view.OrientationEventListener
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vaylith.cleanrepsmobile.media.CaptureGeometry
import com.vaylith.cleanrepsmobile.media.PreviewStatus
import com.vaylith.cleanrepsmobile.model.CaptureReadiness
import com.vaylith.cleanrepsmobile.session.AppState
import kotlinx.coroutines.delay

enum class BannerKind {
    /** The phone was turned while the video runs locked (OD-7). */
    ROTATION,

    /** The video stopped because Clean Reps left the screen. */
    LEFT_SCREEN,

    /** The camera failed; the text names the step. */
    CAMERA_ERROR,

    /** A step or the video connection failed; the text names the step (M3a). */
    ERROR,

    /** A new geometry waits until the video stops. */
    ROTATION_PENDING,

    /** The last verdict could not be judged. */
    UNJUDGED,
}

/** The one action a banner offers. */
enum class BannerAction(val label: String) {
    REOPEN_CAMERA("Reopen camera"),
    STOP_VIDEO("Stop video"),
}

data class Banner(
    val kind: BannerKind,
    val text: String,
    val action: BannerAction? = null,
    val actionEnabled: Boolean = false,
    /** A line under the text, e.g. why Reopen camera is not offered. */
    val note: String? = null,
)

/**
 * Which banners the camera screen shows, in order, from the controller state and the physical
 * rotation banner of [TurnWatch]. Pure.
 */
object Banners {
    /**
     * While a stream runs, RootEncoder's startPreview skips opening the camera, so Reopen camera
     * would show a ready preview with a dead camera. It is offered only while the video is off.
     */
    const val REOPEN_AFTER_STOP = "Stop video, then tap Reopen camera."

    private val VIDEO_FAILED = setOf(CaptureReadiness.ERROR, CaptureReadiness.PUBLISHER_UNAVAILABLE)

    fun select(state: AppState, turned: String?): List<Banner> {
        val banners = mutableListOf<Banner>()
        fun add(banner: Banner) {
            if (banner.text.isNotBlank() && banners.none { it.text == banner.text }) banners += banner
        }
        turned?.let { add(Banner(BannerKind.ROTATION, it)) }
        state.banner?.let { add(Banner(BannerKind.LEFT_SCREEN, it)) }
        when (state.preview) {
            is PreviewStatus.CameraError -> add(cameraError(state))
            is PreviewStatus.RotationPending -> add(Banner(BannerKind.ROTATION_PENDING, state.previewDetail))
            else -> Unit
        }
        (state.stepError ?: state.statusDetail.takeIf { state.readiness in VIDEO_FAILED })?.let { add(Banner(BannerKind.ERROR, it)) }
        state.feedbackBanner?.let { add(Banner(BannerKind.UNJUDGED, it)) }
        return banners
    }

    /** The status line under the pill: the controller's last status, unless a banner already says it. */
    fun caption(state: AppState, banners: List<Banner>): String? =
        state.statusDetail.takeIf { detail -> detail.isNotBlank() && banners.none { it.text == detail } }

    /** Reopen camera while the video is off; while it runs, Stop video instead. */
    fun cameraError(state: AppState): Banner = if (state.videoRunning) {
        Banner(BannerKind.CAMERA_ERROR, state.previewDetail, BannerAction.STOP_VIDEO, actionEnabled = !state.requestInFlight, note = REOPEN_AFTER_STOP)
    } else {
        Banner(BannerKind.CAMERA_ERROR, state.previewDetail, BannerAction.REOPEN_CAMERA, actionEnabled = !state.requestInFlight)
    }
}

/**
 * The physical-rotation banner while the video runs locked: `OrientationEventListener` readings
 * go through M3c's [PhysicalOrientation] quantizer (30-degree hysteresis, 1.5 s stability, a flat
 * phone ignored), which starts at the quadrant the video was started in. The banner names the
 * locked capture orientation. Pure; one per started video.
 */
class TurnWatch(private val geometry: CaptureGeometry) {
    private val quantizer = PhysicalOrientation(Quadrant.forDisplayRotation(geometry.displayRotation))

    fun reading(degrees: Int, nowMs: Long): String? =
        PhysicalOrientation.turnedBanner(true, geometry.displayRotation, geometry.orientation, quantizer.update(degrees, nowMs))
}

/** How often the last reading is fed again, so a phone held still after a turn still completes the 1.5 s wait. */
private const val REFEED_MS = 250L

/**
 * The rotation banner for the video started with [geometry], or null while no video runs. The
 * listener reports only changed angles, so the last reading is fed again every [REFEED_MS].
 */
@Composable
fun rememberTurnedBanner(geometry: CaptureGeometry?): String? {
    val context = LocalContext.current
    var banner by remember(geometry) { mutableStateOf<String?>(null) }
    val watch = remember(geometry) { geometry?.let(::TurnWatch) }
    var lastDegrees by remember(geometry) { mutableStateOf<Int?>(null) }
    DisposableEffect(watch) {
        if (watch == null) return@DisposableEffect onDispose {}
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                lastDegrees = orientation
                banner = watch.reading(orientation, SystemClock.elapsedRealtime())
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    LaunchedEffect(watch) {
        if (watch == null) return@LaunchedEffect
        while (true) {
            delay(REFEED_MS)
            lastDegrees?.let { banner = watch.reading(it, SystemClock.elapsedRealtime()) }
        }
    }
    return banner
}

private val BannerScrim = Color.Black.copy(alpha = 0.7f)

@Composable
fun BannerStack(banners: List<Banner>, onAction: (BannerAction) -> Unit, modifier: Modifier = Modifier) {
    if (banners.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        banners.forEach { banner -> BannerCard(banner, onAction) }
    }
}

@Composable
private fun BannerCard(banner: Banner, onAction: (BannerAction) -> Unit) {
    val color = when (banner.kind) {
        BannerKind.CAMERA_ERROR, BannerKind.ERROR, BannerKind.LEFT_SCREEN -> MaterialTheme.colorScheme.error
        BannerKind.ROTATION, BannerKind.ROTATION_PENDING, BannerKind.UNJUDGED -> Color(0xFFFFD54F)
    }
    Column(Modifier.background(BannerScrim, RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(banner.text, color = color, style = MaterialTheme.typography.bodyLarge)
        banner.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        banner.action?.let { action ->
            OutlinedButton(onClick = { onAction(action) }, enabled = banner.actionEnabled) { Text(action.label) }
        }
    }
}
