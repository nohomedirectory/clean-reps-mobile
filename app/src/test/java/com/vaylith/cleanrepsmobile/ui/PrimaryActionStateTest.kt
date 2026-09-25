package com.vaylith.cleanrepsmobile.ui

import com.vaylith.cleanrepsmobile.model.CaptureReadiness
import com.vaylith.cleanrepsmobile.model.CaptureReadiness.CONNECTING
import com.vaylith.cleanrepsmobile.model.CaptureReadiness.ERROR
import com.vaylith.cleanrepsmobile.model.CaptureReadiness.LIVE
import com.vaylith.cleanrepsmobile.model.CaptureReadiness.NOT_CONFIGURED
import com.vaylith.cleanrepsmobile.model.CaptureReadiness.PUBLISHER_UNAVAILABLE
import com.vaylith.cleanrepsmobile.model.CaptureReadiness.RECONNECTING
import com.vaylith.cleanrepsmobile.model.CaptureReadiness.STOPPED
import com.vaylith.cleanrepsmobile.model.LiveAnalysisState
import com.vaylith.cleanrepsmobile.model.LiveAnalysisStatus
import com.vaylith.cleanrepsmobile.model.LiveBlockedReason
import com.vaylith.cleanrepsmobile.model.phoneText
import com.vaylith.cleanrepsmobile.ui.PracticeState.ACTIVE
import com.vaylith.cleanrepsmobile.ui.PracticeState.NOT_STARTED
import com.vaylith.cleanrepsmobile.ui.PracticeState.PAUSED
import com.vaylith.cleanrepsmobile.ui.PrimaryAction.ALLOW_CAMERA
import com.vaylith.cleanrepsmobile.ui.PrimaryAction.GO_LIVE
import com.vaylith.cleanrepsmobile.ui.PrimaryAction.PAUSE
import com.vaylith.cleanrepsmobile.ui.PrimaryAction.RESTART_VIDEO
import com.vaylith.cleanrepsmobile.ui.PrimaryAction.RESUME
import com.vaylith.cleanrepsmobile.ui.PrimaryAction.SET_UP_CONNECTION
import com.vaylith.cleanrepsmobile.ui.PrimaryAction.START_PRACTICE
import com.vaylith.cleanrepsmobile.ui.PrimaryActionState.Companion.ATTACHING_REASON
import com.vaylith.cleanrepsmobile.ui.PrimaryActionState.Companion.CONNECTING_REASON
import com.vaylith.cleanrepsmobile.ui.PrimaryActionState.Companion.RECONNECTING_REASON
import com.vaylith.cleanrepsmobile.ui.PrimaryActionState.Companion.WAITING_FOR_SERVER
import org.junit.Assert.*
import org.junit.Test

class PrimaryActionStateTest {
    private val restartReasons = setOf(
        LiveBlockedReason.WAITING_FOR_NEW_CAPTURE_EPOCH,
        LiveBlockedReason.WORKER_RETRY_LIMIT,
        LiveBlockedReason.AMBIGUOUS_ACTIVE_SESSIONS,
    )

    /** Every live-analysis value the phone can hold: none, each state, each blocked reason, stale. */
    private val liveAnalysisValues: List<LiveAnalysisStatus?> = buildList {
        add(null)
        for (state in LiveAnalysisState.entries) {
            if (state == LiveAnalysisState.BLOCKED) {
                for (reason in LiveBlockedReason.entries + listOf(null)) add(LiveAnalysisStatus(state, reason))
            } else {
                add(LiveAnalysisStatus(state))
            }
        }
        for (reason in restartReasons) add(LiveAnalysisStatus(LiveAnalysisState.BLOCKED, reason, stale = true))
        add(LiveAnalysisStatus(LiveAnalysisState.TRACKING, stale = true))
    }

    /** readiness x practice x inFlight x configured x permission x captureAttached x liveAnalysis. */
    private val table: List<PrimaryActionInputs> = buildList {
        for (readiness in CaptureReadiness.entries) for (practice in PracticeState.entries)
            for (inFlight in listOf(false, true)) for (configured in listOf(false, true))
                for (permission in listOf(false, true)) for (captureAttached in listOf(false, true))
                    for (liveAnalysis in liveAnalysisValues)
                        add(PrimaryActionInputs(readiness, practice, inFlight, configured, permission, captureAttached, liveAnalysis))
    }

    private fun inputs(
        readiness: CaptureReadiness,
        practice: PracticeState = NOT_STARTED,
        inFlight: Boolean = false,
        configured: Boolean = true,
        permission: Boolean = true,
        captureAttached: Boolean = true,
        liveAnalysis: LiveAnalysisStatus? = null,
    ) = PrimaryActionInputs(readiness, practice, inFlight, configured, permission, captureAttached, liveAnalysis)

    private fun button(inputs: PrimaryActionInputs) = PrimaryActionState.from(inputs).let { Triple(it.action, it.enabled, it.reason) }

    @Test fun `every disabled state has a one-line reason and every enabled state has none`() {
        assertEquals(7 * 3 * 2 * 2 * 2 * 2 * liveAnalysisValues.size, table.size)
        for (row in table) {
            val state = PrimaryActionState.from(row)
            if (state.enabled) {
                assertNull("$row", state.reason)
            } else {
                val reason = state.reason
                assertNotNull("$row", reason)
                assertTrue("$row", reason!!.isNotBlank() && '\n' !in reason && reason.length <= 60)
            }
        }
        assertEquals(PrimaryAction.entries.toSet(), table.map { PrimaryActionState.from(it).action }.toSet())
    }

    @Test fun `the button follows the expected table`() {
        data class Row(val inputs: PrimaryActionInputs, val action: PrimaryAction, val reason: String?)
        val rows = listOf(
            Row(inputs(NOT_CONFIGURED, configured = false, permission = false), SET_UP_CONNECTION, null),
            Row(inputs(NOT_CONFIGURED, configured = false, inFlight = true), SET_UP_CONNECTION, WAITING_FOR_SERVER),
            Row(inputs(NOT_CONFIGURED, permission = false), ALLOW_CAMERA, null),
            Row(inputs(STOPPED, permission = false, inFlight = true), ALLOW_CAMERA, WAITING_FOR_SERVER),
            Row(inputs(NOT_CONFIGURED), GO_LIVE, null),
            Row(inputs(PUBLISHER_UNAVAILABLE), GO_LIVE, null),
            Row(inputs(NOT_CONFIGURED, inFlight = true), GO_LIVE, WAITING_FOR_SERVER),
            Row(inputs(STOPPED), RESTART_VIDEO, null),
            Row(inputs(ERROR, practice = PAUSED), RESTART_VIDEO, null),
            Row(inputs(ERROR, inFlight = true), RESTART_VIDEO, WAITING_FOR_SERVER),
            Row(inputs(CONNECTING), PrimaryAction.CONNECTING, CONNECTING_REASON),
            Row(inputs(CONNECTING, practice = PAUSED, inFlight = true), PrimaryAction.CONNECTING, CONNECTING_REASON),
            Row(inputs(RECONNECTING, practice = PAUSED), PrimaryAction.CONNECTING, RECONNECTING_REASON),
            Row(inputs(RECONNECTING, practice = ACTIVE), PAUSE, null),
            Row(inputs(RECONNECTING, practice = ACTIVE, inFlight = true), PAUSE, WAITING_FOR_SERVER),
            // Settings and permission cannot change while the video runs, so they are not re-checked.
            Row(inputs(CONNECTING, configured = false, permission = false), PrimaryAction.CONNECTING, CONNECTING_REASON),
            Row(inputs(LIVE, configured = false, permission = false), START_PRACTICE, null),
            Row(inputs(LIVE), START_PRACTICE, null),
            Row(inputs(LIVE, inFlight = true), START_PRACTICE, WAITING_FOR_SERVER),
            Row(inputs(LIVE, practice = PAUSED), RESUME, null),
            Row(inputs(LIVE, practice = PAUSED, inFlight = true), RESUME, WAITING_FOR_SERVER),
            Row(inputs(LIVE, practice = ACTIVE), PAUSE, null),
            Row(inputs(LIVE, practice = ACTIVE, inFlight = true), PAUSE, WAITING_FOR_SERVER),
            Row(inputs(LIVE, practice = ACTIVE, captureAttached = false), PAUSE, null),
            Row(inputs(LIVE, captureAttached = false, inFlight = true), START_PRACTICE, ATTACHING_REASON),
            Row(inputs(LIVE, practice = PAUSED, captureAttached = false, inFlight = true), RESUME, ATTACHING_REASON),
            Row(inputs(LIVE, captureAttached = false), RESTART_VIDEO, null),
            Row(inputs(LIVE, practice = PAUSED, captureAttached = false), RESTART_VIDEO, null),
        )
        for (row in rows) {
            assertEquals("${row.inputs}", Triple(row.action, row.reason == null, row.reason), button(row.inputs))
        }
    }

    @Test fun `the athlete meets the actions in the listed order`() {
        val journey = listOf(
            inputs(NOT_CONFIGURED, configured = false, permission = false),
            inputs(NOT_CONFIGURED, permission = false),
            inputs(NOT_CONFIGURED),
            inputs(CONNECTING),
            inputs(LIVE),
            inputs(LIVE, practice = ACTIVE),
            inputs(LIVE, practice = PAUSED),
            inputs(STOPPED, practice = PAUSED),
        ).map { PrimaryActionState.from(it).action.label }
        assertEquals(
            listOf("Set up connection", "Allow camera", "Go live", "Connecting...", "Start practice", "Pause", "Resume", "Restart video"),
            journey,
        )
    }

    @Test fun `no liveAnalysis value changes the button, disables Start practice or Resume, or asks for confirmation`() {
        var startOrResumeChecked = 0
        for (row in table) {
            val withoutStatus = PrimaryActionState.from(row.copy(liveAnalysis = null))
            // Everything except the hint line is identical, so no field (such as a confirmation)
            // can depend on the live-analysis status.
            assertEquals("$row", withoutStatus, PrimaryActionState.from(row).copy(hint = null, hintAction = null))
            if (withoutStatus.action in setOf(START_PRACTICE, RESUME) && withoutStatus.enabled) {
                assertTrue("$row", PrimaryActionState.from(row).enabled)
                startOrResumeChecked++
            }
        }
        // Live, attached and idle: 2 practice states x 4 setting combinations x every status.
        assertEquals(2 * 4 * liveAnalysisValues.size, startOrResumeChecked)
        assertEquals(23, liveAnalysisValues.size)
    }

    @Test fun `the hint shows the live-analysis text while live and offers Restart video only for capture blocks`() {
        for (row in table) {
            val state = PrimaryActionState.from(row)
            val status = row.liveAnalysis
            if (row.readiness != LIVE || status == null) {
                assertNull("$row", state.hint)
                assertNull("$row", state.hintAction)
                continue
            }
            val expectedHint = if (status.stale) phoneText(LiveAnalysisState.STALE) else phoneText(status.state, status.reasonCode)
            assertEquals("$row", expectedHint, state.hint)
            val offers = !status.stale && status.state == LiveAnalysisState.BLOCKED && status.reasonCode in restartReasons
            assertEquals("$row", if (offers) RESTART_VIDEO else null, state.hintAction)
        }
    }

    @Test fun `a capture block while ready to practise keeps Start practice and offers Restart video`() {
        for (reason in restartReasons) {
            for (practice in listOf(NOT_STARTED, PAUSED, ACTIVE)) {
                val state = PrimaryActionState.from(inputs(LIVE, practice = practice, liveAnalysis = LiveAnalysisStatus(LiveAnalysisState.BLOCKED, reason)))
                val expected = when (practice) {
                    NOT_STARTED -> START_PRACTICE
                    PAUSED -> RESUME
                    ACTIVE -> PAUSE
                }
                assertEquals(expected, state.action)
                assertTrue(state.enabled)
                assertEquals(RESTART_VIDEO, state.hintAction)
                assertTrue(state.hint!!.endsWith("tap Restart video"))
            }
        }
        val unsupported = PrimaryActionState.from(
            inputs(LIVE, liveAnalysis = LiveAnalysisStatus(LiveAnalysisState.BLOCKED, LiveBlockedReason.UNSUPPORTED_PRACTICE_TECHNIQUE)),
        )
        assertEquals(PrimaryActionState(START_PRACTICE, true, null, "Automatic analysis supports Teep only", null), unsupported)
        val sideways = PrimaryActionState.from(inputs(LIVE, practice = PAUSED, liveAnalysis = LiveAnalysisStatus(LiveAnalysisState.SIDEWAYS)))
        assertEquals(PrimaryActionState(RESUME, true, null, "Video is sideways - stop and restart video", null), sideways)
        val stale = PrimaryActionState.from(
            inputs(LIVE, liveAnalysis = LiveAnalysisStatus(LiveAnalysisState.BLOCKED, LiveBlockedReason.WORKER_RETRY_LIMIT, stale = true)),
        )
        assertEquals(PrimaryActionState(START_PRACTICE, true, null, "Analysis status unavailable", null), stale)
    }

    @Test fun `practice state comes from the practice flag and the block`() {
        assertEquals(NOT_STARTED, PracticeState.of(practiceActive = false, hasBlock = false))
        assertEquals(PAUSED, PracticeState.of(practiceActive = false, hasBlock = true))
        assertEquals(ACTIVE, PracticeState.of(practiceActive = true, hasBlock = true))
        assertEquals(ACTIVE, PracticeState.of(practiceActive = true, hasBlock = false))
    }
}
