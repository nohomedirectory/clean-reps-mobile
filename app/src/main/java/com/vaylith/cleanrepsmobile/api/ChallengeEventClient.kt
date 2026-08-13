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

/** Minimal SSE reader for the v1 /stream endpoint. Unknown events are ignored, never inferred. */
class ChallengeEventClient(private val baseUrl: String) {
    private var job: Job? = null
    fun start(scope: CoroutineScope, sessionId: String, onVerdict: (VerdictTone, String) -> Unit, onCue: (AthleteCue) -> Unit, onError: (String) -> Unit) {
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
                                parseVerdict(payload)?.let { (tone, text) -> scope.launch(Dispatchers.Main) { onVerdict(tone, text) } }
                                parseCue(payload)?.let { cue -> scope.launch(Dispatchers.Main) { onCue(cue) } }
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
    fun stop() { job?.cancel(); job = null }

    private fun parseVerdict(json: String): Pair<VerdictTone, String>? {
        val state = field(json, "state") ?: return null
        val tone = when (state) { "accepted" -> VerdictTone.ACCEPTED; "rejected" -> VerdictTone.REJECTED; "pending_review", "evidence_failed" -> VerdictTone.NEUTRAL; else -> return null }
        return tone to (field(json, "reasonCode") ?: state)
    }
    private fun parseCue(json: String): AthleteCue? {
        val text = field(json, "text") ?: return null
        return AthleteCue(field(json, "id") ?: return null, text, field(json, "priority") ?: "normal", field(json, "kind") ?: "technical", field(json, "safeAfterKickEventId"))
    }
    private fun field(json: String, name: String): String? = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
}
