package com.vaylith.cleanrepsmobile.ui

import com.vaylith.cleanrepsmobile.api.ChallengeApi
import com.vaylith.cleanrepsmobile.api.FetchResult
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticStep
import com.vaylith.cleanrepsmobile.diagnostics.FailureKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.*
import org.junit.Test

/**
 * The Session check card from synthetic C4 schema-v1 GET bodies: the stored report's keys plus
 * `clientInfo` and `outcomes`, with the field names of clean-reps docs/tonight-sprint/clean-reps-api.md.
 * Coroutine tests use their own TestCoroutineScheduler, not runTest (see SessionControllerTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionCheckCardModelTest {
    private val build = BuildIdentity("0.3.0-rehearsal", 3, "abcdef0123456789abcdef0123456789abcdef01", "Motorola", "moto g", 34)

    /** A GET body. Defaults: a final PASS report of 200 processed frames with 160 selected. */
    private fun body(
        final: Boolean = true,
        overall: String = "PASS",
        geometry: String = "PASS",
        upright: String = "PASS",
        selectedRate: String = "PASS",
        fullBody: String = "PASS",
        advice: String = "[]",
        processed: Long = 200,
        selected: Long = 160,
        head: Long = 152,
        ankle: Long = 149,
        thumbnails: String = """[{"index":0,"atSourceMs":1200},{"index":1,"atSourceMs":45000},{"index":2,"atSourceMs":91000}]""",
        clientInfo: String = """{"appVersionName":"0.3.0-rehearsal","appVersionCode":3,"appGitSha":"4acbcce","deviceManufacturer":"Motorola",""" +
            """"deviceModel":"moto g","androidSdkInt":34,"cameraSensorOrientationDeg":90,"displayRotationDeg":90,"captureOrientation":"landscape",""" +
            """"encodedWidth":1280,"encodedHeight":720,"sentAt":"2026-09-25T10:00:00.000Z"}""",
        accepted: Long = 10,
        rejected: Long = 2,
        evidenceFailed: Long = 3,
        schemaVersion: Int = 1,
    ) = """{"schemaVersion":$schemaVersion,"captureSessionId":"capture-1","sourceEpoch":0,"sessionId":"session-1","analysisRunId":"run-1",""" +
        """"final":$final,"generatedAt":"2026-09-25T10:05:00.000Z","source":{"width":1280,"height":720,"sampleAspectRatio":"1:1","displayRotation":0},""" +
        """"analysis":{"box":{"width":1280,"height":720},"sampleRateHz":8,"decodedFrames":210,"processedFrames":$processed,"skippedFrames":10,""" +
        """"expiredFrames":0,"detectedFrames":${maxOf(selected, 170)},"uprightFrames":165,"selectedFrames":$selected,"selectedWithHead":$head,""" +
        """"selectedWithAnkle":$ankle,"selectorReasons":{"no-usable-person":30},"torsoCenter":{"meanX":0.5,"meanY":0.55,"stdX":0.05,"stdY":0.04},""" +
        """"candidates":{"attempts":14,"fragments":1,"bilateralDuplicates":0,"bridged":0},"coverageSeconds":{"tracking":80.5},""" +
        """"firstFrameAt":"2026-09-25T10:00:02.000Z","lastFrameAt":"2026-09-25T10:04:58.000Z","inferenceMsP50":21.5,"inferenceMsP95":40,"stopReason":"stopped"},""" +
        """"verdict":{"overall":"$overall","geometry":"$geometry","upright":"$upright","selectedRate":"$selectedRate","fullBody":"$fullBody","advice":$advice},""" +
        """"thumbnails":$thumbnails,"clientInfo":$clientInfo,""" +
        """"outcomes":{"kickEvents":16,"accepted":$accepted,"rejected":$rejected,"evidenceFailed":$evidenceFailed,"analysisStartLatencyMs":2100}}"""

    private fun model(json: String, release: String? = "0123abcdef0123abcdef0123abcdef0123abcdef") =
        SessionCheckCardModel.from(QualityReport.parse(json)!!, build, release)

    @Test fun `a PASS report shows the verdicts, the rates, judged versus unavailable, the versions and three thumbnails`() {
        val card = model(body())
        assertTrue(card.final)
        assertEquals(Check.PASS, card.overall)
        assertEquals(
            listOf("Video geometry" to Check.PASS, "Upright" to Check.PASS, "Athlete detected" to Check.PASS, "Head and feet in frame" to Check.PASS),
            card.checks,
        )
        assertEquals(emptyList<String>(), card.advice)
        // Body visible = selectedFrames / processedFrames.
        assertEquals("Body visible in 80% of analysed frames (160 of 200)", card.bodyVisible)
        // 152 / 160 = 95%, 149 / 160 = 93.1%.
        assertEquals("Head visible in 95%, feet in 93% of those frames", card.headAndFeet)
        // Judged = accepted + rejected; unavailable = evidenceFailed (pending events are in neither).
        assertEquals("Judged 12 (10 accepted - 2 rejected) - unavailable 3", card.judged)
        assertEquals("App 0.3.0-rehearsal (4acbcce) - server release 0123abc", card.versions)
        assertEquals(listOf(0, 1, 2), card.thumbnails)
    }

    @Test fun `WARN and FAIL reports carry their checks and the worker's advice`() {
        val warn = model(body(overall = "WARN", upright = "WARN", advice = """["Video looks tilted; check the phone mount"]"""))
        assertEquals(Check.WARN, warn.overall)
        assertEquals(Check.WARN, warn.checks.single { it.first == "Upright" }.second)
        assertEquals(listOf("Video looks tilted; check the phone mount"), warn.advice)

        val fail = model(body(overall = "FAIL", selectedRate = "FAIL", fullBody = "FAIL", selected = 20, head = 5, ankle = 18,
            advice = """["athlete not detected most of the time","head or feet out of frame"]"""))
        assertEquals(Check.FAIL, fail.overall)
        assertEquals(listOf(Check.PASS, Check.PASS, Check.FAIL, Check.FAIL), fail.checks.map { it.second })
        assertEquals(listOf("athlete not detected most of the time", "head or feet out of frame"), fail.advice)
        assertEquals("Body visible in 10% of analysed frames (20 of 200)", fail.bodyVisible)
        assertEquals("Head visible in 25%, feet in 90% of those frames", fail.headAndFeet)
    }

    @Test fun `versions fall back to this phone's app and an unknown server release`() {
        val card = model(body(clientInfo = "null"), release = null)
        assertEquals("App 0.3.0-rehearsal (abcdef0, this phone) - server release unknown", card.versions)
        assertEquals("server release unknown", model(body(), release = "unknown").versions.substringAfter(" - "))
    }

    @Test fun `empty counts never divide by zero`() {
        val card = model(body(processed = 0, selected = 0, head = 0, ankle = 0))
        assertEquals("Body visible: no frames were analysed", card.bodyVisible)
        assertEquals("Head and feet: no athlete was selected", card.headAndFeet)
        assertNull(SessionCheckCardModel.percent(1, 0))
        assertEquals("100%", SessionCheckCardModel.percent(5, 5))
    }

    @Test fun `only the listed thumbnails are fetched, first to latest`() {
        assertEquals(listOf(0, 2), model(body(thumbnails = """[{"index":2,"atSourceMs":9},{"index":0,"atSourceMs":1}]""")).thumbnails)
        assertEquals(emptyList<Int>(), model(body(thumbnails = "[]")).thumbnails)
    }

    @Test fun `malformed report JSON is refused, never a crash (planted)`() {
        val bad = listOf(
            "not json", "", "{", "[]", "null",
            body(schemaVersion = 2),
            body(overall = "MAYBE"),
            body(advice = """["ok",42]"""),
            body(advice = "\"not an array\""),
            body(thumbnails = """[{"index":3,"atSourceMs":0}]"""),
            body(thumbnails = """[{"index":0,"atSourceMs":0},]"""),
            body(processed = -1),
            body(clientInfo = "{\"appVersionName\":"),
            body().replace(""""verdict":{""", """"verdictX":{"""),
            body().replace(""","outcomes":{"kickEvents":16,"accepted":10,"rejected":2,"evidenceFailed":3,"analysisStartLatencyMs":2100}""", ""),
            body().dropLast(1),
        )
        bad.forEachIndexed { i, json -> assertNull("case $i", QualityReport.parse(json)) }
        assertNotNull(QualityReport.parse(body()))
    }

    // --- The poll after Stop video, on virtual time.

    private class Rig(private val answers: (Int) -> FetchResult<String>) {
        val scheduler = TestCoroutineScheduler()
        private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
        val fetchTimes = mutableListOf<Long>()
        val states = mutableListOf<SessionCheckState>()
        var result: SessionCheckState? = null
        var failWith: Exception? = null

        fun start(): Rig = apply {
            scope.launch {
                result = pollSessionCheck(
                    fetch = {
                        fetchTimes += scheduler.currentTime
                        failWith?.let { throw it }
                        answers(fetchTimes.size)
                    },
                    now = { scheduler.currentTime },
                ) { states += it }
            }
            scheduler.runCurrent()
        }

        fun runFor(ms: Long) {
            scheduler.advanceTimeBy(ms)
            scheduler.runCurrent()
        }

        fun stop() = scope.cancel()
    }

    @Test fun `polling stops at the first final report`() {
        val rig = Rig { call -> if (call < 3) FetchResult.Found(body(final = false)) else FetchResult.Found(body(final = true)) }.start()
        rig.runFor(60_000)
        assertEquals(listOf(0L, 2_000L, 4_000L), rig.fetchTimes)
        val result = rig.result as SessionCheckState.Report
        assertTrue(result.complete && result.report.final)
        // The interim reports were shown while waiting.
        assertEquals(SessionCheckState.Checking, rig.states.first())
        assertEquals(2, rig.states.count { it is SessionCheckState.Report && !it.complete })
    }

    @Test fun `without a final report polling ends at 20 s with the latest interim report`() {
        val rig = Rig { FetchResult.Found(body(final = false)) }.start()
        rig.runFor(19_999)
        assertNull(rig.result)
        rig.runFor(1)
        assertEquals((0..10).map { it * 2_000L }, rig.fetchTimes)
        val result = rig.result as SessionCheckState.Report
        assertTrue(result.complete)
        assertFalse(result.report.final)
        rig.runFor(60_000)
        assertEquals(11, rig.fetchTimes.size)
        assertEquals("Interim report: the final report did not arrive within 20 s", SessionCheckCardModel.INTERIM)
    }

    @Test fun `a report that never arrives is 404 - No analysis ran for this capture`() {
        val rig = Rig { FetchResult.NotFound }.start()
        rig.runFor(60_000)
        assertEquals(SessionCheckState.NoAnalysis, rig.result)
        assertEquals(11, rig.fetchTimes.size)
        assertEquals("No analysis ran for this capture", SessionCheckCardModel.NO_ANALYSIS)
        // A report that arrives late in the window still wins.
        val late = Rig { call -> if (call < 6) FetchResult.NotFound else FetchResult.Found(body()) }.start()
        late.runFor(60_000)
        assertTrue(late.result is SessionCheckState.Report)
        assertEquals(10_000L, late.fetchTimes.last())
    }

    @Test fun `an unreadable body or a failed request is a safe message, never exception text (planted)`() {
        val unreadable = Rig { FetchResult.Found("""{"schemaVersion":1,"final":true""") }.start()
        unreadable.runFor(60_000)
        assertEquals(SessionCheckState.Failed("The session check report could not be read"), unreadable.result)

        val secret = "srt-secret-passphrase-9"
        val failing = Rig { error("unused") }.apply {
            failWith = ChallengeApi.ApiException("qualityReport timeout $secret", DiagnosticStep.QUALITY_REPORT, FailureKind.Timeout)
        }.start()
        failing.runFor(60_000)
        val failed = failing.result as SessionCheckState.Failed
        assertEquals("Couldn't reach Clean Reps for session check (timeout) — is Tailscale on?", failed.message)
        assertFalse(secret in failed.message)

        val other = Rig { error("unused") }.apply { failWith = IllegalStateException("boom $secret") }.start()
        other.runFor(60_000)
        assertFalse(secret in (other.result as SessionCheckState.Failed).message)
    }

    @Test fun `closing the card cancels the poll`() {
        val rig = Rig { FetchResult.NotFound }.start()
        rig.runFor(3_000)
        rig.stop()
        rig.runFor(60_000)
        assertEquals(listOf(0L, 2_000L), rig.fetchTimes)
        assertNull(rig.result)
    }

    @Test fun `a thumbnail is decoded at most 640 px on its longer side`() {
        assertEquals(1, thumbnailSampleSize(640, 360))
        assertEquals(2, thumbnailSampleSize(1280, 720))
        assertEquals(2, thumbnailSampleSize(720, 1280))
        assertEquals(16, thumbnailSampleSize(8192, 8192))
        assertEquals(1, thumbnailSampleSize(0, 0))
        for (side in listOf(1, 639, 640, 641, 1279, 1281, 4000, 8192)) {
            assertTrue("$side", side / thumbnailSampleSize(side, 1) <= THUMBNAIL_MAX_SIDE)
        }
    }
}
