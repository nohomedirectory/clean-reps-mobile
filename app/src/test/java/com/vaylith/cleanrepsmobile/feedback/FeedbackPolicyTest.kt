package com.vaylith.cleanrepsmobile.feedback

import com.vaylith.cleanrepsmobile.model.LiveAnalysisState
import com.vaylith.cleanrepsmobile.model.LiveAnalysisStatus
import com.vaylith.cleanrepsmobile.model.VerdictClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackPolicyTest {
    private data class Event(val atMs: Long, val input: String, val cue: FeedbackCue)

    /** Drives one policy and records every cue with the input that produced it. */
    private class Run(val policy: FeedbackPolicy = FeedbackPolicy()) {
        val log = mutableListOf<Event>()

        private fun record(t: Long, input: String, cues: List<FeedbackCue>) = cues.forEach { log += Event(t, input, it) }

        fun start(t: Long) = record(t, "start", policy.onPracticeStarted(t))
        fun resume(t: Long) = record(t, "resume", policy.onPracticeResumed(t))
        fun pause(t: Long) = record(t, "pause", policy.onPracticePaused(t))
        fun stop(t: Long) = record(t, "stop", policy.onPracticeStopped(t))
        fun status(t: Long, state: LiveAnalysisState?, stale: Boolean = false) =
            record(t, "status", policy.onStatus(state?.let { LiveAnalysisStatus(it, stale = stale) }, t))
        fun verdict(t: Long, verdict: VerdictClass, reasonCode: String? = null) =
            record(t, "verdict", policy.onVerdict(verdict, reasonCode, t))

        /** Ticks every [stepMs] from [fromMs] to [toMs] inclusive. */
        fun ticks(fromMs: Long, toMs: Long, stepMs: Long = 250) {
            var t = fromMs
            while (t <= toMs) {
                record(t, "tick", policy.tick(t))
                t += stepMs
            }
        }

        fun times(cue: FeedbackCue) = log.filter { it.cue == cue }.map { it.atMs }
        fun between(fromMs: Long, toMs: Long) = log.filter { it.atMs in fromMs..toMs }
    }

    private val tones = setOf(FeedbackCue.Accept, FeedbackCue.Reject, FeedbackCue.Ready, FeedbackCue.Lost)

    /**
     * CONSTRUCTED gym timeline, as millisecond offsets from the first verdict (no
     * absolute times). The exact ledger offsets were not available to this bead,
     * so these reproduce the audited shape: 26 unjudgeable verdicts in 16 groups
     * from 0 to 41,598 ms, 10 of the groups being pairs 28-30 ms apart.
     */
    private val gymOffsetsMs = listOf(
        0L, 29L,
        2_214L,
        4_871L, 4_899L,
        7_530L, 7_560L,
        10_142L,
        12_806L, 12_835L,
        15_390L, 15_418L,
        18_027L,
        20_655L, 20_685L,
        23_318L, 23_347L,
        25_962L,
        28_577L, 28_605L,
        31_241L, 31_271L,
        33_870L,
        36_512L,
        41_568L, 41_598L,
    )

    @Test fun `constructed gym timeline has the audited shape`() {
        assertEquals(26, gymOffsetsMs.size)
        assertEquals(0L, gymOffsetsMs.first())
        assertEquals(41_598L, gymOffsetsMs.last())
        val groups = mutableListOf(mutableListOf(gymOffsetsMs.first()))
        gymOffsetsMs.zipWithNext().forEach { (previous, next) ->
            if (next - previous < 1_000) groups.last() += next else groups += mutableListOf(next)
        }
        assertEquals(16, groups.size)
        val pairs = groups.filter { it.size == 2 }
        assertEquals(10, pairs.size)
        assertEquals(6, groups.count { it.size == 1 })
        pairs.forEach { assertTrue("pair gap ${it[1] - it[0]}", it[1] - it[0] in 28..30) }
    }

    @Test fun `gym timeline replay in fallback mode gives no per-kick tones and at most 3 LOST cues`() {
        val run = Run()
        val firstVerdictAt = 5_000L
        run.start(0)
        // liveAnalysis never arrives, as with the server that ran at the gym.
        val verdictTimes = gymOffsetsMs.map { firstVerdictAt + it }.toMutableList()
        var t = 0L
        while (t <= firstVerdictAt + 60_000) {
            while (verdictTimes.isNotEmpty() && verdictTimes.first() <= t) {
                run.verdict(verdictTimes.removeAt(0), VerdictClass.UNJUDGEABLE, "evidence_failed")
            }
            run.ticks(t, t)
            t += 250
        }

        assertEquals(0, run.log.count { it.cue == FeedbackCue.Accept || it.cue == FeedbackCue.Reject || it.cue == FeedbackCue.Ready })
        val lost = run.times(FeedbackCue.Lost)
        assertTrue("LOST cues: $lost", lost.size <= 3)
        assertTrue("LOST inside the walk-back grace: $lost", lost.all { it >= FeedbackPolicy.WALK_BACK_GRACE_MS })
        lost.zipWithNext().forEach { (a, b) -> assertTrue("LOST $a then $b", b - a >= FeedbackPolicy.CUE_INTERVAL_MS) }
        // The first verdict after the grace (5,000 + 7,530), then the first verdicts at least
        // 15 s later (5,000 + 23,318 and 5,000 + 41,568).
        assertEquals(listOf(12_530L, 28_318L, 46_568L), lost)
        assertEquals(26, run.log.count { it.cue == FeedbackCue.Banner(FeedbackPolicy.UNJUDGED_BANNER) })
        assertEquals(0, run.log.count { it.cue is FeedbackCue.Speak })
    }

    @Test fun `tracking then 5 s of head_cut gives exactly one LOST`() {
        val run = Run()
        run.status(-500, LiveAnalysisState.TRACKING)
        run.start(0)
        run.ticks(0, 19_750)
        run.status(20_000, LiveAnalysisState.HEAD_CUT)
        run.ticks(20_000, 24_750)
        run.status(25_000, LiveAnalysisState.TRACKING)
        run.ticks(25_000, 45_000)

        assertEquals(listOf(22_000L), run.times(FeedbackCue.Lost))
        assertEquals(
            listOf(
                Event(22_000, "tick", FeedbackCue.Lost),
                Event(22_000, "tick", FeedbackCue.Speak("Head not visible - move the phone back or higher")),
            ),
            run.between(20_000, 24_750),
        )
    }

    @Test fun `tracking resumes after 3 s gives one READY`() {
        val run = Run()
        run.status(-500, LiveAnalysisState.TRACKING)
        run.start(0)
        run.ticks(0, 19_750)
        run.status(20_000, LiveAnalysisState.HEAD_CUT)
        run.ticks(20_000, 22_750)
        run.status(23_000, LiveAnalysisState.TRACKING)
        run.ticks(23_000, 29_750)
        // A return after only 2.75 s is not a re-entry.
        run.status(30_000, LiveAnalysisState.HEAD_CUT)
        run.ticks(30_000, 32_500)
        run.status(32_750, LiveAnalysisState.TRACKING)
        run.ticks(32_750, 60_000)

        assertEquals(listOf(0L, 23_000L), run.times(FeedbackCue.Ready))
        assertEquals(Event(23_000, "status", FeedbackCue.Ready), run.log.single { it.atMs == 23_000L })
    }

    @Test fun `already tracking at Start gives exactly one READY`() {
        val run = Run()
        run.status(-1_000, LiveAnalysisState.TRACKING)
        run.start(0)
        run.ticks(0, 60_000)
        assertEquals(listOf(Event(0, "start", FeedbackCue.Ready)), run.log)
    }

    @Test fun `Start while no_person with tracking from +6 s gives one READY and no LOST`() {
        val run = Run()
        run.status(-500, LiveAnalysisState.NO_PERSON)
        run.start(0)
        run.ticks(0, 5_750)
        run.status(6_000, LiveAnalysisState.TRACKING)
        run.ticks(6_000, 40_000)
        assertEquals(listOf(Event(6_000, "status", FeedbackCue.Ready)), run.log)
    }

    @Test fun `Start while no_person and never tracking gives one LOST at +10 s from ticks alone`() {
        val run = Run()
        run.status(-500, LiveAnalysisState.NO_PERSON)
        run.start(0)
        run.ticks(0, 24_750)
        assertEquals(
            listOf(
                Event(10_000, "tick", FeedbackCue.Lost),
                Event(10_000, "tick", FeedbackCue.Speak("Step into the frame")),
            ),
            run.log,
        )
        // The 15 s limit allows the next reminder at +25 s and not before.
        run.ticks(25_000, 39_750)
        assertEquals(listOf(10_000L, 25_000L), run.times(FeedbackCue.Lost))
    }

    @Test fun `Resume after a pause with a 4 s walk back gives no LOST`() {
        val run = Run()
        run.status(-500, LiveAnalysisState.TRACKING)
        run.start(0)
        run.ticks(0, 29_750)
        run.pause(30_000)
        run.status(30_500, LiveAnalysisState.NO_PERSON)
        run.ticks(30_500, 34_750)
        run.resume(35_000)
        run.ticks(35_000, 38_750)
        run.status(39_000, LiveAnalysisState.TRACKING)
        run.ticks(39_000, 60_000)

        assertEquals(emptyList<Long>(), run.times(FeedbackCue.Lost))
        assertEquals(listOf(0L, 39_000L), run.times(FeedbackCue.Ready))
    }

    @Test fun `flapping every 1 s gives no cues`() {
        val run = Run()
        run.status(-500, LiveAnalysisState.TRACKING)
        run.start(0)
        run.ticks(0, 19_750)
        for (second in 0 until 60) {
            val t = 20_000L + second * 1_000
            run.status(t, if (second % 2 == 0) LiveAnalysisState.NO_PERSON else LiveAnalysisState.TRACKING)
            run.ticks(t, t + 750)
        }
        assertEquals(listOf(Event(0, "start", FeedbackCue.Ready)), run.log)
    }

    @Test fun `no LOST before +10 s after Start or Resume unless tracking was reached`() {
        val notTracking = LiveAnalysisState.entries - LiveAnalysisState.TRACKING - LiveAnalysisState.STALE
        for (state in notTracking) {
            for (arm in listOf("start", "resume")) {
                val run = Run()
                run.status(-500, state)
                if (arm == "start") run.start(0) else run.resume(0)
                run.ticks(0, 14_750)
                assertEquals("$state after $arm", listOf(10_000L), run.times(FeedbackCue.Lost))
            }
        }
        // Once tracking has been reached, 2 s out of view is enough, even inside the grace.
        val reached = Run()
        reached.status(-500, LiveAnalysisState.NO_PERSON)
        reached.start(0)
        reached.status(1_000, LiveAnalysisState.TRACKING)
        reached.status(3_000, LiveAnalysisState.HEAD_CUT)
        reached.ticks(3_000, 9_750)
        assertEquals(listOf(5_000L), reached.times(FeedbackCue.Lost))
    }

    @Test fun `PENDING and UNJUDGEABLE make no tone while status is available`() {
        val run = Run(FeedbackPolicy(speakVerdicts = true))
        run.status(-500, LiveAnalysisState.TRACKING)
        run.start(0)
        for (index in 0 until 40) {
            val verdict = if (index % 2 == 0) VerdictClass.PENDING else VerdictClass.UNJUDGEABLE
            run.verdict(1_000L + index * 500, verdict, "practice_cycle_complete")
        }
        val fromVerdicts = run.log.filter { it.input == "verdict" }
        assertEquals(40, fromVerdicts.size)
        assertTrue(fromVerdicts.all { it.cue == FeedbackCue.Banner("Couldn't judge that one - keep head and feet in view") })
    }

    @Test fun `fallback LOST waits for the walk-back grace after Start and Resume`() {
        // No liveAnalysis status. An unjudgeable fragment while the athlete walks back is expected.
        val started = Run()
        started.start(0)
        started.verdict(2_000, VerdictClass.UNJUDGEABLE, "evidence_failed")
        started.ticks(2_000, 9_750)
        started.verdict(9_999, VerdictClass.UNJUDGEABLE, "evidence_failed")
        assertEquals(emptyList<Long>(), started.times(FeedbackCue.Lost))
        started.verdict(10_000, VerdictClass.UNJUDGEABLE, "evidence_failed")
        assertEquals(listOf(10_000L), started.times(FeedbackCue.Lost))

        val resumed = Run()
        resumed.start(0)
        resumed.pause(20_000)
        resumed.resume(30_000)
        resumed.verdict(32_000, VerdictClass.UNJUDGEABLE, "evidence_failed")
        resumed.verdict(40_000, VerdictClass.UNJUDGEABLE, "evidence_failed")
        assertEquals(listOf(40_000L), resumed.times(FeedbackCue.Lost))

        // Once tracking has been seen since the arm, the grace no longer holds back the fallback.
        val reached = Run()
        reached.status(-500, LiveAnalysisState.TRACKING)
        reached.start(0)
        reached.status(3_000, LiveAnalysisState.STALE, stale = true)
        reached.verdict(4_000, VerdictClass.UNJUDGEABLE, "evidence_failed")
        assertEquals(listOf(4_000L), reached.times(FeedbackCue.Lost))
    }

    @Test fun `PENDING never triggers the fallback LOST`() {
        val run = Run()
        run.start(0)
        for (index in 0 until 20) run.verdict(1_000L + index * 3_000, VerdictClass.PENDING, "pending_review")
        assertTrue(run.log.none { it.cue in tones })
    }

    @Test fun `accepted and rejected always sound, with phrases only when enabled`() {
        val policy = FeedbackPolicy()
        assertEquals(listOf(FeedbackCue.Accept), policy.onVerdict(VerdictClass.ACCEPTED, "practice_cycle_complete", 0))
        assertEquals(listOf(FeedbackCue.Accept), policy.onVerdict(VerdictClass.ACCEPTED, "practice_cycle_complete", 100))
        assertEquals(listOf(FeedbackCue.Reject), policy.onVerdict(VerdictClass.REJECTED, "practice_no_extension", 200))

        policy.speakVerdicts = true
        assertEquals(
            listOf(FeedbackCue.Accept, FeedbackCue.Speak("Good teep")),
            policy.onVerdict(VerdictClass.ACCEPTED, "practice_cycle_complete", 300),
        )
        assertEquals(
            listOf(FeedbackCue.Reject, FeedbackCue.Speak("No extension")),
            policy.onVerdict(VerdictClass.REJECTED, "practice_no_extension", 400),
        )
        assertEquals(listOf(FeedbackCue.Accept), policy.onVerdict(VerdictClass.ACCEPTED, "some_new_code", 500))
        val banner = FeedbackCue.Banner(FeedbackPolicy.UNJUDGED_BANNER)
        assertEquals(listOf(banner), policy.onVerdict(VerdictClass.PENDING, "practice_cycle_complete", 600))
        assertEquals(listOf(banner), policy.onVerdict(VerdictClass.UNJUDGEABLE, "practice_no_extension", 700))

        assertEquals("Good teep", FeedbackPolicy.verdictPhrase(VerdictClass.ACCEPTED, "practice_cycle_complete"))
        assertEquals("No extension", FeedbackPolicy.verdictPhrase(VerdictClass.REJECTED, "practice_no_extension"))
        assertEquals(null, FeedbackPolicy.verdictPhrase(VerdictClass.UNJUDGEABLE, "evidence_failed"))
        assertEquals(null, FeedbackPolicy.verdictPhrase(VerdictClass.PENDING, "practice_cycle_complete"))
    }

    @Test fun `a READY re-entry held back by the 15 s limit fires from a tick`() {
        val run = Run()
        run.status(-500, LiveAnalysisState.TRACKING)
        run.start(0)
        run.status(4_000, LiveAnalysisState.HEAD_CUT)
        run.ticks(4_000, 7_750)
        run.status(8_000, LiveAnalysisState.TRACKING)
        run.ticks(8_000, 30_000)

        assertEquals(listOf(0L, 15_000L), run.times(FeedbackCue.Ready))
        assertTrue(Event(15_000, "tick", FeedbackCue.Ready) in run.log)
        assertEquals(listOf(6_000L), run.times(FeedbackCue.Lost))

        // A held-back READY is dropped when tracking is lost before it may sound.
        val dropped = Run()
        dropped.status(-500, LiveAnalysisState.TRACKING)
        dropped.start(0)
        dropped.status(4_000, LiveAnalysisState.HEAD_CUT)
        dropped.status(8_000, LiveAnalysisState.TRACKING)
        dropped.status(9_000, LiveAnalysisState.HEAD_CUT)
        dropped.ticks(9_000, 30_000)
        assertEquals(listOf(0L), dropped.times(FeedbackCue.Ready))
    }

    @Test fun `LOST sounds at most once every 15 s`() {
        val run = Run()
        run.status(-500, LiveAnalysisState.TRACKING)
        run.start(0)
        run.ticks(0, 9_750)
        for (cycle in 0 until 12) {
            val t = 10_000L + cycle * 5_000
            run.status(t, LiveAnalysisState.HEAD_CUT)
            run.ticks(t, t + 2_250)
            run.status(t + 2_500, LiveAnalysisState.TRACKING)
            run.ticks(t + 2_500, t + 4_750)
        }
        assertEquals(listOf(12_000L, 27_000L, 42_000L, 57_000L), run.times(FeedbackCue.Lost))
        // Each return came after 2.5 s, short of the 3 s re-entry rule.
        assertEquals(listOf(0L), run.times(FeedbackCue.Ready))
    }

    @Test fun `each Start or Resume has one chirp of its own outside the rate limit`() {
        val run = Run()
        run.status(-500, LiveAnalysisState.TRACKING)
        run.start(0)
        run.pause(2_000)
        run.resume(4_000)
        run.pause(5_000)
        run.resume(6_000)
        run.status(7_000, LiveAnalysisState.HEAD_CUT)
        run.status(8_000, LiveAnalysisState.TRACKING)
        run.ticks(8_000, 30_000)
        assertEquals(
            listOf(Event(0, "start", FeedbackCue.Ready), Event(4_000, "resume", FeedbackCue.Ready), Event(6_000, "resume", FeedbackCue.Ready)),
            run.log.filter { it.cue == FeedbackCue.Ready },
        )
    }

    @Test fun `no cues while practice is paused or stopped`() {
        val paused = Run()
        paused.status(-500, LiveAnalysisState.NO_PERSON)
        paused.start(0)
        paused.pause(1_000)
        paused.ticks(1_000, 40_000)
        paused.status(41_000, LiveAnalysisState.TRACKING)
        paused.status(45_000, LiveAnalysisState.HEAD_CUT)
        paused.ticks(45_000, 70_000)
        assertEquals(emptyList<Event>(), paused.log)

        val stopped = Run()
        stopped.start(0)
        stopped.stop(1_000)
        stopped.verdict(5_000, VerdictClass.UNJUDGEABLE, "evidence_failed")
        stopped.ticks(5_000, 30_000)
        assertEquals(listOf(Event(5_000, "verdict", FeedbackCue.Banner(FeedbackPolicy.UNJUDGED_BANNER))), stopped.log)
    }

    @Test fun `a stale status suspends the time-based LOST and verdicts take over`() {
        val run = Run()
        run.status(-500, LiveAnalysisState.NO_PERSON)
        run.start(0)
        run.ticks(0, 4_750)
        run.status(5_000, LiveAnalysisState.STALE, stale = true)
        run.ticks(5_000, 19_750)
        assertEquals(emptyList<Long>(), run.times(FeedbackCue.Lost))

        run.verdict(20_000, VerdictClass.UNJUDGEABLE, "evidence_failed")
        run.status(21_000, LiveAnalysisState.NO_PERSON)
        run.ticks(21_000, 40_000)
        assertEquals(listOf(20_000L, 35_000L), run.times(FeedbackCue.Lost))
        assertEquals(listOf(35_000L), run.log.filter { it.cue is FeedbackCue.Speak }.map { it.atMs })
    }

    @Test fun `OD-4 defaults apply and voice hints can be turned off`() {
        assertTrue(FeedbackPolicy.VOICE_HINTS_DEFAULT)
        assertTrue(FeedbackPolicy().voiceHints)
        assertEquals(15_000L, FeedbackPolicy.CUE_INTERVAL_MS)

        val run = Run(FeedbackPolicy(voiceHints = false))
        run.status(-500, LiveAnalysisState.HEAD_CUT)
        run.start(0)
        run.ticks(0, 14_750)
        assertEquals(listOf(Event(10_000, "tick", FeedbackCue.Lost)), run.log)
    }
}
