package com.vaylith.cleanrepsmobile.model

import java.util.UUID

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

data class SourceEpoch(val id: String = UUID.randomUUID().toString())

data class AthleteCue(
    val id: String,
    val text: String,
    val priority: String,
    val kind: String,
    val safeAfterKickEventId: String?,
)

data class AppState(
    val sessionId: String? = null,
    val blockId: String? = null,
    val captureId: String? = null,
    val selection: BlockSelection = BlockSelection(),
    val epoch: SourceEpoch = SourceEpoch(),
    val readiness: CaptureReadiness = CaptureReadiness.NOT_CONFIGURED,
    val statusDetail: String = "Configure private API and real canonical publisher before official capture.",
    val debugSpeakVerdicts: Boolean = false,
    val activeCue: AthleteCue? = null,
)
