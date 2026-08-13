package com.vaylith.cleanrepsmobile.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import com.vaylith.cleanrepsmobile.model.VerdictTone
import java.util.Locale

/** Local signals only. Server remains the source of verdicts and structured CoachingCue events. */
class AthleteFeedback(context: Context) : TextToSpeech.OnInitListener {
    private val tones = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
    private val tts = TextToSpeech(context, this)
    private var ttsReady = false
    override fun onInit(status: Int) { ttsReady = status == TextToSpeech.SUCCESS; if (ttsReady) tts.language = Locale.US }
    fun verdict(tone: VerdictTone) = when (tone) {
        VerdictTone.ACCEPTED -> tones.startTone(ToneGenerator.TONE_PROP_BEEP2, 90)
        VerdictTone.REJECTED -> tones.startTone(ToneGenerator.TONE_CDMA_LOW_L, 150)
        VerdictTone.NEUTRAL -> tones.startTone(ToneGenerator.TONE_PROP_ACK, 70)
    }
    /** Caller must enforce the safe post-kick window from the canonical cue event. */
    fun speakWhenSafe(text: String) { if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cue") }
    fun audioTest() { verdict(VerdictTone.ACCEPTED); speakWhenSafe("Clean Reps audio test. Earbud ready.") }
    fun close() { tones.release(); tts.shutdown() }
}
