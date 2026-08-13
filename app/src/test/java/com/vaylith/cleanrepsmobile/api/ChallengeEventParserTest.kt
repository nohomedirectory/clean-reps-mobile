package com.vaylith.cleanrepsmobile.api

import com.vaylith.cleanrepsmobile.model.VerdictTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChallengeEventParserTest {
    @Test fun `maps verdict semantics without confusing missing evidence and bad form`() {
        assertEquals(VerdictTone.ACCEPTED, verdict("accepted", "count_worthy").tone)
        assertEquals(VerdictTone.REJECTED, verdict("rejected", "balance_loss").tone)
        assertEquals(VerdictTone.NEUTRAL, verdict("pending_review", "awaiting_analysis").tone)
        assertEquals(VerdictTone.NEUTRAL, verdict("evidence_failed", "support_foot_occluded").tone)
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
}
