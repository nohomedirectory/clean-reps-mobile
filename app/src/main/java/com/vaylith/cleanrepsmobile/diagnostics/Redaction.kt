package com.vaylith.cleanrepsmobile.diagnostics

import com.vaylith.cleanrepsmobile.model.ConnectionSettings
import java.net.URI

/**
 * C7 redaction. Every diagnostics message passes through [redact] before it is
 * stored or written to logcat, because the app handles SRT URLs with
 * passphrases and publish passwords and this repository is public.
 *
 * [literals] are the runtime values of the connection settings; they are
 * replaced wherever they appear, whatever surrounds them.
 */
class Redaction(literals: Iterable<String>) {
    private val literalPattern: Regex? = literals
        .flatMap { listOf(it, jsonEscaped(it)) }
        .filter(String::isNotBlank)
        .distinct()
        .sortedByDescending(String::length)
        .takeIf { it.isNotEmpty() }
        ?.let { values -> Regex(values.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE) }

    fun redact(text: String): String {
        // Literals go first, in one pass, so a short value cannot match inside
        // an earlier placeholder. The patterns then catch secrets whose values
        // are not configured on this phone (for example a server echo).
        var result = literalPattern?.replace(text) { PLACEHOLDER } ?: text
        result = SRT_URL.replace(result) { "srt://$PLACEHOLDER" }
        result = URL_USERINFO.replace(result) { it.groupValues[1] + PLACEHOLDER + "@" }
        result = STREAM_ID.replace(result) { it.groupValues[1] + PLACEHOLDER }
        result = SECRET_KEY_VALUE.replace(result) { it.groupValues[1] + PLACEHOLDER }
        result = PUBLISHER_SECRET.replace(result) { "s=$PLACEHOLDER" }
        return result
    }

    companion object {
        const val PLACEHOLDER = "<redacted>"

        /** Pattern-only redaction, for use before any settings are known. */
        val PATTERNS_ONLY = Redaction(emptyList())

        /**
         * The four settings values plus the hosts inside them, so a socket
         * error naming only the address ("failed to connect to /host:port")
         * is still redacted.
         */
        fun forSettings(settings: ConnectionSettings): Redaction {
            val api = settings.apiBaseUrl.trim()
            val apiUri = runCatching { URI(api) }.getOrNull()
            val srtHost = settings.srtHost.trim()
            return Redaction(
                listOfNotNull(
                    api,
                    api.trimEnd('/'),
                    apiUri?.rawAuthority,
                    apiUri?.host,
                    srtHost,
                    runCatching { URI("srt://$srtHost").host }.getOrNull(),
                    settings.srtPassphrase,
                    settings.publishPassword,
                ),
            )
        }

        private val SRT_URL = Regex("""(?i)srt(?:://|%3A%2F%2F)[^\s"'`]+""")
        private val URL_USERINFO = Regex("""(?i)\b([a-z][a-z0-9+.-]*://)[^/\s?#"'`]+@""")
        // A quoted value may contain escaped quotes, or be cut off (for example
        // by a truncated error body) before its closing quote.
        private const val QUOTED_VALUE = """"(?:[^"\\\n]|\\.)*"?|'(?:[^'\\\n]|\\.)*'?"""
        private val STREAM_ID = Regex("""(?i)(streamid["']?\s*[=:]\s*)($QUOTED_VALUE|[^\s&"']+)""")
        private val SECRET_KEY_VALUE =
            Regex("""(?i)((?:passphrase|password|passwd)["']?\s*[=:]\s*)($QUOTED_VALUE|[^\s&"',;]+)""")
        private val PUBLISHER_SECRET = Regex("""(?i)(?<![\w-])s=[^\s&,"']+""")

        private fun jsonEscaped(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
