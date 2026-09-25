package com.vaylith.cleanrepsmobile.ui

import com.vaylith.cleanrepsmobile.model.CaptureReadiness
import com.vaylith.cleanrepsmobile.model.LiveAnalysisState
import com.vaylith.cleanrepsmobile.model.LiveAnalysisStatus
import com.vaylith.cleanrepsmobile.model.LiveBlockedReason
import com.vaylith.cleanrepsmobile.model.phoneText
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

    @Test fun `the overflow menu holds audio test and the two toggles, and the review marker only while practising`() {
        val idle = ControlRailModel.from(AppState(), configured = true, permission = true).overflow
        assertEquals(
            listOf(
                OverflowItem(OverflowAction.AUDIO_TEST, checked = null),
                // OD-4: voice hints are on by default; Speak verdicts is off.
                OverflowItem(OverflowAction.VOICE_HINTS, checked = true),
                OverflowItem(OverflowAction.SPEAK_VERDICTS, checked = false),
            ),
            idle,
        )
        val toggled = ControlRailModel.from(AppState(voiceHints = false, debugSpeakVerdicts = true), configured = true, permission = true).overflow
        assertEquals(false, toggled.single { it.action == OverflowAction.VOICE_HINTS }.checked)
        assertEquals(true, toggled.single { it.action == OverflowAction.SPEAK_VERDICTS }.checked)
        for ((state, configured, permission) in table) {
            val overflow = ControlRailModel.from(state, configured, permission).overflow
            assertEquals("$state", state.practiceActive, overflow.any { it.action == OverflowAction.MANUAL_MARKER })
            assertEquals("$state", 1, overflow.count { it.action == OverflowAction.AUDIO_TEST })
        }
        assertEquals(listOf("Audio test", "Voice hints", "Speak verdicts", "Save manual review marker"), OverflowAction.entries.map { it.label })
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

    /** Every line the rail can show under the button: the table's reasons and hints, and every live-analysis text. */
    private fun railLines(): Set<String> = buildSet {
        for ((state, configured, permission) in table) {
            val model = ControlRailModel.from(state, configured, permission)
            model.reason?.let(::add)
            model.hint?.let(::add)
        }
        for (state in LiveAnalysisState.entries) {
            add(phoneText(state))
            LiveBlockedReason.entries.forEach { add(phoneText(state, it)) }
        }
        addAll(listOf(PrimaryActionState.WAITING_FOR_SERVER, PrimaryActionState.CONNECTING_REASON,
            PrimaryActionState.RECONNECTING_REASON, PrimaryActionState.ATTACHING_REASON))
    }

    @Test fun `every reason and hint fits the rail's line budget in both orientations`() {
        // The lines are narrowest in the landscape rail; the portrait band is wider on any phone of 360 dp or more.
        assertTrue(RailText.portraitWidthDp(360) >= RailText.LANDSCAPE_WIDTH_DP)
        assertEquals(27, RailText.charsPerLine(RailText.LANDSCAPE_WIDTH_DP))
        assertEquals(3, RailText.MAX_LINES)
        val lines = railLines()
        // The longest strings, which the old one-line rail cut to "Head not visible - move the ph...".
        assertTrue("Another Clean Reps session is still open - tap Restart video" in lines)
        assertTrue("Head not visible - move the phone back or higher" in lines)
        lines.forEach { line ->
            val wrapped = RailText.lines(line)
            assertTrue("$line -> $wrapped", RailText.fits(line))
            assertEquals(line, wrapped.joinToString(" "))
        }
    }

    @Test fun `the line budget refuses a line that would not fit (planted)`() {
        assertEquals(listOf("Head not visible - move the", "phone back or higher"), RailText.lines("Head not visible - move the phone back or higher"))
        assertEquals(listOf("Another Clean Reps session", "is still open - tap Restart", "video"),
            RailText.lines("Another Clean Reps session is still open - tap Restart video"))
        // Four lines, and a word no line can hold.
        assertFalse(RailText.fits("Another Clean Reps session is still open - tap Restart video, then wait for the server"))
        assertFalse(RailText.fits("A" + "x".repeat(30)))
        // In a 160 dp column the same hint would need five lines.
        assertFalse(RailText.fits("Another Clean Reps session is still open - tap Restart video", widthDp = 160))
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
