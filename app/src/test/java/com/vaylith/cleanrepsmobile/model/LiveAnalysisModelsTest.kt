package com.vaylith.cleanrepsmobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAnalysisModelsTest {
    private val stateTexts = mapOf(
        LiveAnalysisState.STARTING to "Starting analysis...",
        LiveAnalysisState.ACQUIRING to "Finding you...",
        LiveAnalysisState.TRACKING to "Tracking you",
        LiveAnalysisState.NO_PERSON to "Step into the frame",
        LiveAnalysisState.SIDEWAYS to "Video is sideways - stop and restart video",
        LiveAnalysisState.HEAD_CUT to "Head not visible - move the phone back or higher",
        LiveAnalysisState.FEET_CUT to "Feet not visible - move the phone back or lower",
        LiveAnalysisState.TOO_SMALL to "Too far - move closer",
        LiveAnalysisState.MULTIPLE_PEOPLE to "More than one person in view",
        LiveAnalysisState.STALE to "Analysis status unavailable",
        LiveAnalysisState.UNKNOWN to "Analysis status unknown",
    )
    private val blockedTexts = mapOf(
        LiveBlockedReason.UNSUPPORTED_PRACTICE_TECHNIQUE to "Automatic analysis supports Teep only",
        LiveBlockedReason.WAITING_FOR_NEW_CAPTURE_EPOCH to "Analysis stopped - tap Restart video",
        LiveBlockedReason.WORKER_RETRY_LIMIT to "Analysis stopped - tap Restart video",
        LiveBlockedReason.SOURCE_GEOMETRY_UNSUPPORTED to "Video format not supported",
        LiveBlockedReason.AMBIGUOUS_ACTIVE_SESSIONS to "Another Clean Reps session is still open - tap Restart video",
        LiveBlockedReason.UNKNOWN to "Analysis stopped",
    )

    @Test fun `every state has its pinned phone text`() {
        assertEquals(LiveAnalysisState.entries.toSet() - LiveAnalysisState.BLOCKED, stateTexts.keys)
        stateTexts.forEach { (state, text) -> assertEquals(text, phoneText(state)) }
        // A blocked reason never changes the text of any other state.
        stateTexts.forEach { (state, text) -> assertEquals(text, phoneText(state, LiveBlockedReason.WORKER_RETRY_LIMIT)) }
    }

    @Test fun `every blocked reason has its pinned phone text`() {
        assertEquals(LiveBlockedReason.entries.toSet(), blockedTexts.keys)
        blockedTexts.forEach { (reason, text) -> assertEquals(text, phoneText(LiveAnalysisState.BLOCKED, reason)) }
        assertEquals("Analysis stopped", phoneText(LiveAnalysisState.BLOCKED, null))
    }

    @Test fun `unknown states and reasons get safe generic texts`() {
        assertEquals("Analysis status unknown", phoneText(LiveAnalysisState.fromWire("warming_up")))
        val unknownReason = phoneText(LiveAnalysisState.BLOCKED, LiveBlockedReason.fromWire("new_server_reason"))
        assertEquals("Analysis stopped", unknownReason)
        assertFalse("an unknown reason must not suggest an action", unknownReason.contains("tap"))
    }

    @Test fun `texts use the launch contract's ASCII punctuation`() {
        (stateTexts.values + blockedTexts.values).forEach { text ->
            assertTrue("non-ASCII in '$text'", text.all { it.code in 0x20..0x7e })
        }
    }

    @Test fun `wire names match the C3 vocabulary and round-trip`() {
        assertEquals(
            listOf(
                "starting", "acquiring", "tracking", "no_person", "sideways", "head_cut", "feet_cut", "too_small",
                "multiple_people", "blocked", "stale",
            ),
            LiveAnalysisState.entries.mapNotNull { it.wireName },
        )
        LiveAnalysisState.entries.filter { it.wireName != null }
            .forEach { assertEquals(it, LiveAnalysisState.fromWire(it.wireName)) }
        assertEquals(LiveAnalysisState.UNKNOWN, LiveAnalysisState.fromWire(null))
        assertEquals(LiveAnalysisState.UNKNOWN, LiveAnalysisState.fromWire("TRACKING"))

        assertEquals(
            listOf(
                "unsupported_practice_technique", "waiting_for_new_capture_epoch", "worker_retry_limit",
                "source_geometry_unsupported", "ambiguous_active_sessions",
            ),
            LiveBlockedReason.entries.mapNotNull { it.wireName },
        )
        LiveBlockedReason.entries.filter { it.wireName != null }
            .forEach { assertEquals(it, LiveBlockedReason.fromWire(it.wireName)) }
        assertEquals(LiveBlockedReason.UNKNOWN, LiveBlockedReason.fromWire(null))

        assertEquals(LiveSourceOrientation.PORTRAIT, LiveSourceOrientation.fromWire("portrait"))
        assertEquals(LiveSourceOrientation.LANDSCAPE, LiveSourceOrientation.fromWire("landscape"))
        assertEquals(LiveSourceOrientation.UNKNOWN, LiveSourceOrientation.fromWire("square"))
    }

    @Test fun `a stale status is not available`() {
        assertTrue(LiveAnalysisStatus(LiveAnalysisState.NO_PERSON).available)
        assertFalse(LiveAnalysisStatus(LiveAnalysisState.NO_PERSON, stale = true).available)
        assertFalse(LiveAnalysisStatus(LiveAnalysisState.STALE, stale = true).available)
        assertFalse(LiveAnalysisStatus(LiveAnalysisState.STALE).available)
    }

    @Test fun `verdict classes are the four agreed classes`() {
        assertEquals(listOf("ACCEPTED", "REJECTED", "PENDING", "UNJUDGEABLE"), VerdictClass.entries.map { it.name })
    }
}
