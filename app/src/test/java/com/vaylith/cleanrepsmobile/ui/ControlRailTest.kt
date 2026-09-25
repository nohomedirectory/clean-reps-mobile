package com.vaylith.cleanrepsmobile.ui

import com.vaylith.cleanrepsmobile.model.CaptureReadiness
import com.vaylith.cleanrepsmobile.model.LiveAnalysisState
import com.vaylith.cleanrepsmobile.model.LiveAnalysisStatus
import com.vaylith.cleanrepsmobile.model.LiveBlockedReason
import com.vaylith.cleanrepsmobile.session.AppState
import org.junit.Assert.*
import org.junit.Test

class ControlRailTest {
    /** (practiceActive, practiceStarted): not started, active, paused. */
    private val practices = listOf(false to false, true to true, false to true)
    private val liveAnalysisValues = listOf(
        null,
        LiveAnalysisStatus(LiveAnalysisState.TRACKING),
        LiveAnalysisStatus(LiveAnalysisState.BLOCKED, LiveBlockedReason.WAITING_FOR_NEW_CAPTURE_EPOCH),
        LiveAnalysisStatus(LiveAnalysisState.SIDEWAYS, stale = true),
    )

    /** Every screen state the rail derives from: readiness x practice x in flight x capture x live analysis, and the host flags. */
    private val table: List<Triple<AppState, Boolean, Boolean>> = buildList {
        for (readiness in CaptureReadiness.entries) for ((active, started) in practices) for (inFlight in listOf(false, true))
            for (capture in listOf(null, "capture-1")) for (liveAnalysis in liveAnalysisValues)
                for (configured in listOf(false, true)) for (permission in listOf(false, true)) {
                    val state = AppState(readiness = readiness, practiceActive = active, practiceStarted = started,
                        requestInFlight = inFlight, captureId = capture, liveAnalysis = liveAnalysis)
                    add(Triple(state, configured, permission))
                }
    }

    @Test fun `every primary action maps to one command, and Connecting does nothing`() {
        val expected = mapOf(
            PrimaryAction.SET_UP_CONNECTION to RailCommand.OPEN_CONNECTION_SETTINGS,
            PrimaryAction.ALLOW_CAMERA to RailCommand.REQUEST_CAMERA_PERMISSION,
            PrimaryAction.GO_LIVE to RailCommand.START_VIDEO,
            PrimaryAction.CONNECTING to RailCommand.NONE,
            PrimaryAction.START_PRACTICE to RailCommand.START_PRACTICE,
            PrimaryAction.RESUME to RailCommand.START_PRACTICE,
            PrimaryAction.PAUSE to RailCommand.PAUSE_PRACTICE,
            PrimaryAction.RESTART_VIDEO to RailCommand.RESTART_VIDEO,
        )
        assertEquals(PrimaryAction.entries.toSet(), expected.keys)
        PrimaryAction.entries.forEach { assertEquals("$it", expected.getValue(it), it.command()) }
    }

    @Test fun `the rail is M3c's button, with a one-line reason exactly when it is disabled`() {
        assertEquals(7 * 3 * 2 * 2 * 4 * 2 * 2, table.size)
        var disabled = 0
        for ((state, configured, permission) in table) {
            val model = ControlRailModel.from(state, configured, permission)
            val m3c = PrimaryActionState.from(PrimaryActionInputs(state.readiness, state.practice, state.requestInFlight, configured, permission,
                state.captureId != null, state.liveAnalysis))
            assertEquals("$state", m3c, model.primary)
            assertEquals("$state", m3c.hint, model.hint)
            assertEquals("$state", m3c.hintAction, model.hintAction)
            if (model.primary.enabled) {
                assertNull("$state", model.reason)
            } else {
                disabled++
                val reason = model.reason
                assertFalse("$state: disabled ${model.primary.action} without a reason", reason.isNullOrBlank())
                assertFalse("$state: reason spans lines", '\n' in reason!!)
            }
        }
        assertTrue("the table reaches disabled states ($disabled)", disabled > 0)
    }

    @Test fun `Stop video shows whenever the video runs, the gear only while nothing runs`() {
        for ((state, configured, permission) in table) {
            val model = ControlRailModel.from(state, configured, permission)
            val running = state.readiness in setOf(CaptureReadiness.CONNECTING, CaptureReadiness.LIVE, CaptureReadiness.RECONNECTING)
            assertEquals("$state", running, model.stopVideoShown)
            assertEquals("$state", running && !state.requestInFlight, model.stopVideoEnabled)
            assertEquals("$state", !running && !state.requestInFlight && !state.practiceActive, model.settingsEnabled)
        }
    }

    @Test fun `the Restart video hint maps to the restart command`() {
        val state = AppState(readiness = CaptureReadiness.LIVE, captureId = "capture-1",
            liveAnalysis = LiveAnalysisStatus(LiveAnalysisState.BLOCKED, LiveBlockedReason.WORKER_RETRY_LIMIT))
        val model = ControlRailModel.from(state, configured = true, permission = true)
        assertEquals(PrimaryAction.START_PRACTICE, model.primary.action)
        assertEquals(PrimaryAction.RESTART_VIDEO, model.hintAction)
        assertEquals(RailCommand.RESTART_VIDEO, model.hintAction!!.command())
        assertNotNull(model.hint)
    }

    @Test fun `a disabled button without a one-line reason is refused (planted)`() {
        listOf(null, "", "   ", "first line\nsecond line").forEach { reason ->
            val planted = PrimaryActionState(PrimaryAction.GO_LIVE, enabled = false, reason = reason)
            assertThrows("reason <$reason>", IllegalArgumentException::class.java) { ControlRailModel.reason(planted) }
        }
        assertNull(ControlRailModel.reason(PrimaryActionState(PrimaryAction.GO_LIVE, enabled = true, reason = null)))
        assertEquals("Connecting to the video server",
            ControlRailModel.reason(PrimaryActionState(PrimaryAction.CONNECTING, enabled = false, reason = PrimaryActionState.CONNECTING_REASON)))
    }
}
