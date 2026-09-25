package com.vaylith.cleanrepsmobile.model

/**
 * Verdict classes for athlete feedback. PENDING (`pending_review`) and
 * UNJUDGEABLE (`evidence_failed`) make no verdict sound.
 */
enum class VerdictClass { ACCEPTED, REJECTED, PENDING, UNJUDGEABLE }

/**
 * C3 live-analysis states with their wire names. The clean-reps launch contract
 * (`million-kicks-launch.v1.json`, liveAnalysis) owns this vocabulary. [UNKNOWN]
 * stands for any state this app version does not recognise.
 */
enum class LiveAnalysisState(val wireName: String?) {
    STARTING("starting"),
    ACQUIRING("acquiring"),
    TRACKING("tracking"),
    NO_PERSON("no_person"),
    SIDEWAYS("sideways"),
    HEAD_CUT("head_cut"),
    FEET_CUT("feet_cut"),
    TOO_SMALL("too_small"),
    MULTIPLE_PEOPLE("multiple_people"),
    BLOCKED("blocked"),
    STALE("stale"),
    UNKNOWN(null);

    companion object {
        fun fromWire(value: String?): LiveAnalysisState =
            entries.firstOrNull { it.wireName != null && it.wireName == value } ?: UNKNOWN
    }
}

/** The `reasonCode` of a `blocked` state. */
enum class LiveBlockedReason(val wireName: String?) {
    UNSUPPORTED_PRACTICE_TECHNIQUE("unsupported_practice_technique"),
    WAITING_FOR_NEW_CAPTURE_EPOCH("waiting_for_new_capture_epoch"),
    WORKER_RETRY_LIMIT("worker_retry_limit"),
    SOURCE_GEOMETRY_UNSUPPORTED("source_geometry_unsupported"),
    AMBIGUOUS_ACTIVE_SESSIONS("ambiguous_active_sessions"),
    UNKNOWN(null);

    companion object {
        fun fromWire(value: String?): LiveBlockedReason =
            entries.firstOrNull { it.wireName != null && it.wireName == value } ?: UNKNOWN
    }
}

enum class LiveSourceOrientation(val wireName: String?) {
    PORTRAIT("portrait"),
    LANDSCAPE("landscape"),
    UNKNOWN(null);

    companion object {
        fun fromWire(value: String?): LiveSourceOrientation =
            entries.firstOrNull { it.wireName != null && it.wireName == value } ?: UNKNOWN
    }
}

/** The transmitted frame as the analyzer decodes it. */
data class LiveSourceGeometry(val width: Int, val height: Int, val orientation: LiveSourceOrientation)

/** The analyzer's athlete region, as fractions of the frame. */
data class LiveAthleteRegion(val left: Double, val top: Double, val right: Double, val bottom: Double)

/** The session stream's `liveAnalysis` projection. It is advisory and never a verdict. */
data class LiveAnalysisStatus(
    val state: LiveAnalysisState,
    val reasonCode: LiveBlockedReason? = null,
    val detail: String = "",
    val practicePaused: Boolean = false,
    val sourceGeometry: LiveSourceGeometry? = null,
    val athleteRegion: LiveAthleteRegion? = null,
    val stale: Boolean = false,
) {
    /** False when the server has marked the status stale. */
    val available: Boolean get() = !stale && state != LiveAnalysisState.STALE
}

/** The one owner-facing text for a live-analysis state, shown on the chip and spoken as a hint. */
fun phoneText(state: LiveAnalysisState, reason: LiveBlockedReason? = null): String = when (state) {
    LiveAnalysisState.STARTING -> "Starting analysis..."
    LiveAnalysisState.ACQUIRING -> "Finding you..."
    LiveAnalysisState.TRACKING -> "Tracking you"
    LiveAnalysisState.NO_PERSON -> "Step into the frame"
    LiveAnalysisState.SIDEWAYS -> "Video is sideways - stop and restart video"
    LiveAnalysisState.HEAD_CUT -> "Head not visible - move the phone back or higher"
    LiveAnalysisState.FEET_CUT -> "Feet not visible - move the phone back or lower"
    LiveAnalysisState.TOO_SMALL -> "Too far - move closer"
    LiveAnalysisState.MULTIPLE_PEOPLE -> "More than one person in view"
    LiveAnalysisState.BLOCKED -> when (reason) {
        LiveBlockedReason.UNSUPPORTED_PRACTICE_TECHNIQUE -> "Automatic analysis supports Teep only"
        LiveBlockedReason.WAITING_FOR_NEW_CAPTURE_EPOCH,
        LiveBlockedReason.WORKER_RETRY_LIMIT -> "Analysis stopped - tap Restart video"
        LiveBlockedReason.SOURCE_GEOMETRY_UNSUPPORTED -> "Video format not supported"
        LiveBlockedReason.AMBIGUOUS_ACTIVE_SESSIONS -> "Another Clean Reps session is still open - tap Restart video"
        // No action is suggested when the app does not know why analysis stopped.
        LiveBlockedReason.UNKNOWN, null -> "Analysis stopped"
    }
    LiveAnalysisState.STALE -> "Analysis status unavailable"
    LiveAnalysisState.UNKNOWN -> "Analysis status unknown"
}
