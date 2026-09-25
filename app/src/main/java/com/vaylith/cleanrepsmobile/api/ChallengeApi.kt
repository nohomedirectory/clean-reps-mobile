package com.vaylith.cleanrepsmobile.api

import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticOutcome
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticStep
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticsLog
import com.vaylith.cleanrepsmobile.diagnostics.FailureKind
import com.vaylith.cleanrepsmobile.diagnostics.Redaction
import com.vaylith.cleanrepsmobile.model.BlockSelection
import com.vaylith.cleanrepsmobile.model.ConnectionSettings
import com.vaylith.cleanrepsmobile.model.ManualEvidenceWindow
import com.vaylith.cleanrepsmobile.model.SourceEpoch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the phone asks of the Clean Reps server, so session logic can run against a fake. */
interface ChallengeBackend {
    suspend fun createSession(challengeId: String): String
    suspend fun createBlock(sessionId: String, selection: BlockSelection): String
    suspend fun markReacquired(sessionId: String, blockId: String)
    suspend fun pausePractice(sessionId: String, blockId: String)
    suspend fun resumePractice(sessionId: String, blockId: String)
    suspend fun attachCapture(sessionId: String, sourceId: String, epoch: SourceEpoch): String
    suspend fun reportSourceHealth(captureId: String, status: String, detail: String)
    suspend fun logManualAttempt(
        sessionId: String,
        blockId: String,
        captureId: String,
        sourceId: String,
        epoch: SourceEpoch,
        occurredAt: String,
        window: ManualEvidenceWindow,
    ): String

    /** C2. [body] is sent byte for byte; a retry must re-send the same bytes. */
    suspend fun clientInfo(captureId: String, body: ByteArray): ClientInfoResult

    /** C5. Never throws: a failing or unreachable server is `reachable = false`. */
    suspend fun health(): ServerHealth

    /** C4. The raw report JSON, or [FetchResult.NotFound] when no analysis ran. */
    suspend fun qualityReport(captureId: String): FetchResult<String>

    /** C4. JPEG thumbnail [index] (0..2), or [FetchResult.NotFound]. */
    suspend fun thumbnail(captureId: String, index: Int): FetchResult<ByteArray>
}

enum class ClientInfoResult {
    /** The first body for this capture; the server stored it. */
    STORED,

    /** The server already held exactly these bytes. */
    UNCHANGED,

    /** HTTP 409: the server holds a different first body and kept it. */
    CONFLICT,
}

/** [release] is the server's `CLEAN_REPS_RELEASE_SHA` (or `unknown`); null when not reported. */
data class ServerHealth(val reachable: Boolean, val release: String?)

sealed interface FetchResult<out T> {
    data class Found<T>(val value: T) : FetchResult<T>
    data object NotFound : FetchResult<Nothing>
}

/**
 * Small v1 HTTPS/JSON client. It does not own verdict semantics or official count.
 * Every call except [logManualAttempt] records its [DiagnosticStep] in [diagnostics].
 */
class ChallengeApi(
    private val baseUrl: String,
    private val diagnostics: DiagnosticsLog? = null,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 12_000,
) : ChallengeBackend {
    /**
     * [step] is null only for [logManualAttempt], which has no step in the
     * DiagnosticsLog vocabulary. The message carries at most a redacted excerpt of
     * the server's error, never the raw body.
     */
    class ApiException(
        message: String,
        val step: DiagnosticStep?,
        val kind: FailureKind,
        cause: Throwable? = null,
    ) : Exception(message, cause) {
        val statusCode: Int? get() = (kind as? FailureKind.Http)?.code
    }

    val configured get() = baseUrl.isNotBlank()

    private val ownRedaction = Redaction.forSettings(ConnectionSettings(apiBaseUrl = baseUrl))

    override suspend fun createSession(challengeId: String): String = logged(DiagnosticStep.CREATE_SESSION) { step ->
        post(step, "/v1/challenge-sessions", "{\"challengeId\":${json(challengeId)}}").requireId(step)
    }

    override suspend fun createBlock(sessionId: String, selection: BlockSelection): String =
        logged(DiagnosticStep.CREATE_BLOCK) { step ->
            post(step, "/v1/challenge-sessions/$sessionId/blocks", blockRequestBody(selection)).requireId(step)
        }

    override suspend fun markReacquired(sessionId: String, blockId: String) = logged(DiagnosticStep.MARK_REACQUIRED) { step ->
        post(step, "/v1/challenge-sessions/$sessionId/blocks/$blockId/reacquired", "{}")
        Unit
    }

    override suspend fun pausePractice(sessionId: String, blockId: String) = logged(DiagnosticStep.PAUSE) { step ->
        post(step, "/v1/challenge-sessions/$sessionId/blocks/$blockId/pause", "{}")
        Unit
    }

    override suspend fun resumePractice(sessionId: String, blockId: String) = logged(DiagnosticStep.RESUME) { step ->
        post(step, "/v1/challenge-sessions/$sessionId/blocks/$blockId/resume", "{}")
        Unit
    }

    override suspend fun attachCapture(sessionId: String, sourceId: String, epoch: SourceEpoch): String =
        logged(DiagnosticStep.ATTACH_CAPTURE) { step ->
            post(
                step,
                "/v1/challenge-sessions/$sessionId/captures",
                """{"sourceId":${json(sourceId)},"sourceEpoch":${epoch.value},"purpose":"rehearsal"}""",
            ).requireId(step)
        }

    override suspend fun reportSourceHealth(captureId: String, status: String, detail: String) = logged(DiagnosticStep.HEALTH) { step ->
        post(step, "/v1/captures/$captureId/health", """{"status":${json(status)},"detail":${json(detail)}}""")
        Unit
    }

    /**
     * Truthful alignment fallback: creates a durable event against the one raw
     * source interval but supplies no invented pose evidence. The server must
     * classify it evidence_failed until the Clean Reps review surface settles it.
     */
    override suspend fun logManualAttempt(
        sessionId: String,
        blockId: String,
        captureId: String,
        sourceId: String,
        epoch: SourceEpoch,
        occurredAt: String,
        window: ManualEvidenceWindow,
    ): String {
        val sourceReference = "mediamtx://$sourceId?sourceEpoch=${epoch.value}"
        val body = """{"blockId":${json(blockId)},"captureSessionId":${json(captureId)},"sourceEpoch":${epoch.value},"occurredAt":${json(occurredAt)},"evidence":{"startMs":${window.startMs},"endMs":${window.endMs},"poseFrames":0,"athleteVisible":false,"supportFootVisible":false,"sourceUrl":${json(sourceReference)}}}"""
        val idempotencyKey = "manual-attempt:$captureId:${epoch.value}:${window.endMs}"
        return post(null, "/v1/challenge-sessions/$sessionId/analysis/kick-events", body, idempotencyKey).requireEventId()
    }

    override suspend fun clientInfo(captureId: String, body: ByteArray): ClientInfoResult = logged<ClientInfoResult>(
        DiagnosticStep.CLIENT_INFO,
        describe = {
            if (it == ClientInfoResult.CONFLICT) DiagnosticOutcome.FAIL to "HTTP 409: the server kept a different first body"
            else DiagnosticOutcome.OK to it.name.lowercase()
        },
    ) { step ->
        val reply = exchange(step, "POST", "/v1/captures/${captureSegment(step, captureId)}/client-info", body)
        when {
            reply.code == 409 -> ClientInfoResult.CONFLICT
            reply.code !in 200..299 -> throw httpFailure(step, reply)
            else -> when (CLIENT_INFO_OUTCOME.find(reply.text)?.groupValues?.get(1)) {
                "stored" -> ClientInfoResult.STORED
                "unchanged" -> ClientInfoResult.UNCHANGED
                else -> throw ApiException("${label(step)} reply did not say stored or unchanged", step, FailureKind.Other)
            }
        }
    }

    override suspend fun health(): ServerHealth {
        val step = DiagnosticStep.HEALTH
        return try {
            val reply = exchange(step, "GET", "/health").requireSuccess(step)
            ServerHealth(reachable = true, release = RELEASE.find(reply.text)?.groupValues?.get(1))
                .also { diagnostics?.ok(step, "server release ${it.release ?: "not reported"}") }
        } catch (error: ApiException) {
            diagnostics?.fail(step, error.message.orEmpty(), error.cause)
            ServerHealth(reachable = false, release = null)
        }
    }

    override suspend fun qualityReport(captureId: String): FetchResult<String> =
        logged<FetchResult<String>>(DiagnosticStep.QUALITY_REPORT, describe = ::describeFetch) { step ->
            val reply = exchange(step, "GET", "/v1/captures/${captureSegment(step, captureId)}/quality-report")
            when (reply.code) {
                404 -> FetchResult.NotFound
                in 200..299 -> FetchResult.Found(reply.text)
                else -> throw httpFailure(step, reply)
            }
        }

    override suspend fun thumbnail(captureId: String, index: Int): FetchResult<ByteArray> {
        require(index in 0..2) { "thumbnail index must be 0, 1 or 2" }
        return logged<FetchResult<ByteArray>>(DiagnosticStep.QUALITY_REPORT, describe = ::describeFetch) { step ->
            val path = "/v1/captures/${captureSegment(step, captureId)}/quality-report/thumbnails/$index.jpg"
            val reply = exchange(step, "GET", path, accept = "image/jpeg")
            when {
                reply.code == 404 -> FetchResult.NotFound
                reply.code !in 200..299 -> throw httpFailure(step, reply)
                reply.contentType?.substringBefore(';')?.trim()?.equals("image/jpeg", ignoreCase = true) != true ->
                    throw ApiException("${label(step)} thumbnail was not image/jpeg", step, FailureKind.Other)
                else -> FetchResult.Found(reply.body)
            }
        }
    }

    private class Reply(val code: Int, val contentType: String?, val body: ByteArray) {
        val text: String get() = body.toString(Charsets.UTF_8)
    }

    /** Records [step] as OK (or as [describe] says) on success and as FAIL on an [ApiException]. */
    private suspend fun <T> logged(
        step: DiagnosticStep,
        describe: (T) -> Pair<DiagnosticOutcome, String> = { DiagnosticOutcome.OK to "" },
        block: suspend (DiagnosticStep) -> T,
    ): T {
        val result = try {
            block(step)
        } catch (error: ApiException) {
            diagnostics?.fail(step, error.message.orEmpty(), error.cause)
            throw error
        }
        diagnostics?.let { log -> describe(result).let { (outcome, message) -> log.record(step, outcome, message, null) } }
        return result
    }

    private fun describeFetch(result: FetchResult<*>): Pair<DiagnosticOutcome, String> = when (result) {
        is FetchResult.Found -> DiagnosticOutcome.OK to ""
        FetchResult.NotFound -> DiagnosticOutcome.INFO to "not found (404)"
    }

    private suspend fun post(
        step: DiagnosticStep?,
        path: String,
        body: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): Reply = exchange(step, "POST", path, body.toByteArray(Charsets.UTF_8), idempotencyKey).requireSuccess(step)

    private suspend fun exchange(
        step: DiagnosticStep?,
        method: String,
        path: String,
        body: ByteArray? = null,
        idempotencyKey: String? = null,
        accept: String = "application/json",
    ): Reply = withContext(Dispatchers.IO) {
        if (!configured) throw ApiException("CHALLENGE_API_BASE_URL is not configured", step, FailureKind.Other)
        try {
            val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method; connectTimeout = connectTimeoutMs; readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                setRequestProperty("Accept", accept)
                idempotencyKey?.let { setRequestProperty("Idempotency-Key", it) }
                if (body != null) {
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
            }
            if (body != null) connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            Reply(code, connection.contentType, stream?.use { readCapped(step, it) } ?: ByteArray(0))
        } catch (error: IOException) {
            val kind = FailureKind.of(error)
            val reason = when (kind) {
                FailureKind.Timeout -> "timeout"
                FailureKind.Unreachable -> "unreachable"
                else -> "network error"
            }
            throw ApiException("${label(step)} failed ($reason)", step, kind, error)
        }
    }

    private fun readCapped(step: DiagnosticStep?, input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return output.toByteArray()
            if (output.size() + read > MAX_RESPONSE_BYTES) {
                throw ApiException("${label(step)} reply exceeded $MAX_RESPONSE_BYTES bytes", step, FailureKind.Other)
            }
            output.write(buffer, 0, read)
        }
    }

    private fun Reply.requireSuccess(step: DiagnosticStep?): Reply = if (code in 200..299) this else throw httpFailure(step, this)

    private fun Reply.requireId(step: DiagnosticStep?): String = ID.find(text)?.groupValues?.get(1)
        ?: throw ApiException("${label(step)} response did not include id", step, FailureKind.Other)

    private fun Reply.requireEventId(): String = EVENT_ID.find(text)?.groupValues?.get(1)
        ?: throw ApiException("${label(null)} response did not include event id", null, FailureKind.Other)

    /** The server's error text (or the body), redacted as a whole before the excerpt is cut. */
    private fun httpFailure(step: DiagnosticStep?, reply: Reply): ApiException {
        val serverText = SERVER_ERROR.find(reply.text)?.groupValues?.get(1) ?: reply.text
        val redacted = ownRedaction.redact((diagnostics?.redaction ?: Redaction.PATTERNS_ONLY).redact(serverText))
        val excerpt = CONTROL_CHARACTERS.replace(redacted, " ").trim().take(MAX_EXCERPT_LENGTH)
        val message = "${label(step)} HTTP ${reply.code}" + if (excerpt.isEmpty()) "" else ": $excerpt"
        return ApiException(message, step, FailureKind.Http(reply.code))
    }

    private fun captureSegment(step: DiagnosticStep, captureId: String): String {
        if (!CAPTURE_ID.matches(captureId)) throw ApiException("${label(step)} refused an invalid capture id", step, FailureKind.Other)
        return captureId
    }

    private fun label(step: DiagnosticStep?) = step?.wireName ?: "logManualAttempt"

    private fun json(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        const val MAX_EXCERPT_LENGTH = 200
        val ID = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
        val EVENT_ID = Regex("\"event\"\\s*:\\s*\\{[^}]*\"id\"\\s*:\\s*\"([^\"]+)\"")
        val CLIENT_INFO_OUTCOME = Regex("\"clientInfo\"\\s*:\\s*\"(stored|unchanged)\"")
        val RELEASE = Regex("\"release\"\\s*:\\s*\"([A-Za-z0-9._-]{1,64})\"")
        val SERVER_ERROR = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val CAPTURE_ID = Regex("[A-Za-z0-9_-]{1,128}")
        val CONTROL_CHARACTERS = Regex("""\p{Cntrl}+""")
    }
}

/** The optional height describes the chosen drill; it is not a quality threshold. */
internal fun blockRequestBody(selection: BlockSelection): String {
    val height = selection.targetHeight?.let { ",\"targetHeight\":\"${it.wireValue}\"" }.orEmpty()
    return """{"technique":"${selection.technique.wireValue}","side":"${selection.side.name.lowercase()}","targetContext":"${selection.targetContext.wireValue}"$height,"intent":"${selection.intent}","cameraProfile":"${selection.cameraProfile}","reacquisition":true}"""
}
