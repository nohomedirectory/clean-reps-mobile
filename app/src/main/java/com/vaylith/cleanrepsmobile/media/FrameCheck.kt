package com.vaylith.cleanrepsmobile.media

import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticStep
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticsLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One Frame check. It asks the renderer for the next frame of the whole
 * transmitted picture and gives exactly one answer through [onMain]: the
 * frame, or null when nothing is being drawn, the request failed, or no frame
 * arrived within [TIMEOUT_MS] (scheduled with [afterTimeout]).
 *
 * In the app the frame is RootEncoder's `takePhoto` bitmap, drawn at the
 * encoder size with the stream orientation and no aspect mode, so it includes
 * what the filled preview crops. [F] keeps this logic free of Android types.
 * The capture callback may arrive on any thread.
 */
internal class FrameCheck<F : Any>(
    private val diagnostics: DiagnosticsLog?,
    private val onMain: (() -> Unit) -> Unit,
    private val afterTimeout: (() -> Unit) -> Unit,
    private val answer: (F?) -> Unit,
) {
    private val answered = AtomicBoolean(false)

    /**
     * [drawing]: the renderer is running (preview on or stream live); a frame is
     * only drawn then. [capture] asks it for one frame.
     */
    fun start(drawing: Boolean, capture: ((F?) -> Unit) -> Unit) {
        if (!drawing) {
            diagnostics?.info(DiagnosticStep.PREVIEW_START, "frame check: no frame is being drawn")
            reply(null)
            return
        }
        try {
            capture { frame -> reply(frame) }
        } catch (error: Exception) {
            diagnostics?.fail(DiagnosticStep.PREVIEW_START, "frame check failed", error)
            reply(null)
            return
        }
        afterTimeout {
            if (reply(null)) diagnostics?.fail(DiagnosticStep.PREVIEW_START, "frame check: no frame within $TIMEOUT_MS ms")
        }
    }

    /** True if this call gave the answer; every later call is ignored. */
    private fun reply(frame: F?): Boolean {
        if (!answered.compareAndSet(false, true)) return false
        onMain { answer(frame) }
        return true
    }

    companion object {
        const val TIMEOUT_MS = 3_000L
    }
}
