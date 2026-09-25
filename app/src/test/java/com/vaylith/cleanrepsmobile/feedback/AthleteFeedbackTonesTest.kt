package com.vaylith.cleanrepsmobile.feedback

import android.media.ToneGenerator
import com.vaylith.cleanrepsmobile.model.VerdictClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tone plan only. Pattern facts (on/off lengths, frequencies) are from the
 * AOSP ToneGenerator.cpp tone table; whether the cues are audible and told apart
 * on the phone is device evidence (P7), not something this test can show.
 */
class AthleteFeedbackTonesTest {
    private val soundingCues = listOf(FeedbackCue.Accept, FeedbackCue.Reject, FeedbackCue.Ready, FeedbackCue.Lost)

    @Test fun `each of the four cues uses the M3b constant`() {
        assertEquals(listOf(TonePulse(ToneGenerator.TONE_PROP_BEEP2, 0, 280)), CueTones.pulses(FeedbackCue.Accept))
        assertEquals(listOf(TonePulse(ToneGenerator.TONE_CDMA_LOW_L, 0, 150)), CueTones.pulses(FeedbackCue.Reject))
        assertEquals(listOf(TonePulse(ToneGenerator.TONE_PROP_PROMPT, 0, 150)), CueTones.pulses(FeedbackCue.Ready))
        assertEquals(
            listOf(TonePulse(ToneGenerator.TONE_CDMA_LOW_SS, 0, 100), TonePulse(ToneGenerator.TONE_CDMA_LOW_SS, 250, 100)),
            CueTones.pulses(FeedbackCue.Lost),
        )
    }

    @Test fun `the four cues differ in tone or rhythm`() {
        val signatures = soundingCues.map { cue -> CueTones.pulses(cue).map { Triple(it.tone, it.atMs, it.durationMs) } }
        assertEquals(4, signatures.toSet().size)
        // Same tone family, different rhythm: REJECT is one burst, LOST two separate pips.
        assertEquals(1, CueTones.pulses(FeedbackCue.Reject).size)
        assertEquals(2, CueTones.pulses(FeedbackCue.Lost).size)
        // Same 400+1200 Hz chord, different rhythm: ACCEPT keeps both 40 ms beeps (40 on, 200 off, 40 on),
        // READY is one steady chirp shorter than PROP_PROMPT's own 200 ms.
        assertTrue(CueTones.ACCEPT_MS >= 40 + 200 + 40)
        assertTrue(CueTones.READY_MS < 200)
    }

    @Test fun `LOST is two short pips, not the repeating 800 ms warble`() {
        val (first, second) = CueTones.pulses(FeedbackCue.Lost)
        assertTrue(first.durationMs < 800)
        assertTrue(second.durationMs < 800)
        // A silent gap separates the pips.
        assertTrue(second.atMs > first.atMs + first.durationMs)
    }

    @Test fun `neutral verdicts are silent and the retired neutral beep is gone`() {
        assertNull(CueTones.forVerdict(VerdictClass.PENDING))
        assertNull(CueTones.forVerdict(VerdictClass.UNJUDGEABLE))
        assertEquals(FeedbackCue.Accept, CueTones.forVerdict(VerdictClass.ACCEPTED))
        assertEquals(FeedbackCue.Reject, CueTones.forVerdict(VerdictClass.REJECTED))
        for (cue in soundingCues) {
            assertFalse(CueTones.pulses(cue).any { it.tone == ToneGenerator.TONE_PROP_ACK })
        }
    }

    @Test fun `speech and banners make no tone`() {
        assertTrue(CueTones.pulses(FeedbackCue.Speak("Step into the frame")).isEmpty())
        assertTrue(CueTones.pulses(FeedbackCue.Banner(FeedbackPolicy.UNJUDGED_BANNER)).isEmpty())
    }
}
