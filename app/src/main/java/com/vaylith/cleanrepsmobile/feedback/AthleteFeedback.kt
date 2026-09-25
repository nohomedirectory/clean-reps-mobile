package com.vaylith.cleanrepsmobile.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.vaylith.cleanrepsmobile.model.VerdictClass
import java.util.Locale

/** The sounds and speech a session plays: [AthleteFeedback] on the phone, a recorder in tests. */
interface AthleteSignals {
    /** Plays one FeedbackPolicy cue. A [FeedbackCue.Banner] is shown by the screen and never sounds. */
    fun cue(cue: FeedbackCue)

    /** Speaks a hint at once, replacing anything still being spoken. */
    fun speak(text: String)
}

/** Local signals only. Server remains the source of verdicts and structured CoachingCue events. */
class AthleteFeedback(context: Context) : TextToSpeech.OnInitListener, AthleteSignals {
    private val tones = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
    private val tts = TextToSpeech(context, this)
    private val pulseTimer = Handler(Looper.getMainLooper())
    private var ttsReady = false
    override fun onInit(status: Int) { ttsReady = status == TextToSpeech.SUCCESS; if (ttsReady) tts.language = Locale.US }

    override fun cue(cue: FeedbackCue) {
        if (cue is FeedbackCue.Speak) speak(cue.text) else play(CueTones.pulses(cue))
    }

    override fun speak(text: String) { if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cue") }

    /** Caller must enforce the safe post-kick window from the canonical cue event. */
    fun speakWhenSafe(text: String) = speak(text)
    fun audioTest() { cue(FeedbackCue.Accept); speakWhenSafe("Clean Reps audio test. Earbud ready.") }
    fun close() { pulseTimer.removeCallbacksAndMessages(null); tones.release(); tts.shutdown() }

    private fun play(pattern: List<TonePulse>) {
        if (pattern.isEmpty()) return
        // A new cue replaces the unplayed rest of the previous one.
        pulseTimer.removeCallbacksAndMessages(null)
        pattern.forEach { pulse ->
            val start = Runnable { tones.startTone(pulse.tone, pulse.durationMs) }
            if (pulse.atMs == 0L) start.run() else pulseTimer.postDelayed(start, pulse.atMs)
        }
    }
}

/** One `ToneGenerator` tone started [atMs] after its cue and cut after [durationMs]. */
data class TonePulse(val tone: Int, val atMs: Long, val durationMs: Int)

/**
 * The sound of each FeedbackPolicy cue, using the constants chosen in M3b. The
 * patterns come from the AOSP `ToneGenerator.cpp` tone table: `startTone` plays
 * a tone's own pattern and stops it after `durationMs` at the latest. The
 * cues differ in pitch or rhythm; audibility and distinctness on the phone are
 * device evidence (P7).
 */
object CueTones {
    /** TONE_PROP_BEEP2 is 40 ms on, 200 ms off, 40 ms on (400+1200 Hz); this length plays both beeps. */
    const val ACCEPT_MS = 280

    /** TONE_CDMA_LOW_L is a 1300/1450 Hz warble; one 150 ms burst. */
    const val REJECT_MS = 150

    /** TONE_PROP_PROMPT is one steady 400+1200 Hz tone of 200 ms; this cuts it to a short chirp. */
    const val READY_MS = 150

    /**
     * TONE_CDMA_LOW_SS is an 800 ms 1300/1450 Hz warble repeated after 400 ms gaps
     * without end, so each pip is a separate short start of it.
     */
    const val LOST_PIP_MS = 100
    const val LOST_SECOND_PIP_AT_MS = 250L

    fun pulses(cue: FeedbackCue): List<TonePulse> = when (cue) {
        FeedbackCue.Accept -> listOf(TonePulse(ToneGenerator.TONE_PROP_BEEP2, 0, ACCEPT_MS))
        FeedbackCue.Reject -> listOf(TonePulse(ToneGenerator.TONE_CDMA_LOW_L, 0, REJECT_MS))
        FeedbackCue.Ready -> listOf(TonePulse(ToneGenerator.TONE_PROP_PROMPT, 0, READY_MS))
        FeedbackCue.Lost -> listOf(
            TonePulse(ToneGenerator.TONE_CDMA_LOW_SS, 0, LOST_PIP_MS),
            TonePulse(ToneGenerator.TONE_CDMA_LOW_SS, LOST_SECOND_PIP_AT_MS, LOST_PIP_MS),
        )
        is FeedbackCue.Speak, is FeedbackCue.Banner -> emptyList()
    }

    /**
     * The sound of a verdict; null (silence) for PENDING and UNJUDGEABLE, which the
     * retired TONE_PROP_ACK used to sound. Used until FeedbackPolicy is wired (M6b).
     */
    fun forVerdict(verdict: VerdictClass): FeedbackCue? = when (verdict) {
        VerdictClass.ACCEPTED -> FeedbackCue.Accept
        VerdictClass.REJECTED -> FeedbackCue.Reject
        VerdictClass.PENDING, VerdictClass.UNJUDGEABLE -> null
    }
}
