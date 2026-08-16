package com.vaylith.cleanrepsmobile.model

/** Mirrors clean-reps million-kicks-launch.v1.json; official count is server-owned. */
enum class KickSide { RIGHT, LEFT }
enum class CaptureReadiness { NOT_CONFIGURED, PUBLISHER_UNAVAILABLE, CONNECTING, LIVE, RECONNECTING, STOPPED, ERROR }
enum class VerdictTone { ACCEPTED, REJECTED, NEUTRAL }

data class BlockSelection(
    val technique: String = "side_kick",
    val side: KickSide = KickSide.RIGHT,
    val targetContext: String = "bag",
    val intent: String = "challenge_counting",
    val cameraProfile: String = "fixed_full_body_oblique_v1",
)

/**
 * Capture epochs are monotonically increasing, non-negative integers. This is
 * intentionally compatible with the server's CaptureSession contract; a UUID
 * is not valid sourceEpoch wire data.
 */
data class SourceEpoch(val value: Long = 0) {
    init { require(value >= 0) { "source epoch must be non-negative" } }
    fun next(): SourceEpoch = SourceEpoch(value + 1)
    val displayId: String get() = value.toString()
}

/**
 * A manual alignment fallback references only the already-recording canonical
 * source interval. It never asserts pose visibility and therefore enters the
 * server ledger as evidence_failed until a human reviews the raw recording.
 */
data class ManualEvidenceWindow(val startMs: Long, val endMs: Long) {
    init {
        require(startMs >= 0) { "manual evidence start must be non-negative" }
        require(endMs >= startMs) { "manual evidence end must follow start" }
    }
}

fun manualEvidenceWindow(elapsedMs: Long, preRollMs: Long = 2_500): ManualEvidenceWindow {
    require(elapsedMs >= 0) { "capture elapsed time must be non-negative" }
    require(preRollMs >= 0) { "manual evidence pre-roll must be non-negative" }
    return ManualEvidenceWindow(startMs = (elapsedMs - preRollMs).coerceAtLeast(0), endMs = elapsedMs)
}

data class AthleteCue(
    val id: String,
    val text: String,
    val priority: Int,
    val kind: String,
    val safeAfterKickEventId: String?,
)

data class AppState(
    val sessionId: String? = null,
    val blockId: String? = null,
    val blockReady: Boolean = false,
    val captureId: String? = null,
    val captureStartedAtElapsedMs: Long? = null,
    val lastManualKickEventId: String? = null,
    val selection: BlockSelection = BlockSelection(),
    val epoch: SourceEpoch = SourceEpoch(),
    val readiness: CaptureReadiness = CaptureReadiness.NOT_CONFIGURED,
    val statusDetail: String = "Configure private API and real canonical publisher before official capture.",
    val debugSpeakVerdicts: Boolean = false,
    val activeCue: AthleteCue? = null,
)
