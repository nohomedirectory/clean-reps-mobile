package com.vaylith.cleanrepsmobile.diagnostics

import android.util.Log
import java.time.Instant

/** The fixed step vocabulary. [wireName] is what appears in logcat and the export. */
enum class DiagnosticStep(val wireName: String) {
    CREATE_SESSION("createSession"),
    ATTACH_CAPTURE("attachCapture"),
    CLIENT_INFO("clientInfo"),
    PUBLISHER_START("publisherStart"),
    SRT_CONNECT("srtConnect"),
    CAMERA_OPEN("cameraOpen"),
    PREVIEW_START("previewStart"),
    CREATE_BLOCK("createBlock"),
    MARK_REACQUIRED("markReacquired"),
    PAUSE("pause"),
    RESUME("resume"),
    /** `POST /v1/captures/:id/health`: the capture's video health report. */
    HEALTH("health"),

    /** `GET /health` (C5): the server check behind the reachability chip. */
    SERVER_HEALTH("serverHealth"),
    EVENT_STREAM("eventStream"),
    QUALITY_REPORT("qualityReport"),
}

enum class DiagnosticOutcome { OK, FAIL, INFO }

/** One stored event. [redactedMessage] has already passed through [Redaction]. */
data class DiagnosticsEntry(
    val timestampMs: Long,
    val step: DiagnosticStep,
    val outcome: DiagnosticOutcome,
    val errorClass: String?,
    val redactedMessage: String,
) {
    fun line(): String = buildString {
        append(Instant.ofEpochMilli(timestampMs)).append(' ')
        append(outcome.name).append(' ').append(step.wireName)
        errorClass?.let { append(' ').append(it) }
        if (redactedMessage.isNotEmpty()) append(": ").append(redactedMessage)
    }
}

enum class LogPriority { INFO, WARN }

/** Where each redacted entry is mirrored. JVM tests use a fake so they never touch `android.util.Log`. */
fun interface LogSink {
    fun write(priority: LogPriority, tag: String, line: String)
}

object LogcatSink : LogSink {
    override fun write(priority: LogPriority, tag: String, line: String) {
        when (priority) {
            LogPriority.INFO -> Log.i(tag, line)
            LogPriority.WARN -> Log.w(tag, line)
        }
    }
}

/**
 * Bounded in-memory ring of app events, mirrored to [sink] under [TAG]. Nothing
 * is uploaded. Every message is redacted before it is stored or mirrored;
 * replace [redaction] whenever the connection settings change.
 */
class DiagnosticsLog(
    private val clock: () -> Long,
    private val sink: LogSink,
    @Volatile var redaction: Redaction = Redaction.PATTERNS_ONLY,
    private val capacity: Int = CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lock = Any()
    private val ring = ArrayDeque<DiagnosticsEntry>(capacity)

    fun ok(step: DiagnosticStep, message: String = ""): DiagnosticsEntry =
        record(step, DiagnosticOutcome.OK, message, null)

    fun info(step: DiagnosticStep, message: String): DiagnosticsEntry =
        record(step, DiagnosticOutcome.INFO, message, null)

    fun fail(step: DiagnosticStep, message: String, error: Throwable? = null): DiagnosticsEntry =
        record(step, DiagnosticOutcome.FAIL, message, error)

    fun record(step: DiagnosticStep, outcome: DiagnosticOutcome, message: String, error: Throwable?): DiagnosticsEntry {
        val text = listOfNotNull(message, error?.let(::describe)).filter(String::isNotEmpty).joinToString(": ")
        // Redact before truncating so a secret cut in half can never escape.
        val redacted = redaction.redact(CONTROL_CHARACTERS.replace(text, " ")).take(MAX_MESSAGE_LENGTH)
        synchronized(lock) {
            val entry = DiagnosticsEntry(clock(), step, outcome, error?.let(::className), redacted)
            if (ring.size == capacity) ring.removeFirstOrNull()
            ring.addLast(entry)
            sink.write(if (outcome == DiagnosticOutcome.FAIL) LogPriority.WARN else LogPriority.INFO, TAG, entry.line())
            return entry
        }
    }

    /** Oldest first. */
    fun entries(): List<DiagnosticsEntry> = synchronized(lock) { ring.toList() }

    /** Text for the Diagnostics sheet's Copy action, redacted again with the current settings. */
    fun exportText(): String {
        val snapshot = entries()
        val text = buildString {
            append("Clean Reps diagnostics: ").append(snapshot.size).append(" events, oldest first, redacted\n")
            snapshot.forEach { append(it.line()).append('\n') }
        }
        return redaction.redact(text)
    }

    private fun describe(error: Throwable): String = buildString {
        append(error.message.orEmpty())
        var cause = error.cause
        var depth = 0
        while (cause != null && cause !== error && depth < MAX_CAUSES) {
            append(" | caused by ").append(className(cause))
            cause.message?.let { append(": ").append(it) }
            cause = cause.cause
            depth += 1
        }
    }

    private fun className(error: Throwable) = error.javaClass.simpleName.ifEmpty { error.javaClass.name }

    companion object {
        const val TAG = "CleanReps"
        const val CAPACITY = 100
        const val MAX_MESSAGE_LENGTH = 500
        private const val MAX_CAUSES = 3
        private val CONTROL_CHARACTERS = Regex("""\p{Cntrl}+""")
    }
}
