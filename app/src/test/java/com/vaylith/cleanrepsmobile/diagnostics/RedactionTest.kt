package com.vaylith.cleanrepsmobile.diagnostics

import com.vaylith.cleanrepsmobile.media.MediaMtxSrtConfig
import com.vaylith.cleanrepsmobile.model.ConnectionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactionTest {
    private val settings = ConnectionSettings(
        apiBaseUrl = "http://192.0.2.20:8080",
        srtHost = "video.example.test:8890",
        srtPassphrase = "Zq7!long-passphrase",
        publishPassword = "publisher-secret",
    )
    private val configured = Redaction.forSettings(settings)
    private val patternsOnly = Redaction.PATTERNS_ONLY

    private fun assertRedacted(redacted: String, vararg secrets: String) {
        secrets.forEach { assertFalse("'$it' leaked in: $redacted", redacted.contains(it, ignoreCase = true)) }
    }

    @Test fun `synthetic SRT publish URL is redacted by pattern alone`() {
        val url = "srt://192.0.2.10:8890?mode=caller&streamid=#!::m=publish,r=million-kicks-camera,u=publisher,s=pw&passphrase=abcdefghij"
        assertEquals("srt://<redacted>", patternsOnly.redact(url))
        assertEquals(
            "SRT connect failed: srt://<redacted> (retry 3)",
            patternsOnly.redact("SRT connect failed: $url (retry 3)"),
        )
    }

    @Test fun `the URL the app actually publishes to is redacted`() {
        val endpoint = MediaMtxSrtConfig("192.0.2.10", "abcdefghij", "publisher-secret").endpoint()
        for (redaction in listOf(patternsOnly, configured)) {
            val redacted = redaction.redact("java.io.IOException: SRT handshake failed for $endpoint")
            assertEquals("java.io.IOException: SRT handshake failed for srt://<redacted>", redacted)
        }
    }

    @Test fun `percent-encoded SRT URL is redacted`() {
        val redacted = patternsOnly.redact("bad target srt%3A%2F%2F192.0.2.10%3A8890%3Fpassphrase%3Dabcdefghij done")
        assertEquals("bad target srt://<redacted> done", redacted)
    }

    @Test fun `passphrase inside exception text is redacted`() {
        assertEquals(
            "java.io.IOException: handshake rejected passphrase=<redacted>&latency=1000000",
            patternsOnly.redact("java.io.IOException: handshake rejected passphrase=abcdefghij&latency=1000000"),
        )
        assertEquals(
            "MEDIAMTX_SRT_PASSPHRASE=<redacted> rejected",
            patternsOnly.redact("MEDIAMTX_SRT_PASSPHRASE=abcdefghij rejected"),
        )
    }

    @Test fun `streamid and publisher secret are redacted outside a URL`() {
        val streamId = patternsOnly.redact("bad streamid=#!::m=publish,r=million-kicks-camera,u=publisher,s=hunter2x for path")
        assertEquals("bad streamid=<redacted> for path", streamId)
        val secret = patternsOnly.redact("auth failed for u=publisher,s=hunter2x (401)")
        assertEquals("auth failed for u=publisher,s=<redacted> (401)", secret)
    }

    @Test fun `API error body echoing a password is redacted`() {
        val keyed = patternsOnly.redact("""409 {"error":"conflict","password":"hunter2 two words","passphrase": "abcdefghij"}""")
        assertRedacted(keyed, "hunter2", "two words", "abcdefghij")
        assertEquals("""409 {"error":"conflict","password":<redacted>,"passphrase": <redacted>}""", keyed)

        val echoed = configured.redact("""400 {"error":"publish password publisher-secret is not accepted"}""")
        assertEquals("""400 {"error":"publish password <redacted> is not accepted"}""", echoed)
    }

    @Test fun `truncated or escaped quoted values are redacted to the end`() {
        // ChallengeApi keeps only the first 300 characters of an error body.
        assertEquals("""500 {"password":<redacted>""", patternsOnly.redact("""500 {"password":"hunter2 cut off"""))
        assertEquals(
            """400 {"password":<redacted>,"ok":true}""",
            patternsOnly.redact("""400 {"password":"a\"hunter2","ok":true}"""),
        )
        assertEquals("streamid=<redacted>", patternsOnly.redact("streamid='#!::m=publish,s=hunter2"))
    }

    @Test fun `userinfo in an HTTP URL is redacted`() {
        assertEquals(
            "GET http://<redacted>@example.test:8080/v1/challenge-sessions failed",
            patternsOnly.redact("GET http://owner:s3cret@example.test:8080/v1/challenge-sessions failed"),
        )
        assertEquals("https://<redacted>@example.test", patternsOnly.redact("https://owner:p@ss@example.test"))
        // An '@' outside the authority is not userinfo.
        assertEquals(
            "https://example.test/v1/users?email=a@example.test",
            patternsOnly.redact("https://example.test/v1/users?email=a@example.test"),
        )
    }

    @Test fun `each of the four literal settings values is redacted wherever it appears`() {
        val texts = mapOf(
            settings.apiBaseUrl to "Clean Reps at http://192.0.2.20:8080 timed out",
            settings.srtHost to "video server video.example.test:8890 unreachable",
            settings.srtPassphrase to "server echoed Zq7!long-passphrase back",
            settings.publishPassword to "wrong key publisher-secret",
        )
        texts.forEach { (secret, text) ->
            val redacted = configured.redact(text)
            assertRedacted(redacted, secret)
            assertEquals(1, Regex(Regex.escape(Redaction.PLACEHOLDER)).findAll(redacted).count())
        }
        // Matching ignores case, so a host echoed in capitals is still redacted.
        assertRedacted(configured.redact("VIDEO.EXAMPLE.TEST:8890 refused"), "video.example.test")
    }

    @Test fun `hosts inside the settings are redacted when a socket error names only the address`() {
        val redacted = configured.redact(
            "failed to connect to /192.0.2.20 (port 8080) from /192.0.2.99; video.example.test lookup failed",
        )
        assertEquals("failed to connect to /<redacted> (port 8080) from /192.0.2.99; <redacted> lookup failed", redacted)
    }

    @Test fun `JSON-escaped literal values are redacted`() {
        val quoted = Redaction.forSettings(settings.copy(publishPassword = "quote\"secret"))
        assertRedacted(quoted.redact("""{"echo":"quote\"secret"}"""), "quote\\\"secret", "secret")
    }

    @Test fun `innocuous text is unchanged`() {
        val innocuous = listOf(
            "Canonical SRT source is live: million-kicks-camera",
            "Server refused attach capture (409)",
            "HTTP 503 from /v1/challenge-sessions/abc/blocks",
            "status=live kicks=5 epoch 3",
            "The video passphrase must contain 10–79 characters.",
            "Couldn't reach Clean Reps for create session (timeout) — is Tailscale on?",
            "Connecting one SRT source for epoch 7…",
            "https://example.test/v1/health",
            "",
        )
        for (redaction in listOf(patternsOnly, configured)) {
            innocuous.forEach { assertEquals(it, redaction.redact(it)) }
        }
    }

    @Test fun `redaction is idempotent`() {
        val once = configured.redact("passphrase=abcdefghij srt://192.0.2.10:8890?s=pw publisher-secret http://a:b@example.test")
        assertEquals(once, configured.redact(once))
    }
}
