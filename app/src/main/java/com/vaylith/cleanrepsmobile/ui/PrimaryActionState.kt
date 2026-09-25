package com.vaylith.cleanrepsmobile.ui

import com.vaylith.cleanrepsmobile.model.CaptureReadiness
import com.vaylith.cleanrepsmobile.model.LiveAnalysisState
import com.vaylith.cleanrepsmobile.model.LiveAnalysisStatus
import com.vaylith.cleanrepsmobile.model.LiveBlockedReason
import com.vaylith.cleanrepsmobile.model.phoneText

/** The actions of the single primary button, in the order the athlete meets them. */
enum class PrimaryAction(val label: String) {
    SET_UP_CONNECTION("Set up connection"),
    ALLOW_CAMERA("Allow camera"),
    GO_LIVE("Go live"),
    CONNECTING("Connecting..."),
    START_PRACTICE("Start practice"),
    PAUSE("Pause"),
    RESUME("Resume"),
    RESTART_VIDEO("Restart video"),
}

/** Practice as the button sees it: no block yet, a running block, or a paused block. */
enum class PracticeState {
    NOT_STARTED,
    ACTIVE,
    PAUSED;

    companion object {
        fun of(practiceActive: Boolean, hasBlock: Boolean): PracticeState = when {
            practiceActive -> ACTIVE
            hasBlock -> PAUSED
            else -> NOT_STARTED
        }
    }
}

data class PrimaryActionInputs(
    val readiness: CaptureReadiness,
    val practice: PracticeState,
    /** A server or video request started by the button has not finished. */
    val inFlight: Boolean,
    /** The private connection settings are complete. */
    val configured: Boolean,
    /** Camera and microphone permissions are granted. */
    val permission: Boolean,
    /**
     * The live video is attached to the session as a capture; practice needs one. While an attach
     * is running, report [inFlight] too: a live video with no capture and nothing in flight means
     * the attach failed, and the button offers Restart video.
     */
    val captureAttached: Boolean,
    /** The session stream's `liveAnalysis` projection, or null when none has arrived. */
    val liveAnalysis: LiveAnalysisStatus?,
)

/**
 * What the primary button shows. [reason] is the one-line reason for a disabled button and is
 * null when it is enabled. [hint] is the live-analysis line under the button, and [hintAction]
 * is the one-tap action the hint offers (only Restart video).
 */
data class PrimaryActionState(
    val action: PrimaryAction,
    val enabled: Boolean,
    val reason: String?,
    val hint: String? = null,
    val hintAction: PrimaryAction? = null,
) {
    companion object {
        const val WAITING_FOR_SERVER = "Waiting for the server to answer"
        const val CONNECTING_REASON = "Connecting to the video server"
        const val RECONNECTING_REASON = "Video is reconnecting - wait, or tap Stop video"
        const val ATTACHING_REASON = "Linking the video to this session"

        /** Live-analysis blocks that only a new capture can clear. */
        private val RESTART_REASONS = setOf(
            LiveBlockedReason.WAITING_FOR_NEW_CAPTURE_EPOCH,
            LiveBlockedReason.WORKER_RETRY_LIMIT,
            LiveBlockedReason.AMBIGUOUS_ACTIVE_SESSIONS,
        )

        /**
         * The button for [inputs]. `liveAnalysis` is advisory: it never changes the action, never
         * disables Start practice or Resume and never asks for a confirmation. It only sets the
         * hint, and offers Restart video when analysis is blocked for a reason a new capture clears.
         */
        fun from(inputs: PrimaryActionInputs): PrimaryActionState {
            val button = button(inputs)
            val live = inputs.readiness == CaptureReadiness.LIVE
            val status = inputs.liveAnalysis?.takeIf { live } ?: return button
            val hint = if (status.available) phoneText(status.state, status.reasonCode) else phoneText(LiveAnalysisState.STALE)
            val offersRestart = status.available &&
                status.state == LiveAnalysisState.BLOCKED &&
                status.reasonCode in RESTART_REASONS
            return button.copy(hint = hint, hintAction = if (offersRestart) PrimaryAction.RESTART_VIDEO else null)
        }

        private fun button(inputs: PrimaryActionInputs): PrimaryActionState = when (inputs.readiness) {
            CaptureReadiness.CONNECTING, CaptureReadiness.RECONNECTING ->
                if (inputs.practice == PracticeState.ACTIVE) {
                    waitable(PrimaryAction.PAUSE, inputs.inFlight)
                } else {
                    val reason = if (inputs.readiness == CaptureReadiness.CONNECTING) CONNECTING_REASON else RECONNECTING_REASON
                    PrimaryActionState(PrimaryAction.CONNECTING, enabled = false, reason = reason)
                }
            CaptureReadiness.LIVE -> when {
                inputs.practice == PracticeState.ACTIVE -> waitable(PrimaryAction.PAUSE, inputs.inFlight)
                !inputs.captureAttached && !inputs.inFlight ->
                    // Attaching the reconnected video failed; a new video start makes a new capture.
                    PrimaryActionState(PrimaryAction.RESTART_VIDEO, enabled = true, reason = null)
                else -> {
                    val action = if (inputs.practice == PracticeState.PAUSED) PrimaryAction.RESUME else PrimaryAction.START_PRACTICE
                    when {
                        !inputs.captureAttached -> PrimaryActionState(action, enabled = false, reason = ATTACHING_REASON)
                        else -> waitable(action, inputs.inFlight)
                    }
                }
            }
            // Not live: set up, allow the camera, then start the video.
            CaptureReadiness.NOT_CONFIGURED,
            CaptureReadiness.PUBLISHER_UNAVAILABLE,
            CaptureReadiness.STOPPED,
            CaptureReadiness.ERROR -> when {
                !inputs.configured -> waitable(PrimaryAction.SET_UP_CONNECTION, inputs.inFlight)
                !inputs.permission -> waitable(PrimaryAction.ALLOW_CAMERA, inputs.inFlight)
                // After a stopped or failed video the same start is offered as Restart video.
                inputs.readiness == CaptureReadiness.STOPPED || inputs.readiness == CaptureReadiness.ERROR ->
                    waitable(PrimaryAction.RESTART_VIDEO, inputs.inFlight)
                else -> waitable(PrimaryAction.GO_LIVE, inputs.inFlight)
            }
        }

        private fun waitable(action: PrimaryAction, inFlight: Boolean) =
            if (inFlight) {
                PrimaryActionState(action, enabled = false, reason = WAITING_FOR_SERVER)
            } else {
                PrimaryActionState(action, enabled = true, reason = null)
            }
    }
}
