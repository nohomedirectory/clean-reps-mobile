package com.vaylith.cleanrepsmobile.api

import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticStep
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticsLog
import com.vaylith.cleanrepsmobile.diagnostics.FailureKind
import com.vaylith.cleanrepsmobile.diagnostics.StepMessages
import com.vaylith.cleanrepsmobile.model.AthleteCue
import com.vaylith.cleanrepsmobile.model.LiveAnalysisState
import com.vaylith.cleanrepsmobile.model.LiveAnalysisStatus
import com.vaylith.cleanrepsmobile.model.LiveAthleteRegion
import com.vaylith.cleanrepsmobile.model.LiveBlockedReason
import com.vaylith.cleanrepsmobile.model.LiveSourceGeometry
import com.vaylith.cleanrepsmobile.model.LiveSourceOrientation
import com.vaylith.cleanrepsmobile.model.VerdictClass
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** The session's server events, so session logic can run against a fake stream. */
interface ChallengeEvents {
    /**
     * Follows [sessionId]'s stream until [stop]; every callback runs on the main
     * thread. [onVerdict] gets each new adjudication once. [onLiveAnalysis] gets
     * every frame's `liveAnalysis` projection, null when the frame has none.
     */
    fun start(
        scope: CoroutineScope,
        sessionId: String,
        onVerdict: (MobileVerdictEvent) -> Unit,
        onCue: (AthleteCue) -> Unit,
        onCueSafe: (AthleteCue) -> Unit,
        onError: (String) -> Unit,
        onChallengeTotal: (Long) -> Unit,
        onLiveAnalysis: (LiveAnalysisStatus?) -> Unit,
        onSessionCounts: (SessionCounts) -> Unit,
    )

    fun stop()
}

/**
 * Minimal SSE reader for the v1 /stream endpoint. Unknown events are ignored,
 * never inferred. Parsing is kept independent of the socket so the exact
 * server contract can be exercised in ordinary JVM unit tests.
 */
class ChallengeEventClient(
    private val baseUrl: String,
    private val diagnostics: DiagnosticsLog,
) : ChallengeEvents {
    private var job: Job? = null
    @Volatile private var activeConnection: HttpURLConnection? = null
    private var lastDeliveredAdjudicationId: String? = null
    private var lastSafeKickEventId: String? = null
    private val deliveredCueIds = mutableSetOf<String>()

    override fun start(
        scope: CoroutineScope,
        sessionId: String,
        onVerdict: (MobileVerdictEvent) -> Unit,
        onCue: (AthleteCue) -> Unit,
        onCueSafe: (AthleteCue) -> Unit,
        onError: (String) -> Unit,
        onChallengeTotal: (Long) -> Unit,
        onLiveAnalysis: (LiveAnalysisStatus?) -> Unit,
        onSessionCounts: (SessionCounts) -> Unit,
    ) {
        stop()
        fun deliver(payload: String) {
            // A data frame that is not one well-formed JSON object is not OverlayState.
            val frame = JsonFields.parse(payload) ?: return
            ChallengeEventParser.challengeTotal(frame)?.let { total -> scope.launch(Dispatchers.Main) { onChallengeTotal(total) } }
            ChallengeEventParser.sessionCounts(frame)?.let { counts -> scope.launch(Dispatchers.Main) { onSessionCounts(counts) } }
            ChallengeEventParser.liveAnalysis(frame).let { status -> scope.launch(Dispatchers.Main) { onLiveAnalysis(status) } }
            ChallengeEventParser.verdict(frame)?.let { verdict ->
                // OverlayState is a projection and can be repeated. A tone is
                // tied to a unique adjudication, never merely a changed count.
                if (lastDeliveredAdjudicationId != verdict.adjudicationId) {
                    lastDeliveredAdjudicationId = verdict.adjudicationId
                    lastSafeKickEventId = verdict.kickEventId
                    scope.launch(Dispatchers.Main) { onVerdict(verdict) }
                }
            }
            ChallengeEventParser.cue(frame)?.let { cue ->
                scope.launch(Dispatchers.Main) { onCue(cue) }
                // Never speak while a cue's declared post-kick window is
                // unresolved. `safeAfterKickEventId == null` is displayed
                // but not spoken by this baseline; the server may later
                // declare a true interrupt policy explicitly.
                if (cueHasResolvedSafeWindow(cue, lastSafeKickEventId) && deliveredCueIds.add(cue.id)) {
                    scope.launch(Dispatchers.Main) { onCueSafe(cue) }
                }
            }
        }
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                var iterationConnection: HttpURLConnection? = null
                try {
                    val connection = (URL(baseUrl.trimEnd('/') + "/v1/challenge-sessions/$sessionId/stream").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"; readTimeout = 0; connectTimeout = 8_000
                        instanceFollowRedirects = false
                        setRequestProperty("Accept", "text/event-stream")
                    }
                    iterationConnection = connection
                    activeConnection = connection
                    val code = connection.responseCode
                    if (code != HttpURLConnection.HTTP_OK) throw EventStreamHttpException(code)
                    connection.inputStream.bufferedReader().useLines { lines ->
                        var data = StringBuilder()
                        lines.forEach { line ->
                            if (line.startsWith("data:")) data.append(line.removePrefix("data:").trim())
                            if (line.isBlank() && data.isNotEmpty()) {
                                val payload = data.toString(); data = StringBuilder()
                                deliver(payload)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // stop() disconnects the socket to end the read; that is not a stream failure.
                    ensureActive()
                    // The cause goes only to the redacted DiagnosticsLog; the status line gets the step message.
                    diagnostics.fail(DiagnosticStep.EVENT_STREAM, "reconnecting in 2 s", error)
                    val message = eventStreamErrorMessage(error)
                    withContext(Dispatchers.Main) { onError(message) }
                    kotlinx.coroutines.delay(2_000)
                } finally {
                    iterationConnection?.disconnect()
                    if (activeConnection === iterationConnection) activeConnection = null
                }
            }
        }
    }
    override fun stop() {
        job?.cancel()
        activeConnection?.disconnect()
        activeConnection = null
        job = null
        lastDeliveredAdjudicationId = null
        lastSafeKickEventId = null
        deliveredCueIds.clear()
    }

}

/** A non-200 answer to the stream request. Its message is fixed text, never a URL. */
internal class EventStreamHttpException(val code: Int) : IOException("HTTP $code")

/** The owner-facing text for a stream failure. It never contains the exception's own text. */
internal fun eventStreamErrorMessage(error: Throwable): String = when (error) {
    is EventStreamHttpException -> StepMessages.message(DiagnosticStep.EVENT_STREAM, FailureKind.Http(error.code))
    else -> StepMessages.forError(DiagnosticStep.EVENT_STREAM, error)
}

/** One adjudication. Only ACCEPTED and REJECTED make a verdict sound. */
data class MobileVerdictEvent(
    val kickEventId: String,
    val kickSequence: Long,
    val adjudicationId: String,
    val adjudicationSequence: Long,
    val reasonCode: String,
    val verdictClass: VerdictClass,
)

/** This session's accepted, rejected and evidence-failed kick counts, from the top level of OverlayState. */
data class SessionCounts(val accepted: Int, val rejected: Int, val evidenceFailed: Int)

internal fun cueHasResolvedSafeWindow(cue: AthleteCue, completedKickEventId: String?): Boolean =
    cue.safeAfterKickEventId != null && cue.safeAfterKickEventId == completedKickEventId

/**
 * V1 launch-envelope parser. It intentionally has no authority to infer a verdict.
 *
 * Each data frame is OverlayState with one more top-level key, `liveAnalysis`.
 * Every value is read from its own object only: a key of the same name inside
 * another object (for example `latestVerdict.state` and `liveAnalysis.state`)
 * is never seen.
 */
object ChallengeEventParser {
    fun parseChallengeTotal(json: String): Long? = JsonFields.parse(json)?.let { challengeTotal(it) }
    fun parseVerdict(json: String): MobileVerdictEvent? = JsonFields.parse(json)?.let { verdict(it) }
    fun parseCue(json: String): AthleteCue? = JsonFields.parse(json)?.let { cue(it) }
    fun parseLiveAnalysis(json: String): LiveAnalysisStatus? = JsonFields.parse(json)?.let { liveAnalysis(it) }
    fun parseSessionCounts(json: String): SessionCounts? = JsonFields.parse(json)?.let { sessionCounts(it) }

    internal fun challengeTotal(frame: JsonFields): Long? = frame.long("challengeOfficialAcceptedCount")?.takeIf { it >= 0 }

    internal fun verdict(frame: JsonFields): MobileVerdictEvent? {
        // A settled decision is nested under latestVerdict so it retains original
        // kick attribution after delay or manual supersession.
        val verdict = frame.obj("latestVerdict") ?: return null
        val state = verdict.text("state") ?: return null
        val verdictClass = when (state) {
            "accepted" -> VerdictClass.ACCEPTED
            "rejected" -> VerdictClass.REJECTED
            "pending_review" -> VerdictClass.PENDING
            "evidence_failed" -> VerdictClass.UNJUDGEABLE
            else -> return null
        }
        return MobileVerdictEvent(
            kickEventId = verdict.text("kickEventId") ?: return null,
            kickSequence = verdict.long("kickSequence") ?: return null,
            adjudicationId = verdict.text("adjudicationId") ?: return null,
            adjudicationSequence = verdict.long("adjudicationSequence") ?: return null,
            reasonCode = verdict.text("reasonCode") ?: state,
            verdictClass = verdictClass,
        )
    }

    internal fun cue(frame: JsonFields): AthleteCue? {
        // OverlayState always carries activeCue, null when there is no cue. Nothing
        // else in the frame is ever read as a cue.
        val activeCue = frame.obj("activeCue") ?: return null
        val text = activeCue.text("text") ?: return null
        return AthleteCue(activeCue.text("id") ?: return null, text, activeCue.int("priority") ?: 0, activeCue.text("kind") ?: "technical", activeCue.text("safeAfterKickEventId"))
    }

    /** The C3 projection, or null when the frame has no `liveAnalysis` object. */
    internal fun liveAnalysis(frame: JsonFields): LiveAnalysisStatus? {
        val live = frame.obj("liveAnalysis") ?: return null
        // The server projects staleness as state `stale` plus `stale: true`; the flag alone is enough.
        val stale = live.boolean("stale") == true
        val state = if (stale) LiveAnalysisState.STALE else LiveAnalysisState.fromWire(live.string("state") ?: return null)
        return LiveAnalysisStatus(
            state = state,
            reasonCode = live.string("reasonCode")?.let { LiveBlockedReason.fromWire(it) },
            detail = live.string("detail").orEmpty(),
            practicePaused = live.boolean("practicePaused") == true,
            sourceGeometry = live.obj("sourceGeometry")?.let { sourceGeometry(it) },
            athleteRegion = live.obj("athleteRegion")?.let { athleteRegion(it) },
            stale = stale,
        )
    }

    internal fun sessionCounts(frame: JsonFields): SessionCounts? {
        val accepted = frame.int("accepted")?.takeIf { it >= 0 } ?: return null
        val rejected = frame.int("rejected")?.takeIf { it >= 0 } ?: return null
        val evidenceFailed = frame.int("evidenceFailed")?.takeIf { it >= 0 } ?: return null
        return SessionCounts(accepted, rejected, evidenceFailed)
    }

    private fun sourceGeometry(geometry: JsonFields): LiveSourceGeometry? {
        val width = geometry.int("width")?.takeIf { it > 0 } ?: return null
        val height = geometry.int("height")?.takeIf { it > 0 } ?: return null
        return LiveSourceGeometry(width, height, LiveSourceOrientation.fromWire(geometry.string("orientation")))
    }

    private fun athleteRegion(region: JsonFields): LiveAthleteRegion? {
        val left = region.double("left") ?: return null
        val top = region.double("top") ?: return null
        val right = region.double("right") ?: return null
        val bottom = region.double("bottom") ?: return null
        val inFrame = listOf(left, top, right, bottom).all { it in 0.0..1.0 }
        return if (inFrame && left < right && top < bottom) LiveAthleteRegion(left, top, right, bottom) else null
    }
}

/**
 * The members of ONE JSON object, each kept as its raw JSON text. A nested
 * object or array stays whole, so its keys are never visible at this level;
 * strings are read with their escapes, so a brace or quote inside a string
 * cannot change the nesting. When a key repeats, the last value wins, as in
 * JavaScript's `JSON.parse`.
 */
internal class JsonFields private constructor(private val members: Map<String, String>) {
    /** A string member, or null when absent, JSON null or not a string. */
    fun string(name: String): String? = members[name]?.takeIf { it.startsWith('"') }?.let { JsonScanner(it).string() }

    /** A non-empty string member. */
    fun text(name: String): String? = string(name)?.takeIf(String::isNotEmpty)

    fun int(name: String): Int? = members[name]?.takeIf { INTEGER.matches(it) }?.toIntOrNull()

    fun long(name: String): Long? = members[name]?.takeIf { INTEGER.matches(it) }?.toLongOrNull()

    fun double(name: String): Double? = members[name]?.takeIf { NUMBER.matches(it) }?.toDoubleOrNull()

    fun boolean(name: String): Boolean? = when (members[name]) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /** An object member, or null when absent, JSON null or not an object. */
    fun obj(name: String): JsonFields? = members[name]?.takeIf { it.startsWith('{') }?.let { parse(it) }

    /** True when the member is present with the JSON value null. */
    fun isNull(name: String): Boolean = members[name] == "null"

    /** The raw JSON text of each element of an array member, or null when absent, JSON null, not an array or malformed. */
    fun array(name: String): List<String>? = members[name]?.takeIf { it.startsWith('[') }?.let { JsonScanner(it).elements() }

    /** An array of strings, or null when any element is not a string. */
    fun strings(name: String): List<String>? = array(name)?.map { element ->
        JsonScanner(element).let { scanner -> scanner.string()?.takeIf { scanner.atEnd() } } ?: return null
    }

    /** An array of objects, or null when any element is not an object. */
    fun objects(name: String): List<JsonFields>? = array(name)?.map { element -> parse(element) ?: return null }

    companion object {
        private val INTEGER = Regex("-?(0|[1-9][0-9]*)")
        internal val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

        /** Null unless [json] is exactly one well-formed JSON object. */
        fun parse(json: String): JsonFields? {
            val scanner = JsonScanner(json)
            val members = scanner.members() ?: return null
            return if (scanner.atEnd()) JsonFields(members) else null
        }
    }
}

/** A strict single-pass JSON reader for [JsonFields]. Every read returns null or false when malformed. */
private class JsonScanner(private val text: String) {
    private var index = 0
    private var depth = 0

    fun atEnd(): Boolean {
        skipWhitespace()
        return index == text.length
    }

    fun members(): Map<String, String>? {
        skipWhitespace()
        if (peek() != '{' || ++depth > MAX_DEPTH) return null
        index++
        val result = LinkedHashMap<String, String>()
        skipWhitespace()
        if (peek() == '}') return result.also { index++; depth-- }
        while (true) {
            skipWhitespace()
            val key = string() ?: return null
            skipWhitespace()
            if (peek() != ':') return null
            index++
            skipWhitespace()
            val start = index
            if (!value()) return null
            result[key] = text.substring(start, index)
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> return result.also { index++; depth-- }
                else -> return null
            }
        }
    }

    fun string(): String? {
        if (peek() != '"') return null
        index++
        val out = StringBuilder()
        while (index < text.length) {
            val char = text[index++]
            when {
                char == '"' -> return out.toString()
                char == '\\' -> when (text.getOrNull(index++)) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        val hex = text.substring(index, minOf(index + 4, text.length))
                        if (hex.length != 4 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                        out.append(hex.toInt(16).toChar())
                        index += 4
                    }
                    else -> return null
                }
                char < ' ' -> return null
                else -> out.append(char)
            }
        }
        return null
    }

    /** The raw text of each element of exactly one JSON array, or null when malformed. */
    fun elements(): List<String>? {
        skipWhitespace()
        if (peek() != '[' || ++depth > MAX_DEPTH) return null
        index++
        val result = mutableListOf<String>()
        skipWhitespace()
        if (peek() == ']') {
            index++
            depth--
            return result.takeIf { atEnd() }
        }
        while (true) {
            skipWhitespace()
            val start = index
            if (!value()) return null
            result += text.substring(start, index)
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                ']' -> {
                    index++
                    depth--
                    return result.takeIf { atEnd() }
                }
                else -> return null
            }
        }
    }

    private fun value(): Boolean = when (peek()) {
        '"' -> string() != null
        '{' -> members() != null
        '[' -> array()
        null -> false
        else -> literal()
    }

    private fun array(): Boolean {
        if (++depth > MAX_DEPTH) return false
        index++
        skipWhitespace()
        if (peek() == ']') return true.also { index++; depth-- }
        while (true) {
            skipWhitespace()
            if (!value()) return false
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                ']' -> return true.also { index++; depth-- }
                else -> return false
            }
        }
    }

    private fun literal(): Boolean {
        val start = index
        while (index < text.length && (text[index].isLetterOrDigit() || text[index] in "+-.")) index++
        val literal = text.substring(start, index)
        return literal == "true" || literal == "false" || literal == "null" || JsonFields.NUMBER.matches(literal)
    }

    private fun peek(): Char? = text.getOrNull(index)

    private fun skipWhitespace() {
        while (index < text.length && text[index] in " \t\r\n") index++
    }

    private companion object {
        const val MAX_DEPTH = 32
    }
}
