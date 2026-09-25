package com.vaylith.cleanrepsmobile.feedback

import com.vaylith.cleanrepsmobile.model.LiveAnalysisState
import com.vaylith.cleanrepsmobile.model.LiveAnalysisStatus
import com.vaylith.cleanrepsmobile.model.VerdictClass
import com.vaylith.cleanrepsmobile.model.phoneText

/**
 * What the athlete hears or sees. The four tones are played by AthleteFeedback
 * (M5a) with these `android.media.ToneGenerator` constants, chosen to be told
 * apart by pitch and rhythm; audibility and distinctness are device evidence (P7):
 * - [Accept]: `TONE_PROP_BEEP2` for 280 ms, long enough for both beeps of its
 *   high double beep (40 ms on, 200 ms off, 40 ms on);
 * - [Reject]: `TONE_CDMA_LOW_L` for 150 ms, one low long tone (unchanged);
 * - [Ready]: `TONE_PROP_PROMPT`, one short chirp. It is deliberately not
 *   `TONE_PROP_ACK`, the retired neutral beep that the owner heard as counting;
 * - [Lost]: `TONE_CDMA_LOW_SS`, two short low pips.
 */
sealed interface FeedbackCue {
    data object Accept : FeedbackCue
    data object Reject : FeedbackCue
    data object Ready : FeedbackCue
    data object Lost : FeedbackCue
    data class Speak(val text: String) : FeedbackCue
    data class Banner(val text: String) : FeedbackCue
}

/**
 * Pure policy for verdict tones and the READY/LOST framing cues. Every input
 * carries a monotonic time (for example `SystemClock.elapsedRealtime()`).
 *
 * The session stream publishes only when the `liveAnalysis` projection changes,
 * so a steady state produces no further events. The controller therefore calls
 * [tick] periodically; every time-based cue fires from whichever input first
 * observes it due.
 *
 * READY and LOST sound only while practice is active, each at most once every
 * [CUE_INTERVAL_MS]. A re-entry READY held back by that limit sounds once the
 * limit allows, if tracking has continued. LOST repeats at that interval while
 * its condition holds. A stale or missing status suspends the time-based LOST;
 * unjudgeable verdicts drive it instead, after the same walk-back grace.
 */
class FeedbackPolicy(
    /** OD-4: speak the phone text of the current state together with LOST. */
    var voiceHints: Boolean = VOICE_HINTS_DEFAULT,
    /** Speak a short phrase for accepted and rejected verdicts. */
    var speakVerdicts: Boolean = false,
) {
    private var lastNowMs = Long.MIN_VALUE
    private var status: LiveAnalysisStatus? = null
    private var tracking = false
    private var trackingEndedAtMs: Long? = null

    private var practiceActive = false
    private var armedAtMs = 0L
    /** Tracking has been seen since the last Start or Resume, and that arm's own chirp has sounded. */
    private var trackingReached = false
    private var readyPending = false

    private var lastReadyAtMs: Long? = null
    private var lastLostAtMs: Long? = null

    fun onPracticeStarted(nowMs: Long): List<FeedbackCue> = arm(nowMs)

    fun onPracticeResumed(nowMs: Long): List<FeedbackCue> = arm(nowMs)

    fun onPracticePaused(nowMs: Long): List<FeedbackCue> = disarm(nowMs)

    fun onPracticeStopped(nowMs: Long): List<FeedbackCue> = disarm(nowMs)

    /** [status] is null when the session stream carries no `liveAnalysis`. */
    fun onStatus(status: LiveAnalysisStatus?, nowMs: Long): List<FeedbackCue> {
        val now = advance(nowMs)
        this.status = status
        val nowTracking = status != null && status.available && status.state == LiveAnalysisState.TRACKING
        if (nowTracking && !tracking) {
            val ended = trackingEndedAtMs
            if (practiceActive && trackingReached && ended != null && now - ended >= READY_REENTRY_MS) readyPending = true
        }
        if (!nowTracking && tracking) trackingEndedAtMs = now
        if (!nowTracking) readyPending = false
        tracking = nowTracking
        return evaluate(now)
    }

    fun onVerdict(verdict: VerdictClass, reasonCode: String?, nowMs: Long): List<FeedbackCue> {
        val now = advance(nowMs)
        val cues = mutableListOf<FeedbackCue>()
        when (verdict) {
            VerdictClass.ACCEPTED -> cues += FeedbackCue.Accept
            VerdictClass.REJECTED -> cues += FeedbackCue.Reject
            VerdictClass.PENDING -> cues += FeedbackCue.Banner(UNJUDGED_BANNER)
            VerdictClass.UNJUDGEABLE -> {
                cues += FeedbackCue.Banner(UNJUDGED_BANNER)
                // Fallback: without a usable status an unjudgeable verdict is the only
                // sign the athlete is not being seen. The live analyzer suppresses
                // fragments, so such a verdict is meaningful. The walk-back grace still
                // applies: a fragment while the athlete walks back from the phone is expected.
                if (practiceActive && !statusAvailable() && pastWalkBackGrace(now) && allowed(lastLostAtMs, now)) {
                    lastLostAtMs = now
                    cues += FeedbackCue.Lost
                }
            }
        }
        if (speakVerdicts) verdictPhrase(verdict, reasonCode)?.let { cues += FeedbackCue.Speak(it) }
        return cues + evaluate(now)
    }

    fun tick(nowMs: Long): List<FeedbackCue> = evaluate(advance(nowMs))

    private fun arm(nowMs: Long): List<FeedbackCue> {
        val now = advance(nowMs)
        practiceActive = true
        armedAtMs = now
        trackingReached = false
        readyPending = false
        return evaluate(now)
    }

    private fun disarm(nowMs: Long): List<FeedbackCue> {
        advance(nowMs)
        practiceActive = false
        readyPending = false
        return emptyList()
    }

    private fun evaluate(now: Long): List<FeedbackCue> {
        if (!practiceActive) return emptyList()
        val cues = mutableListOf<FeedbackCue>()
        if (tracking) {
            if (!trackingReached) {
                // Each Start or Resume has one chirp of its own, outside the rate limit.
                trackingReached = true
                readyPending = false
                lastReadyAtMs = now
                cues += FeedbackCue.Ready
            } else if (readyPending && allowed(lastReadyAtMs, now)) {
                readyPending = false
                lastReadyAtMs = now
                cues += FeedbackCue.Ready
            }
        } else if (statusAvailable() && lostDue(now) && allowed(lastLostAtMs, now)) {
            lastLostAtMs = now
            cues += FeedbackCue.Lost
            val current = status
            if (voiceHints && current != null) cues += FeedbackCue.Speak(phoneText(current.state, current.reasonCode))
        }
        return cues
    }

    private fun lostDue(now: Long): Boolean {
        if (!trackingReached) return pastWalkBackGrace(now)
        val ended = trackingEndedAtMs ?: return false
        return now - ended >= LOST_AFTER_MS
    }

    private fun pastWalkBackGrace(now: Long) = trackingReached || now - armedAtMs >= WALK_BACK_GRACE_MS

    private fun statusAvailable() = status?.available == true

    private fun allowed(lastAtMs: Long?, now: Long) = lastAtMs == null || now - lastAtMs >= CUE_INTERVAL_MS

    /** Times never move backwards; an out-of-order input is treated as happening now. */
    private fun advance(nowMs: Long): Long {
        if (nowMs > lastNowMs) lastNowMs = nowMs
        return lastNowMs
    }

    companion object {
        /** OD-4 default: spoken hints are on, with a toggle. */
        const val VOICE_HINTS_DEFAULT = true
        /** OD-4 default: READY and LOST each sound at most once in this interval. */
        const val CUE_INTERVAL_MS = 15_000L
        const val READY_REENTRY_MS = 3_000L
        const val LOST_AFTER_MS = 2_000L
        /** The athlete taps Start or Resume at the phone, 3-4 m from the kicking spot. */
        const val WALK_BACK_GRACE_MS = 10_000L
        const val UNJUDGED_BANNER = "Couldn't judge that one - keep head and feet in view"

        /** Phrases for "Speak verdicts". Other codes, and verdicts that are not accepted or rejected, stay silent. */
        fun verdictPhrase(verdict: VerdictClass, reasonCode: String?): String? = when {
            verdict == VerdictClass.ACCEPTED && reasonCode == "practice_cycle_complete" -> "Good teep"
            verdict == VerdictClass.REJECTED && reasonCode == "practice_no_extension" -> "No extension"
            else -> null
        }
    }
}
