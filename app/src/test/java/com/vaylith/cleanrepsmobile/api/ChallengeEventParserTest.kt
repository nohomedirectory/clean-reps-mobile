package com.vaylith.cleanrepsmobile.api

import com.vaylith.cleanrepsmobile.model.VerdictTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.vaylith.cleanrepsmobile.model.AthleteCue
import com.vaylith.cleanrepsmobile.model.LiveAnalysisState
import com.vaylith.cleanrepsmobile.model.LiveAnalysisStatus
import com.vaylith.cleanrepsmobile.model.LiveAthleteRegion
import com.vaylith.cleanrepsmobile.model.LiveBlockedReason
import com.vaylith.cleanrepsmobile.model.LiveSourceGeometry
import com.vaylith.cleanrepsmobile.model.LiveSourceOrientation
import com.vaylith.cleanrepsmobile.model.VerdictClass
import com.vaylith.cleanrepsmobile.model.phoneText
import org.junit.Test

class ChallengeEventParserTest {
    @Test fun `missing safe window never triggers speech from a null equality`() {
        val cue = AthleteCue("cue", "A delayed cue", 1, "technical", null)
        assertFalse(cueHasResolvedSafeWindow(cue, null))
        assertFalse(cueHasResolvedSafeWindow(cue.copy(safeAfterKickEventId = "kick-1"), "kick-2"))
        assertTrue(cueHasResolvedSafeWindow(cue.copy(safeAfterKickEventId = "kick-1"), "kick-1"))
    }
    @Test fun `challenge total comes only from explicit cumulative server field`() {
        assertEquals(123L, ChallengeEventParser.parseChallengeTotal("{\"officialAcceptedCount\":2,\"challengeOfficialAcceptedCount\":123}"))
        assertNull(ChallengeEventParser.parseChallengeTotal("{\"officialAcceptedCount\":2}"))
        assertNull(ChallengeEventParser.parseChallengeTotal("{\"challengeOfficialAcceptedCount\":-1}"))
    }
    @Test fun `maps verdict semantics without confusing missing evidence and bad form`() {
        assertEquals(VerdictTone.ACCEPTED, verdict("accepted", "count_worthy").tone)
        assertEquals(VerdictTone.REJECTED, verdict("rejected", "balance_loss").tone)
        assertEquals(VerdictTone.NEUTRAL, verdict("pending_review", "awaiting_analysis").tone)
        assertEquals(VerdictTone.NEUTRAL, verdict("evidence_failed", "support_foot_occluded").tone)
    }

    @Test fun `verdict classes keep pending review and missing evidence apart`() {
        assertEquals(VerdictClass.ACCEPTED, verdict("accepted", "count_worthy").verdictClass)
        assertEquals(VerdictClass.REJECTED, verdict("rejected", "balance_loss").verdictClass)
        assertEquals(VerdictClass.PENDING, verdict("pending_review", "awaiting_analysis").verdictClass)
        assertEquals(VerdictClass.UNJUDGEABLE, verdict("evidence_failed", "support_foot_occluded").verdictClass)
        assertEquals("support_foot_occluded", verdict("evidence_failed", "support_foot_occluded").reasonCode)
        // The compatibility default derives the class from the tone; a NEUTRAL tone never makes a sound class.
        assertEquals(VerdictClass.PENDING, MobileVerdictEvent("k", 1, "a", 1, VerdictTone.NEUTRAL, "r").verdictClass)
        assertEquals(VerdictClass.ACCEPTED, MobileVerdictEvent("k", 1, "a", 1, VerdictTone.ACCEPTED, "r").verdictClass)
    }

    @Test fun `server-shaped frame yields verdict, live status, counts and no cue together`() {
        val frame = overlayFrame()
        val verdict = ChallengeEventParser.parseVerdict(frame)!!
        assertEquals("kick-6", verdict.kickEventId)
        assertEquals(VerdictClass.UNJUDGEABLE, verdict.verdictClass)
        assertEquals(
            LiveAnalysisStatus(
                state = LiveAnalysisState.TRACKING,
                reasonCode = null,
                detail = "upright 9/12 body 12/12",
                practicePaused = true,
                sourceGeometry = LiveSourceGeometry(1280, 720, LiveSourceOrientation.LANDSCAPE),
                athleteRegion = LiveAthleteRegion(0.2, 0.05, 0.8, 0.95),
                stale = false,
            ),
            ChallengeEventParser.parseLiveAnalysis(frame),
        )
        assertEquals(SessionCounts(accepted = 3, rejected = 1, evidenceFailed = 2), ChallengeEventParser.parseSessionCounts(frame))
        assertEquals(41L, ChallengeEventParser.parseChallengeTotal(frame))
        assertNull(ChallengeEventParser.parseCue(frame))
    }

    @Test fun `live state and verdict state are each read only from their own object`() {
        val accepted = overlayFrame(latestVerdict = verdictJson("accepted", "practice_cycle_complete"), liveAnalysis = live("no_person"))
        assertEquals(VerdictClass.ACCEPTED, ChallengeEventParser.parseVerdict(accepted)!!.verdictClass)
        assertEquals(LiveAnalysisState.NO_PERSON, ChallengeEventParser.parseLiveAnalysis(accepted)!!.state)

        // Planted: latestVerdict.state precedes a liveAnalysis object that has no state of its own.
        // A first-occurrence reader would report "accepted" (or anything) as the live state.
        val stateless = overlayFrame(
            latestVerdict = verdictJson("accepted", "practice_cycle_complete"),
            liveAnalysis = """{"reasonCode":null,"detail":"feet 2/12","practicePaused":false,"sourceGeometry":null,"athleteRegion":null,"stale":false}""",
        )
        assertNull(ChallengeEventParser.parseLiveAnalysis(stateless))
        assertEquals(VerdictClass.ACCEPTED, ChallengeEventParser.parseVerdict(stateless)!!.verdictClass)

        // Planted: a verdict-shaped state inside latestVerdict with no liveAnalysis at all.
        assertNull(ChallengeEventParser.parseLiveAnalysis(overlayFrame(liveAnalysis = "null")))
        assertNull(ChallengeEventParser.parseLiveAnalysis(overlayFrame().replace(",\"liveAnalysis\":$LIVE_TRACKING", "")))

        // Vice versa: liveAnalysis first and a latestVerdict without a state gives no verdict.
        val liveFirst = """{"liveAnalysis":${live("tracking")},"latestVerdict":{"kickEventId":"kick-1","kickSequence":1,"adjudicationId":"adj-1","adjudicationSequence":1,"reasonCode":"x"}}"""
        assertNull(ChallengeEventParser.parseVerdict(liveFirst))
        assertEquals(LiveAnalysisState.TRACKING, ChallengeEventParser.parseLiveAnalysis(liveFirst)!!.state)
        // A live state word never becomes a verdict, and a verdict word never becomes a live state.
        assertNull(ChallengeEventParser.parseVerdict(overlayFrame(latestVerdict = verdictJson("tracking", "x"))))
        assertEquals(LiveAnalysisState.UNKNOWN, ChallengeEventParser.parseLiveAnalysis(overlayFrame(liveAnalysis = live("accepted")))!!.state)
    }

    @Test fun `stale flag makes the live state STALE whatever state says`() {
        val flagged = ChallengeEventParser.parseLiveAnalysis(
            overlayFrame(liveAnalysis = LIVE_TRACKING.replace("\"stale\":false", "\"stale\":true")),
        )!!
        assertEquals(LiveAnalysisState.STALE, flagged.state)
        assertTrue(flagged.stale)
        assertFalse(flagged.available)

        // The server's own stale projection keeps practicePaused, sourceGeometry and athleteRegion.
        val projected = ChallengeEventParser.parseLiveAnalysis(
            overlayFrame(
                liveAnalysis = """{"state":"stale","reasonCode":null,"detail":"","practicePaused":true,"sourceGeometry":{"width":720,"height":1280,"orientation":"portrait"},"athleteRegion":{"left":0.1,"top":0.02,"right":0.9,"bottom":0.98},"stale":true}""",
            ),
        )!!
        assertEquals(LiveAnalysisState.STALE, projected.state)
        assertTrue(projected.practicePaused)
        assertEquals(LiveSourceGeometry(720, 1280, LiveSourceOrientation.PORTRAIT), projected.sourceGeometry)
        assertEquals(LiveAthleteRegion(0.1, 0.02, 0.9, 0.98), projected.athleteRegion)
        assertEquals("Analysis status unavailable", phoneText(projected.state, projected.reasonCode))

        val notStale = ChallengeEventParser.parseLiveAnalysis(overlayFrame())!!
        assertTrue(notStale.available)
    }

    @Test fun `blocked status carries its reason and the contract phone text`() {
        val blocked = ChallengeEventParser.parseLiveAnalysis(
            overlayFrame(
                liveAnalysis = """{"state":"blocked","reasonCode":"waiting_for_new_capture_epoch","detail":"","practicePaused":false,"sourceGeometry":null,"athleteRegion":null,"stale":false}""",
            ),
        )!!
        assertEquals(LiveAnalysisState.BLOCKED, blocked.state)
        assertEquals(LiveBlockedReason.WAITING_FOR_NEW_CAPTURE_EPOCH, blocked.reasonCode)
        assertNull(blocked.sourceGeometry)
        assertNull(blocked.athleteRegion)
        assertEquals("Analysis stopped - tap Restart video", phoneText(blocked.state, blocked.reasonCode))

        val technique = ChallengeEventParser.parseLiveAnalysis(overlayFrame(liveAnalysis = live("blocked", reasonCode = "\"unsupported_practice_technique\"")))!!
        assertEquals("Automatic analysis supports Teep only", phoneText(technique.state, technique.reasonCode))
        val unknownReason = ChallengeEventParser.parseLiveAnalysis(overlayFrame(liveAnalysis = live("blocked", reasonCode = "\"new_reason\"")))!!
        assertEquals(LiveBlockedReason.UNKNOWN, unknownReason.reasonCode)
        assertEquals(LiveAnalysisState.UNKNOWN, ChallengeEventParser.parseLiveAnalysis(overlayFrame(liveAnalysis = live("dancing")))!!.state)
    }

    @Test fun `out-of-range geometry and region are dropped, not guessed`() {
        val status = ChallengeEventParser.parseLiveAnalysis(
            overlayFrame(
                liveAnalysis = """{"state":"tracking","reasonCode":null,"detail":"","practicePaused":false,"sourceGeometry":{"width":0,"height":720,"orientation":"landscape"},"athleteRegion":{"left":0.8,"top":0.05,"right":0.2,"bottom":0.95},"stale":false}""",
            ),
        )!!
        assertEquals(LiveAnalysisState.TRACKING, status.state)
        assertNull(status.sourceGeometry)
        assertNull(status.athleteRegion)
    }

    @Test fun `null or absent activeCue gives no cue even with live detail present`() {
        assertNull(ChallengeEventParser.parseCue(overlayFrame(activeCue = "null")))
        assertNull(ChallengeEventParser.parseCue(overlayFrame().replace("\"activeCue\":null,", "")))
        // Planted: the removed whole-payload fallback read a top-level id and text as a cue.
        assertNull(ChallengeEventParser.parseCue("{\"id\":\"cue-9\",\"text\":\"Stray top-level text\",\"priority\":1}"))
        assertNull(ChallengeEventParser.parseCue("{\"activeCue\":null,\"id\":\"cue-9\",\"text\":\"Stray top-level text\"}"))

        val cue = ChallengeEventParser.parseCue(
            overlayFrame(activeCue = """{"id":"cue-2","sessionId":"session-1","priority":2,"kind":"technical","text":"Say \"chamber\" {first}","createdAt":"2030-01-01T00:00:06.000Z","safeAfterKickEventId":"kick-6","status":"active"}"""),
        )!!
        assertEquals(AthleteCue("cue-2", "Say \"chamber\" {first}", 2, "technical", "kick-6"), cue)
    }

    @Test fun `session counts come from the top level only`() {
        // Planted: nested objects carrying the same keys appear BEFORE the top-level counts.
        val nestedFirst = """{"liveAnalysis":{"state":"tracking","accepted":99,"rejected":98,"evidenceFailed":97},""" +
            """"latestVerdict":{"accepted":96,"rejected":95,"evidenceFailed":94},""" +
            """"sourceHealth":{"accepted":93},"activeCue":{"accepted":92},"currentBlock":{"accepted":91},""" +
            """"accepted":4,"rejected":0,"evidenceFailed":7}"""
        assertEquals(SessionCounts(accepted = 4, rejected = 0, evidenceFailed = 7), ChallengeEventParser.parseSessionCounts(nestedFirst))
        // A count present only inside a nested object is not a session count.
        assertNull(ChallengeEventParser.parseSessionCounts("""{"liveAnalysis":{"accepted":1},"rejected":0,"evidenceFailed":0}"""))
        assertNull(ChallengeEventParser.parseSessionCounts(overlayFrame(accepted = -1)))
        assertEquals(SessionCounts(0, 0, 0), ChallengeEventParser.parseSessionCounts(overlayFrame(accepted = 0, rejected = 0, evidenceFailed = 0)))
    }

    @Test fun `braces and quotes inside strings do not shift the object boundaries`() {
        // sourceHealth.detail is free text from the phone; it precedes the counts and liveAnalysis.
        val frame = overlayFrame(sourceHealthDetail = "\"closing } brace, opening { brace, quote \\\" and \\u007d\"")
        assertEquals(SessionCounts(3, 1, 2), ChallengeEventParser.parseSessionCounts(frame))
        assertEquals(LiveAnalysisState.TRACKING, ChallengeEventParser.parseLiveAnalysis(frame)!!.state)
        assertEquals(VerdictClass.UNJUDGEABLE, ChallengeEventParser.parseVerdict(frame)!!.verdictClass)
    }

    @Test fun `malformed frames yield nothing`() {
        val frame = overlayFrame()
        for (bad in listOf(frame.dropLast(1), "$frame}", "$frame x", "[$frame]", "", "null", frame.replace(":3,", ":03,"))) {
            assertNull(bad, ChallengeEventParser.parseVerdict(bad))
            assertNull(bad, ChallengeEventParser.parseLiveAnalysis(bad))
            assertNull(bad, ChallengeEventParser.parseSessionCounts(bad))
            assertNull(bad, ChallengeEventParser.parseChallengeTotal(bad))
        }
    }

    @Test fun `parses canonical cue with original kick safe window`() {
        val cue = ChallengeEventParser.parseCue("{\"sessionId\":\"s-1\",\"officialAcceptedCount\":2,\"activeCue\":{\"id\":\"cue-1\",\"text\":\"Re-chamber before putting it down.\",\"priority\":3,\"kind\":\"technical\",\"safeAfterKickEventId\":\"kick-7\"}}")!!
        assertEquals("kick-7", cue.safeAfterKickEventId)
        assertEquals(3, cue.priority)
        assertEquals("Re-chamber before putting it down.", cue.text)
    }

    @Test fun `ignores unknown envelope rather than inventing feedback`() {
        assertNull(ChallengeEventParser.parseVerdict("{\"state\":\"optimistic\"}"))
        assertNull(ChallengeEventParser.parseCue("{\"state\":\"accepted\"}"))
    }

    @Test fun `preserves delayed original kick attribution through manual supersession`() {
        val parsed = ChallengeEventParser.parseVerdict(
            "{\"latestVerdict\":{\"kickEventId\":\"kick-original-7\",\"kickSequence\":7,\"adjudicationId\":\"manual-superseding-19\",\"adjudicationSequence\":19,\"state\":\"accepted\",\"reasonCode\":\"manual_confirmed\"}}",
        )!!
        assertEquals("kick-original-7", parsed.kickEventId)
        assertEquals(7, parsed.kickSequence)
        assertEquals("manual-superseding-19", parsed.adjudicationId)
        assertEquals(19, parsed.adjudicationSequence)
    }

    private fun verdict(state: String, reason: String) = ChallengeEventParser.parseVerdict(
        "{\"latestVerdict\":{\"kickEventId\":\"kick-1\",\"kickSequence\":1,\"adjudicationId\":\"adj-1\",\"adjudicationSequence\":1,\"state\":\"$state\",\"reasonCode\":\"$reason\"}}",
    )!!

    private fun verdictJson(state: String, reason: String) =
        """{"kickEventId":"kick-6","kickSequence":6,"adjudicationId":"adj-6","adjudicationSequence":9,"state":"$state","reasonCode":"$reason","occurredAt":"2030-01-01T00:00:04.000Z","adjudicatedAt":"2030-01-01T00:00:05.000Z"}"""

    private fun live(state: String, reasonCode: String = "null") =
        """{"state":"$state","reasonCode":$reasonCode,"detail":"","practicePaused":false,"sourceGeometry":null,"athleteRegion":null,"stale":false}"""

    /**
     * One session-stream data frame as clean-reps sends it: `ledger.overlay(sessionId)`
     * (OverlayState, keys in server order) plus the top-level `liveAnalysis`
     * projection, with no wrapper. Values are synthetic.
     */
    private fun overlayFrame(
        accepted: Int = 3,
        rejected: Int = 1,
        evidenceFailed: Int = 2,
        sourceHealthDetail: String = "\"phone preview ok\"",
        activeCue: String = "null",
        latestVerdict: String = verdictJson("evidence_failed", "support_foot_occluded"),
        liveAnalysis: String = LIVE_TRACKING,
    ) = """{"sessionId":"session-1","challengeId":"challenge-1","challengeOfficialAcceptedCount":41,"officialAcceptedCount":0,""" +
        """"attempts":6,"accepted":$accepted,"rejected":$rejected,"pendingReview":0,"evidenceFailed":$evidenceFailed,""" +
        """"currentBlock":{"id":"block-1","sessionId":"session-1","sequence":1,"technique":"teep","side":"right","targetContext":"hanging_bag",""" +
        """"intent":"challenge_counting","cameraProfile":"fixed_full_body_oblique_v1","startedAt":"2030-01-01T00:00:00.000Z","reacquisition":"ready","practiceState":"active"},""" +
        """"sourceHealth":{"captureSessionId":"capture-1","status":"healthy","detail":$sourceHealthDetail,"observedAt":"2030-01-01T00:00:03.000Z"},""" +
        """"activeCue":$activeCue,"latestVerdict":$latestVerdict,"latestSettledKickEventId":"kick-6","analysisLag":"ready",""" +
        """"liveAnalysis":$liveAnalysis}"""

    private companion object {
        /** Copied from the clean-reps session-stream test (challenge-snapshot-stream.test.ts). */
        const val LIVE_TRACKING = """{"state":"tracking","reasonCode":null,"detail":"upright 9/12 body 12/12","practicePaused":true,""" +
            """"sourceGeometry":{"width":1280,"height":720,"orientation":"landscape"},"athleteRegion":{"left":0.2,"top":0.05,"right":0.8,"bottom":0.95},"stale":false}"""
    }
}
