package com.vaylith.cleanrepsmobile.api

import com.vaylith.cleanrepsmobile.model.AthleteCue
import com.vaylith.cleanrepsmobile.model.VerdictTone
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimal SSE reader for the v1 /stream endpoint. Unknown events are ignored,
 * never inferred. Parsing is kept independent of the socket so the exact
 * server contract can be exercised in ordinary JVM unit tests.
 */
class ChallengeEventClient(private val baseUrl: String) {
    private var job: Job? = null
    private var lastDeliveredAdjudicationId: String? = null
    private var lastSafeKickEventId: String? = null
    private val deliveredCueIds = mutableSetOf<String>()
    fun start(
        scope: CoroutineScope,
        sessionId: String,
        onVerdict: (MobileVerdictEvent) -> Unit,
        onCue: (AthleteCue) -> Unit,
        onCueSafe: (AthleteCue) -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val connection = (URL(baseUrl.trimEnd('/') + "/v1/challenge-sessions/$sessionId/stream").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"; readTimeout = 0; connectTimeout = 8_000
                        setRequestProperty("Accept", "text/event-stream")
                    }
                    connection.inputStream.bufferedReader().useLines { lines ->
                        var data = StringBuilder()
                        lines.forEach { line ->
                            if (line.startsWith("data:")) data.append(line.removePrefix("data:").trim())
                            if (line.isBlank() && data.isNotEmpty()) {
                                val payload = data.toString(); data = StringBuilder()
                                ChallengeEventParser.parseVerdict(payload)?.let { verdict ->
                                    // OverlayState is a projection and can be repeated. A tone is
                                    // tied to a unique adjudication, never merely a changed count.
                                    if (lastDeliveredAdjudicationId != verdict.adjudicationId) {
                                        lastDeliveredAdjudicationId = verdict.adjudicationId
                                        lastSafeKickEventId = verdict.kickEventId
                                        scope.launch(Dispatchers.Main) { onVerdict(verdict) }
                                    }
                                }
                                ChallengeEventParser.parseCue(payload)?.let { cue ->
                                    scope.launch(Dispatchers.Main) { onCue(cue) }
                                    // Never speak while a cue's declared post-kick window is
                                    // unresolved. `safeAfterKickEventId == null` is displayed
                                    // but not spoken by this baseline; the server may later
                                    // declare a true interrupt policy explicitly.
                                    if (cue.safeAfterKickEventId == lastSafeKickEventId && deliveredCueIds.add(cue.id)) {
                                        scope.launch(Dispatchers.Main) { onCueSafe(cue) }
                                    }
                                }
                            }
                        }
                    }
                } catch (error: Exception) {
                    withContext(Dispatchers.Main) { onError("Event stream reconnecting: ${error.message}") }
                    kotlinx.coroutines.delay(2_000)
                }
            }
        }
    }
    fun stop() {
        job?.cancel()
        job = null
        lastDeliveredAdjudicationId = null
        lastSafeKickEventId = null
        deliveredCueIds.clear()
    }

}

data class MobileVerdictEvent(
    val kickEventId: String,
    val kickSequence: Long,
    val adjudicationId: String,
    val adjudicationSequence: Long,
    val tone: VerdictTone,
    val reasonCode: String,
)

/** V1 launch-envelope parser. It intentionally has no authority to infer a verdict. */
object ChallengeEventParser {
    fun parseVerdict(json: String): MobileVerdictEvent? {
        // The server emits raw OverlayState. A settled decision is nested under
        // latestVerdict so it retains original kick attribution after delay or
        // manual supersession.
        val verdict = objectField(json, "latestVerdict") ?: return null
        val state = field(verdict, "state") ?: return null
        val tone = when (state) {
            "accepted" -> VerdictTone.ACCEPTED
            "rejected" -> VerdictTone.REJECTED
            "pending_review", "evidence_failed" -> VerdictTone.NEUTRAL
            else -> return null
        }
        return MobileVerdictEvent(
            kickEventId = field(verdict, "kickEventId") ?: return null,
            kickSequence = longField(verdict, "kickSequence") ?: return null,
            adjudicationId = field(verdict, "adjudicationId") ?: return null,
            adjudicationSequence = longField(verdict, "adjudicationSequence") ?: return null,
            tone = tone,
            reasonCode = field(verdict, "reasonCode") ?: state,
        )
    }
    fun parseCue(json: String): AthleteCue? {
        // OverlayState nests the durable CueController output beneath activeCue.
        val activeCue = objectField(json, "activeCue") ?: json
        val text = field(activeCue, "text") ?: return null
        return AthleteCue(field(activeCue, "id") ?: return null, text, intField(activeCue, "priority") ?: 0, field(activeCue, "kind") ?: "technical", field(activeCue, "safeAfterKickEventId"))
    }
    private fun field(json: String, name: String): String? = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
    private fun intField(json: String, name: String): Int? = Regex("\"$name\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
    private fun longField(json: String, name: String): Long? = Regex("\"$name\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()
    private fun objectField(json: String, name: String): String? {
        val start = Regex("\"$name\"\\s*:\\s*\\{").find(json)?.range?.last?.plus(1) ?: return null
        var depth = 1
        for (index in start until json.length) {
            when (json[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return json.substring(start, index)
            }
        }
        return null
    }
}
