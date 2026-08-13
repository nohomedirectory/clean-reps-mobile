package com.vaylith.cleanrepsmobile.api

import com.vaylith.cleanrepsmobile.model.BlockSelection
import com.vaylith.cleanrepsmobile.model.SourceEpoch
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small v1 HTTPS/JSON client. It does not own verdict semantics or official count. */
class ChallengeApi(private val baseUrl: String) {
    class ApiException(message: String) : Exception(message)

    val configured get() = baseUrl.isNotBlank()

    suspend fun createSession(challengeId: String): String = post("/v1/challenge-sessions", "{\"challengeId\":${json(challengeId)}}").requireId()

    suspend fun createBlock(sessionId: String, selection: BlockSelection): String = post(
        "/v1/challenge-sessions/$sessionId/blocks",
        """{"technique":"${selection.technique}","side":"${selection.side.name.lowercase()}","targetContext":"${selection.targetContext}","intent":"${selection.intent}","cameraProfile":"${selection.cameraProfile}","reacquisition":true}""",
    ).requireId()

    suspend fun attachCapture(sessionId: String, sourceId: String, epoch: SourceEpoch): String = post(
        "/v1/challenge-sessions/$sessionId/captures",
        """{"sourceId":${json(sourceId)},"sourceEpoch":${epoch.value}}""",
    ).requireId()

    suspend fun reportSourceHealth(captureId: String, status: String, detail: String) {
        post("/v1/captures/$captureId/health", """{"status":${json(status)},"detail":${json(detail)}}""")
    }

    private suspend fun post(path: String, body: String): ApiReply = withContext(Dispatchers.IO) {
        if (!configured) throw ApiException("CHALLENGE_API_BASE_URL is not configured")
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 8_000; readTimeout = 12_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Idempotency-Key", UUID.randomUUID().toString())
            doOutput = true
        }
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        if (code !in 200..299) throw ApiException("$code ${response.take(300)}")
        ApiReply(response)
    }

    private fun json(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

private data class ApiReply(val raw: String) {
    fun requireId(): String = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
        ?: throw ChallengeApi.ApiException("v1 response did not include id")
}
