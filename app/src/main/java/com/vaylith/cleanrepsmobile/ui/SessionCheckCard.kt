package com.vaylith.cleanrepsmobile.ui

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vaylith.cleanrepsmobile.api.ChallengeApi
import com.vaylith.cleanrepsmobile.api.FetchResult
import com.vaylith.cleanrepsmobile.api.JsonFields
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticStep
import com.vaylith.cleanrepsmobile.diagnostics.StepMessages
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A C4 verdict check. */
enum class Check {
    PASS,
    WARN,
    FAIL;

    companion object {
        fun fromWire(value: String?): Check? = entries.firstOrNull { it.name == value }
    }
}

/**
 * The fields of a C4 `GET /v1/captures/{id}/quality-report` body (schema version 1, clean-reps
 * `docs/tonight-sprint/clean-reps-api.md`) that the Session check card shows: the stored report's
 * `final`, `source`, `analysis` counts, `verdict` and `thumbnails`, the ledger `outcomes` and the
 * stored C2 `clientInfo`.
 */
data class QualityReport(
    val final: Boolean,
    val overall: Check,
    val geometry: Check,
    val upright: Check,
    val selectedRate: Check,
    val fullBody: Check,
    val advice: List<String>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val processedFrames: Long,
    val selectedFrames: Long,
    val selectedWithHead: Long,
    val selectedWithAnkle: Long,
    /** The thumbnail indices the report lists: 0 first analysed frame, 1 middle, 2 latest. */
    val thumbnails: List<Int>,
    val accepted: Long,
    val rejected: Long,
    val evidenceFailed: Long,
    /** From the stored C2 client info, when the capture sent one. */
    val clientAppVersion: String?,
    val clientGitSha: String?,
) {
    companion object {
        /** Null for anything that is not a well-formed schema-v1 body: the card then shows a safe error. */
        fun parse(json: String): QualityReport? {
            val body = JsonFields.parse(json) ?: return null
            if (body.long("schemaVersion") != 1L) return null
            val verdict = body.obj("verdict") ?: return null
            val analysis = body.obj("analysis") ?: return null
            val source = body.obj("source") ?: return null
            val outcomes = body.obj("outcomes") ?: return null
            fun check(name: String) = Check.fromWire(verdict.string(name))
            fun count(fields: JsonFields, name: String) = fields.long(name)?.takeIf { it >= 0 }
            val client = if (body.isNull("clientInfo")) null else body.obj("clientInfo")
            return QualityReport(
                final = body.boolean("final") ?: return null,
                overall = check("overall") ?: return null,
                geometry = check("geometry") ?: return null,
                upright = check("upright") ?: return null,
                selectedRate = check("selectedRate") ?: return null,
                fullBody = check("fullBody") ?: return null,
                advice = verdict.strings("advice")?.take(MAX_ADVICE)?.map { it.take(MAX_ADVICE_CHARS) } ?: return null,
                sourceWidth = source.int("width")?.takeIf { it > 0 } ?: return null,
                sourceHeight = source.int("height")?.takeIf { it > 0 } ?: return null,
                processedFrames = count(analysis, "processedFrames") ?: return null,
                selectedFrames = count(analysis, "selectedFrames") ?: return null,
                selectedWithHead = count(analysis, "selectedWithHead") ?: return null,
                selectedWithAnkle = count(analysis, "selectedWithAnkle") ?: return null,
                thumbnails = body.objects("thumbnails")?.map { it.int("index")?.takeIf { index -> index in 0..2 } ?: return null } ?: return null,
                accepted = count(outcomes, "accepted") ?: return null,
                rejected = count(outcomes, "rejected") ?: return null,
                evidenceFailed = count(outcomes, "evidenceFailed") ?: return null,
                clientAppVersion = client?.text("appVersionName"),
                clientGitSha = client?.text("appGitSha"),
            )
        }

        /** The contract's caps: at most 8 advice strings of at most 120 characters. */
        private const val MAX_ADVICE = 8
        private const val MAX_ADVICE_CHARS = 120
    }
}

/** What the card shows for one report. Pure. */
data class SessionCheckCardModel(
    val final: Boolean,
    val overall: Check,
    val checks: List<Pair<String, Check>>,
    val advice: List<String>,
    val bodyVisible: String,
    val headAndFeet: String,
    val judged: String,
    val versions: String,
    val thumbnails: List<Int>,
) {
    companion object {
        const val NO_ANALYSIS = "No analysis ran for this capture"
        const val UNREADABLE = "The session check report could not be read"
        const val INTERIM = "Interim report: the final report did not arrive within 20 s"
        const val WAITING = "Waiting for the final report..."
        const val CHECKING = "Checking what the server saw (up to 20 s)..."

        fun from(report: QualityReport, localBuild: BuildIdentity?, serverRelease: String?): SessionCheckCardModel = SessionCheckCardModel(
            final = report.final,
            overall = report.overall,
            checks = listOf(
                "Video geometry" to report.geometry,
                "Upright" to report.upright,
                "Athlete detected" to report.selectedRate,
                "Head and feet in frame" to report.fullBody,
            ),
            advice = report.advice,
            bodyVisible = percent(report.selectedFrames, report.processedFrames)
                ?.let { "Body visible in $it of analysed frames (${report.selectedFrames} of ${report.processedFrames})" }
                ?: "Body visible: no frames were analysed",
            // C4 counts head and ankle separately; the worker's fullBody check above judges them together.
            headAndFeet = if (report.selectedFrames > 0) {
                "Head visible in ${percent(report.selectedWithHead, report.selectedFrames)}, feet in " +
                    "${percent(report.selectedWithAnkle, report.selectedFrames)} of those frames"
            } else {
                "Head and feet: no athlete was selected"
            },
            judged = "Judged ${report.accepted + report.rejected} (${report.accepted} accepted - ${report.rejected} rejected) - " +
                "unavailable ${report.evidenceFailed}",
            versions = listOf(appVersion(report, localBuild), "server release ${serverRelease?.let(StatusOverlayModel::shortRelease) ?: "unknown"}")
                .joinToString(" - "),
            thumbnails = thumbnailIndices(report),
        )

        /** Whole percent of [part] in [whole], or null when there is nothing to divide. */
        fun percent(part: Long, whole: Long): String? =
            if (whole <= 0) null else "${(part * 100.0 / whole).roundToInt().coerceIn(0, 100)}%"

        /** At most the three listed thumbnails, first to latest. */
        fun thumbnailIndices(report: QualityReport): List<Int> = report.thumbnails.distinct().sorted().take(3)

        /** The app that made the capture (its C2 client info); this phone's app when the capture sent none. */
        private fun appVersion(report: QualityReport, localBuild: BuildIdentity?): String = when {
            report.clientAppVersion != null -> "App ${report.clientAppVersion} (${shortSha(report.clientGitSha)})"
            localBuild != null -> "App ${localBuild.versionName} (${shortSha(localBuild.gitSha)}, this phone)"
            else -> "App unknown"
        }

        private fun shortSha(sha: String?): String = sha?.takeIf { Regex("[0-9a-f]{7,40}").matches(it) }?.take(7) ?: "unknown"
    }
}

/** The card's progress. */
sealed interface SessionCheckState {
    data object Checking : SessionCheckState

    /** [complete] once `final: true` arrived or the 20 s window ended with this (interim) report. */
    data class Report(val report: QualityReport, val complete: Boolean) : SessionCheckState

    data object NoAnalysis : SessionCheckState

    /** A safe owner message; never exception text. */
    data class Failed(val message: String) : SessionCheckState
}

const val SESSION_CHECK_WINDOW_MS = 20_000L
const val SESSION_CHECK_INTERVAL_MS = 2_000L

/**
 * After Stop video: fetches the report at once, then every [SESSION_CHECK_INTERVAL_MS], for up to
 * [SESSION_CHECK_WINDOW_MS]. It stops at the first report with `final: true`; otherwise the latest
 * interim report is kept. With no report at all, the last answer decides: 404 "No analysis ran for
 * this capture", an unreadable body, or the step's owner message. [now] is a monotonic clock.
 */
suspend fun pollSessionCheck(
    fetch: suspend () -> FetchResult<String>,
    now: () -> Long,
    onState: (SessionCheckState) -> Unit,
): SessionCheckState {
    val start = now()
    var latest: QualityReport? = null
    var last: SessionCheckState = SessionCheckState.NoAnalysis
    onState(SessionCheckState.Checking)
    while (true) {
        try {
            when (val result = fetch()) {
                is FetchResult.Found -> {
                    val report = QualityReport.parse(result.value)
                    if (report == null) {
                        last = SessionCheckState.Failed(SessionCheckCardModel.UNREADABLE)
                    } else {
                        latest = report
                        if (report.final) return SessionCheckState.Report(report, complete = true).also(onState)
                        onState(SessionCheckState.Report(report, complete = false))
                    }
                }
                FetchResult.NotFound -> last = SessionCheckState.NoAnalysis
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            last = SessionCheckState.Failed(ownerMessage(error))
        }
        val elapsed = now() - start
        if (elapsed >= SESSION_CHECK_WINDOW_MS) break
        delay(minOf(SESSION_CHECK_INTERVAL_MS, SESSION_CHECK_WINDOW_MS - elapsed))
    }
    val end = latest?.let { SessionCheckState.Report(it, complete = true) } ?: last
    onState(end)
    return end
}

/** The step's owner message: the cause itself is recorded by ChallengeApi in the DiagnosticsLog. */
private fun ownerMessage(error: Exception): String = when (error) {
    is ChallengeApi.ApiException -> StepMessages.message(error.step ?: DiagnosticStep.QUALITY_REPORT, error.kind)
    else -> StepMessages.forError(DiagnosticStep.QUALITY_REPORT, error)
}

/** The longest side a thumbnail is decoded at; a report's JPEG may be up to 8192 px each way. */
const val THUMBNAIL_MAX_SIDE = 640

/** The power-of-two sample size that brings the longer side to at most [maxSide]. */
fun thumbnailSampleSize(width: Int, height: Int, maxSide: Int = THUMBNAIL_MAX_SIDE): Int {
    var sample = 1
    while (maxOf(width, height) / sample > maxSide) sample *= 2
    return sample
}

/** Decodes the JPEG bytes in memory, downsampled; nothing is written anywhere. Null when they are not an image. */
private fun decodeThumbnail(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = thumbnailSampleSize(bounds.outWidth, bounds.outHeight) }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

/**
 * The Session check card (OD-15 default: shown on the phone, with thumbnails): what the server
 * saw for [captureId]. It polls after Stop video and fetches the listed thumbnails, decoded in
 * memory only.
 */
@Composable
fun SessionCheckCard(
    captureId: String,
    landscape: Boolean,
    serverRelease: String?,
    fetchReport: suspend () -> FetchResult<String>,
    fetchThumbnail: suspend (Int) -> FetchResult<ByteArray>,
    onDismiss: () -> Unit,
) {
    var state by remember(captureId) { mutableStateOf<SessionCheckState>(SessionCheckState.Checking) }
    val images = remember(captureId) { mutableStateMapOf<Int, ImageBitmap>() }
    LaunchedEffect(captureId) { pollSessionCheck(fetchReport, SystemClock::elapsedRealtime) { state = it } }
    val shown = state as? SessionCheckState.Report
    val model = shown?.let { SessionCheckCardModel.from(it.report, BuildIdentity.current(), serverRelease) }
    val indices = model?.thumbnails.orEmpty()
    LaunchedEffect(captureId, indices) {
        for (index in indices) {
            if (index in images) continue
            val bytes = try {
                (fetchThumbnail(index) as? FetchResult.Found)?.value
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // ChallengeApi records the failure; the card simply shows no image for this index.
                null
            } ?: continue
            withContext(Dispatchers.Default) { decodeThumbnail(bytes) }?.let { images[index] = it }
        }
    }
    ScreenPanel(title = "Session check", landscape = landscape, onDismiss = onDismiss) {
        Text("Report for the video that stopped. Close this to return to the camera.", style = MaterialTheme.typography.bodySmall)
        when (val current = state) {
            SessionCheckState.Checking -> Text(SessionCheckCardModel.CHECKING, style = MaterialTheme.typography.bodyLarge)
            SessionCheckState.NoAnalysis -> Text(SessionCheckCardModel.NO_ANALYSIS, style = MaterialTheme.typography.titleMedium)
            is SessionCheckState.Failed -> Text(current.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            is SessionCheckState.Report -> model?.let { ReportBody(it, current.complete, images) }
        }
    }
}

@Composable
private fun ReportBody(model: SessionCheckCardModel, complete: Boolean, images: Map<Int, ImageBitmap>) {
    when {
        !complete -> Text(SessionCheckCardModel.WAITING, style = MaterialTheme.typography.bodyMedium)
        !model.final -> Text(SessionCheckCardModel.INTERIM, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    Text("Overall: ${model.overall}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = checkColor(model.overall))
    model.checks.forEach { (label, check) ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(check.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = checkColor(check))
        }
    }
    model.advice.forEach { Text("- $it", style = MaterialTheme.typography.bodyMedium) }
    PanelSection("What the server saw")
    Text(model.bodyVisible, style = MaterialTheme.typography.bodyMedium)
    Text(model.headAndFeet, style = MaterialTheme.typography.bodyMedium)
    Text(model.judged, style = MaterialTheme.typography.bodyMedium)
    Text(model.versions, style = MaterialTheme.typography.bodySmall)
    if (model.thumbnails.isNotEmpty()) {
        PanelSection("Analysed frames")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            model.thumbnails.forEach { index ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    images[index]?.let { Image(it, contentDescription = THUMBNAIL_LABELS[index], contentScale = ContentScale.Fit, modifier = Modifier.heightIn(max = 140.dp)) }
                        ?: Text("...", style = MaterialTheme.typography.bodySmall)
                    Text(THUMBNAIL_LABELS[index], style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private val THUMBNAIL_LABELS = listOf("First", "Middle", "Latest")

@Composable
private fun checkColor(check: Check): Color = when (check) {
    Check.PASS -> Color(0xFF66BB6A)
    Check.WARN -> Color(0xFFFFB300)
    Check.FAIL -> MaterialTheme.colorScheme.error
}
