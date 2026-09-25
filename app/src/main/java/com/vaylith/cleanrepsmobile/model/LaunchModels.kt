package com.vaylith.cleanrepsmobile.model

/** Mirrors clean-reps million-kicks-launch.v1.json; official count is server-owned. */
/** Only [autoJudged] techniques are analysed live; the others are shown but not selectable this round. */
enum class KickTechnique(val wireValue: String, val label: String, val autoJudged: Boolean) {
    TEEP("teep", "Teep", autoJudged = true),
    ROUNDHOUSE("roundhouse", "Roundhouse", autoJudged = false),
    SIDE_KICK("side_kick", "Side kick", autoJudged = false),
}
enum class KickSide(val label: String) { RIGHT("Right"), LEFT("Left") }
enum class KickTarget(val wireValue: String, val label: String) {
    AIR("air", "Air"), STANDING_BAG("standing_bag", "Standing bag"), HANGING_BAG("hanging_bag", "Hanging bag"),
}
enum class TargetHeight(val wireValue: String, val label: String) {
    LOW("low", "Low"), MIDDLE("middle", "Middle"), HIGH("high", "High"),
}
enum class CaptureReadiness { NOT_CONFIGURED, PUBLISHER_UNAVAILABLE, CONNECTING, LIVE, RECONNECTING, STOPPED, ERROR }

data class BlockSelection(
    val technique: KickTechnique = KickTechnique.TEEP,
    val side: KickSide = KickSide.RIGHT,
    val targetContext: KickTarget = KickTarget.HANGING_BAG,
    val targetHeight: TargetHeight? = null,
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
