package com.vaylith.cleanrepsmobile.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vaylith.cleanrepsmobile.BuildConfig
import com.vaylith.cleanrepsmobile.api.ServerHealth
import com.vaylith.cleanrepsmobile.media.CanonicalSourcePublisher
import com.vaylith.cleanrepsmobile.media.CaptureGeometry

/** This app build and the phone it runs on. Never any connection setting. */
data class BuildIdentity(
    val versionName: String,
    val versionCode: Int,
    val gitSha: String,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
) {
    companion object {
        /** From BuildConfig's identity fields only: its other fields carry the private connection settings. */
        fun current(): BuildIdentity = BuildIdentity(
            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GIT_SHA, Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(), Build.VERSION.SDK_INT,
        )
    }
}

/**
 * What the Diagnostics sheet shows, derived purely: the build identity and the server release
 * (C5), the camera's sensor orientation and the geometry the encoder is prepared with (flagging
 * the unverified sensor-270 compensation), the last events newest first, and the Copy text (M3a's
 * redacted export). [eventLines] are `DiagnosticsLog.exportLines()`: every line redacted again
 * with the current settings, like the Copy text, never the entries as they were recorded.
 */
data class DiagnosticsModel(
    val identity: List<String>,
    val camera: List<String>,
    /** True for a 270-degree sensor, whose compensation is not verified on hardware. */
    val cameraUnverified: Boolean,
    val events: List<String>,
    val copyText: String,
) {
    companion object {
        const val UNVERIFIED_270 = "Sensor at 270 degrees: the rotation compensation is not verified on this hardware. Check the frame below."
        const val NO_EVENTS = "No events yet"

        fun from(
            build: BuildIdentity,
            sensorDeg: Int?,
            geometry: CaptureGeometry?,
            eventLines: List<String>,
            exportText: String,
            health: ServerHealth?,
        ): DiagnosticsModel {
            val unverified = geometry?.sensorCompensationUnverified == true || sensorDeg == 270
            return DiagnosticsModel(
                identity = listOf(
                    "App ${build.versionName} (version code ${build.versionCode})",
                    "Git SHA ${build.gitSha}",
                    "Phone ${build.manufacturer} ${build.model}, Android SDK ${build.sdkInt}".replace("  ", " "),
                    serverLine(health),
                ),
                camera = listOfNotNull(
                    "Back camera sensor orientation: ${sensorDeg?.let { "$it degrees" } ?: "unavailable"}",
                    geometry?.let {
                        "Prepared geometry: ${it.label}, display rotation ${it.displayRotationDeg} degrees, rotation argument ${it.rotationArg}"
                    } ?: "Prepared geometry: not prepared yet",
                    UNVERIFIED_270.takeIf { unverified },
                ),
                cameraUnverified = unverified,
                events = eventLines.asReversed().ifEmpty { listOf(NO_EVENTS) },
                copyText = exportText,
            )
        }

        /** The server's full `release` from `GET /health` (the chip shows 7 characters), as clean-reps C5 asks. */
        fun serverLine(health: ServerHealth?): String = when {
            health == null -> "Server release: not checked yet"
            !health.reachable -> "Server release: unknown (server unreachable)"
            else -> "Server release ${health.release ?: "not reported"}"
        }

        /** The Frame check result line: the size of the WHOLE transmitted frame, including what the filled preview crops. */
        fun frameCheckText(width: Int, height: Int): String =
            if (width <= 0 || height <= 0) {
                "No frame: the preview is not running, or no frame arrived in time."
            } else {
                "Whole transmitted frame: ${width}x$height (${if (width > height) "landscape" else if (height > width) "portrait" else "square"})"
            }
    }
}

/** One Frame check answer, kept in memory only. */
private data class FrameCheckResult(val image: ImageBitmap?, val text: String)

/**
 * Build identity, the camera geometry, the last 100 redacted events with Copy, and Frame check:
 * the publisher's `frameCheck` renders the next frame at the encoder size with the stream
 * orientation and no aspect mode, so the owner sees the whole transmitted frame, upright and
 * undistorted, not the filled preview's crop. The frame is shown and never saved.
 */
@Composable
fun DiagnosticsSheet(
    landscape: Boolean,
    publisher: CanonicalSourcePublisher,
    health: ServerHealth?,
    eventLines: () -> List<String>,
    exportText: () -> String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var refresh by remember { mutableIntStateOf(0) }
    val model = remember(refresh, health) {
        DiagnosticsModel.from(BuildIdentity.current(), publisher.sensorOrientationDeg, publisher.preparedGeometry, eventLines(), exportText(), health)
    }
    var frame by remember { mutableStateOf<FrameCheckResult?>(null) }
    var checking by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    ScreenPanel(title = "Diagnostics", landscape = landscape, onDismiss = onDismiss) {
        PanelSection("Build")
        model.identity.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        PanelSection("Camera")
        model.camera.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium,
                color = if (line == DiagnosticsModel.UNVERIFIED_270) MaterialTheme.colorScheme.error else Color.Unspecified)
        }
        PanelSection("Frame check")
        Text("Shows the whole frame the phone sends, including the edges the full-screen preview crops.", style = MaterialTheme.typography.bodySmall)
        Button(enabled = !checking, onClick = {
            checking = true
            publisher.frameCheck { bitmap: Bitmap?, width: Int, height: Int ->
                frame = FrameCheckResult(bitmap?.asImageBitmap(), DiagnosticsModel.frameCheckText(width, height))
                checking = false
            }
        }) { Text(if (checking) "Checking..." else "Frame check") }
        frame?.let { result ->
            Text(result.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            // Fit keeps the frame's own proportions: nothing is cropped or stretched.
            result.image?.let { Image(it, contentDescription = result.text, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) }
        }
        PanelSection("Recent events (redacted, newest first)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(model.copyText))
                copied = true
            }) { Text(if (copied) "Copied" else "Copy") }
            OutlinedButton(onClick = { refresh++; copied = false }) { Text("Refresh") }
        }
        model.events.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    }
}

@Composable
internal fun PanelSection(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

private val PanelScrim = Color.Black.copy(alpha = 0.5f)

/**
 * A panel over the camera screen: from the bottom in portrait, from the right in landscape,
 * inside the `safeDrawing` insets, with a title, Close, a scrolling body, and Back or a tap on
 * the scrim to dismiss it.
 */
@Composable
internal fun ScreenPanel(title: String, landscape: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onDismiss)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(PanelScrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
        val panel = if (landscape) {
            Modifier.align(Alignment.CenterEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp).width(480.dp).fillMaxHeight()
        } else {
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp).fillMaxWidth()
                .heightIn(max = maxHeight * 0.85f)
        }
        Surface(panel, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close $title") }
                }
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
            }
        }
    }
}
