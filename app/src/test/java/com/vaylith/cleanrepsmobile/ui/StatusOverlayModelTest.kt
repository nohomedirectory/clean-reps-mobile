package com.vaylith.cleanrepsmobile.ui

import com.vaylith.cleanrepsmobile.api.ChallengeEventParser
import com.vaylith.cleanrepsmobile.api.ServerHealth
import com.vaylith.cleanrepsmobile.model.CaptureReadiness
import com.vaylith.cleanrepsmobile.model.LiveAnalysisState
import com.vaylith.cleanrepsmobile.model.LiveAnalysisStatus
import com.vaylith.cleanrepsmobile.model.LiveBlockedReason
import com.vaylith.cleanrepsmobile.session.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.*
import org.junit.Test

class StatusOverlayModelTest {
    /**
     * The C3 phone texts, copied verbatim from the clean-reps contract (docs/tonight-sprint/clean-reps-api.md,
     * "State vocabulary"): wire state and reasonCode -> text.
     */
    private val contract = listOf(
        Triple("starting", null, "Starting analysis..."),
        Triple("acquiring", null, "Finding you..."),
        Triple("tracking", null, "Tracking you"),
        Triple("no_person", null, "Can't see you - get your whole body, head to feet, in view"),
        Triple("sideways", null, "Video is sideways - stop and restart video"),
        Triple("head_cut", null, "Head not visible - move the phone back or higher"),
        Triple("feet_cut", null, "Feet not visible - move the phone back or lower"),
        Triple("too_small", null, "Too far - move closer"),
        Triple("multiple_people", null, "More than one person in view"),
        Triple("blocked", "unsupported_practice_technique", "Automatic analysis supports Teep only"),
        Triple("blocked", "waiting_for_new_capture_epoch", "Analysis stopped - tap Restart video"),
        Triple("blocked", "worker_retry_limit", "Analysis stopped - tap Restart video"),
        Triple("blocked", "source_geometry_unsupported", "Video format not supported"),
        Triple("blocked", "ambiguous_active_sessions", "Another Clean Reps session is still open - tap Restart video"),
    )

    /** One SSE `challenge-state` frame with the C3 projection, using the contract's field names. */
    private fun frame(state: String, reason: String? = null, paused: Boolean = false, stale: Boolean = false): String {
        val reasonJson = reason?.let { "\"$it\"" } ?: "null"
        return """{"challengeOfficialAcceptedCount":0,"accepted":0,"rejected":0,"evidenceFailed":0,"liveAnalysis":{"state":"$state",""" +
            """"reasonCode":$reasonJson,"detail":"","practicePaused":$paused,"sourceGeometry":{"width":1280,"height":720,"orientation":"landscape"},""" +
            """"athleteRegion":{"left":0.2,"top":0.05,"right":0.8,"bottom":0.95},"stale":$stale}}"""
    }

    /**
     * The chip's short label for each contract row (orchestrator decision on M8a): the chip names the
     * state, the rail's hint under the primary button carries the full contract sentence.
     */
    private val shortLabels = mapOf(
        "starting" to "Starting", "acquiring" to "Finding you", "tracking" to "Tracking", "no_person" to "No one in view",
        "sideways" to "Video sideways", "head_cut" to "Head cut off", "feet_cut" to "Feet cut off", "too_small" to "Too far",
        "multiple_people" to "Too many people", "unsupported_practice_technique" to "Teep only",
        "waiting_for_new_capture_epoch" to "Stopped - restart video", "worker_retry_limit" to "Stopped - restart video",
        "source_geometry_unsupported" to "Unsupported video", "ambiguous_active_sessions" to "Stopped - restart video",
    )

    /** The screen state for one SSE frame, parsed by the real ChallengeEventParser. */
    private fun live(json: String): AppState? {
        val status = ChallengeEventParser.parseLiveAnalysis(json) ?: return null
        return AppState(readiness = CaptureReadiness.LIVE, liveAnalysis = status, captureId = "capture-1")
    }

    private fun chip(json: String): AnalysisChip? = live(json)?.let { StatusOverlayModel.from(it, health = null).analysis }

    private fun railHint(json: String): String? = live(json)?.let { ControlRailModel.from(it, configured = true, permission = true).hint }

    @Test fun `every C3 state and reasonCode - the rail shows the contract text, the chip its short label`() {
        for ((state, reason, text) in contract) {
            val json = frame(state, reason)
            assertEquals("$state/$reason", text, railHint(json))
            val chip = chip(json)!!
            assertEquals("$state/$reason", shortLabels.getValue(reason ?: state), chip.text)
            assertFalse("$state/$reason", chip.paused)
        }
        // Every known state and blocked reason is covered by the contract table.
        val covered = contract.map { it.first }.toSet()
        LiveAnalysisState.entries.mapNotNull { it.wireName }.filter { it != "stale" }.forEach { assertTrue(it, it in covered) }
        LiveBlockedReason.entries.mapNotNull { it.wireName }.forEach { reason -> assertTrue(reason, contract.any { it.second == reason }) }
        assertEquals("Analysis", StatusOverlayModel.ANALYSIS)
    }

    @Test fun `practicePaused adds the Paused badge beside the state`() {
        val chip = chip(frame("tracking", paused = true))!!
        assertEquals(AnalysisChip("Tracking", paused = true, tone = ChipTone.GOOD), chip)
        assertEquals("Paused", StatusOverlayModel.PAUSED)
    }

    @Test fun `a stale status reads unavailable and keeps the badge`() {
        // The server projects stale as state stale, stale true, reasonCode null, detail "".
        assertEquals(AnalysisChip("Unavailable", paused = true, tone = ChipTone.UNAVAILABLE), chip(frame("stale", paused = true, stale = true)))
        assertEquals("Analysis status unavailable", railHint(frame("stale", paused = true, stale = true)))
        // The flag alone is enough, whatever the state says.
        assertEquals("Unavailable", chip(frame("tracking", stale = true))!!.text)
        assertEquals("Analysis status unavailable", railHint(frame("tracking", stale = true)))
    }

    @Test fun `an unknown state or reason shows a safe fallback, never a crash (planted)`() {
        assertEquals(AnalysisChip("Unknown", paused = false, tone = ChipTone.UNAVAILABLE), chip(frame("levitating")))
        assertEquals("Analysis status unknown", railHint(frame("levitating")))
        assertEquals("Stopped", chip(frame("blocked", "cosmic_rays"))!!.text)
        assertEquals("Analysis stopped", railHint(frame("blocked", "cosmic_rays")))
        assertEquals("Stopped", chip(frame("blocked", null))!!.text)
        assertEquals("Unknown", chip(frame(""))!!.text)
        // A malformed projection is ignored by the parser: no chip, no exception.
        assertNull(chip("""{"liveAnalysis":{"state":42}}"""))
        assertNull(chip("""{"liveAnalysis":"tracking"}"""))
        assertNull(chip("not json"))
    }

    @Test fun `the chip shows only while LIVE and never repeats the rail's sentence`() {
        val status = LiveAnalysisStatus(LiveAnalysisState.HEAD_CUT)
        for (readiness in CaptureReadiness.entries) {
            val model = StatusOverlayModel.from(AppState(readiness = readiness, liveAnalysis = status, captureId = "capture-1"), null)
            assertEquals("$readiness", readiness == CaptureReadiness.LIVE, model.analysis != null)
        }
        assertNull(StatusOverlayModel.from(AppState(readiness = CaptureReadiness.LIVE), null).analysis)
        // Every state, reason and staleness: the chip is a short label, and never the hint under the primary button.
        val statuses = LiveAnalysisState.entries.flatMap { state ->
            (listOf<LiveBlockedReason?>(null) + LiveBlockedReason.entries).flatMap { reason ->
                listOf(false, true).map { stale -> LiveAnalysisStatus(state, reason, stale = stale) }
            }
        }
        for (live in statuses) {
            val state = AppState(readiness = CaptureReadiness.LIVE, liveAnalysis = live, captureId = "capture-1")
            val hint = ControlRailModel.from(state, configured = true, permission = true).hint!!
            val label = StatusOverlayModel.from(state, null).analysis!!.text
            assertNotEquals("$live", hint, label)
            assertTrue("$live: <$label>", label.isNotBlank() && label.length <= 24)
        }
    }

    @Test fun `a due framing hint changes only the rail's acquiring line, never the chip`() {
        val frames = contract.map { (state, reason) -> frame(state, reason) } + frame("stale", stale = true) + frame("acquiring", stale = true)
        var searching = 0
        for (json in frames) {
            val due = live(json)!!.copy(framingHintDue = true)
            val chip = StatusOverlayModel.from(due, null).analysis!!
            assertEquals(json, chip(json), chip)
            val hint = ControlRailModel.from(due, configured = true, permission = true).hint
            if (due.liveAnalysis!!.state == LiveAnalysisState.ACQUIRING && due.liveAnalysis.available) {
                assertEquals("Can't see you - get your whole body, head to feet, in view", hint)
                assertEquals("Finding you", chip.text)
                searching++
            } else {
                assertEquals(json, railHint(json), hint)
            }
            assertNotEquals(json, hint, chip.text)
        }
        assertEquals(1, searching)
    }

    @Test fun `the reachability chip shows reachable with the server release, or unreachable`() {
        assertEquals(ReachabilityChip("Server: checking...", reachable = null), StatusOverlayModel.reachability(null))
        assertEquals(ReachabilityChip("Server reachable - release 0123abc", reachable = true),
            StatusOverlayModel.reachability(ServerHealth(reachable = true, release = "0123abcdef0123abcdef0123abcdef0123abcdef")))
        assertEquals("Server reachable - release 1234567", StatusOverlayModel.reachability(ServerHealth(true, "1234567")).text)
        // C5: an unset or rejected CLEAN_REPS_RELEASE_SHA is reported as "unknown".
        assertEquals("Server reachable - release unknown", StatusOverlayModel.reachability(ServerHealth(true, "unknown")).text)
        assertEquals("Server reachable - release not reported", StatusOverlayModel.reachability(ServerHealth(true, null)).text)
        assertEquals(ReachabilityChip("Server unreachable", reachable = false), StatusOverlayModel.reachability(ServerHealth(false, null)))
        assertEquals(StatusOverlayModel.reachability(ServerHealth(true, "1234567")), StatusOverlayModel.from(AppState(), ServerHealth(true, "1234567")).reachability)
    }

    @Test fun `the session counts come from the parsed snapshot only`() {
        val counts = ChallengeEventParser.parseSessionCounts("""{"accepted":3,"rejected":1,"evidenceFailed":2,"liveAnalysis":null}""")
        assertEquals("This session 3 accepted - 1 rejected", StatusOverlayModel.from(AppState(sessionCounts = counts), null).counts)
        assertNull(StatusOverlayModel.from(AppState(), null).counts)
        // A verdict on screen is not a count: only the snapshot's numbers are shown.
        val state = AppState(sessionCounts = counts, feedbackBanner = "Couldn't judge that one - keep head and feet in view", challengeOfficialAcceptedCount = 999)
        assertEquals("This session 3 accepted - 1 rejected", StatusOverlayModel.from(state, null).counts)
    }

    /**
     * Virtual time on a scheduler of its own, not runTest: runTest reports any earlier test's leaked
     * coroutine exception as its own failure.
     */
    @Test fun `health is checked at once, then every 15 s, until the poll is cancelled`() {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(StandardTestDispatcher(scheduler))
        var checks = 0
        val seen = mutableListOf<ServerHealth>()
        val poll = scope.launch { pollServerHealth({ checks++; ServerHealth(true, "abc1234") }) { seen += it } }
        scheduler.runCurrent()
        assertEquals(1, checks)
        scheduler.advanceTimeBy(HEALTH_POLL_MS - 1)
        scheduler.runCurrent()
        assertEquals(1, checks)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertEquals(2, checks)
        assertEquals(15_000L, HEALTH_POLL_MS)
        poll.cancel()
        scheduler.advanceTimeBy(10 * HEALTH_POLL_MS)
        scheduler.runCurrent()
        assertEquals(2, checks)
        assertEquals(2, seen.size)
        scope.cancel()
    }
}
