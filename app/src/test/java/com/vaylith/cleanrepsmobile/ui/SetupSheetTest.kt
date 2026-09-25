package com.vaylith.cleanrepsmobile.ui

import com.vaylith.cleanrepsmobile.model.BlockSelection
import com.vaylith.cleanrepsmobile.model.CaptureReadiness
import com.vaylith.cleanrepsmobile.model.KickSide
import com.vaylith.cleanrepsmobile.model.KickTarget
import com.vaylith.cleanrepsmobile.model.KickTechnique
import com.vaylith.cleanrepsmobile.model.TargetHeight
import com.vaylith.cleanrepsmobile.session.AppState
import org.junit.Assert.*
import org.junit.Test

class SetupSheetTest {
    private val running = setOf(CaptureReadiness.CONNECTING, CaptureReadiness.LIVE, CaptureReadiness.RECONNECTING)

    /** readiness x practice active x in flight. */
    private val states: List<AppState> = CaptureReadiness.entries.flatMap { readiness ->
        listOf(false, true).flatMap { active -> listOf(false, true).map { inFlight ->
            AppState(readiness = readiness, practiceActive = active, practiceStarted = active, requestInFlight = inFlight)
        } }
    }

    @Test fun `the drill chip reads Teep - Right - Hanging bag by default`() {
        assertEquals("Teep - Right - Hanging bag", SetupRules.drillLabel(BlockSelection()))
        assertEquals("Teep - Right - Hanging bag", ControlRailModel.from(AppState(), configured = true, permission = true).drillLabel)
        assertEquals("Teep - Left - Standing bag - High target",
            SetupRules.drillLabel(BlockSelection(side = KickSide.LEFT, targetContext = KickTarget.STANDING_BAG, targetHeight = TargetHeight.HIGH)))
        assertEquals("Teep - Right - Air", SetupRules.drillLabel(BlockSelection(targetContext = KickTarget.AIR)))
    }

    @Test fun `Teep is selectable, Roundhouse and Side kick are shown disabled as not auto-judged yet`() {
        val options = SetupRules.techniques(AppState())
        assertEquals(KickTechnique.entries, options.map { it.technique })
        assertEquals(TechniqueOption(KickTechnique.TEEP, enabled = true, note = null), options[0])
        assertEquals(TechniqueOption(KickTechnique.ROUNDHOUSE, enabled = false, note = "not auto-judged yet"), options[1])
        assertEquals(TechniqueOption(KickTechnique.SIDE_KICK, enabled = false, note = "not auto-judged yet"), options[2])
    }

    @Test fun `a non-Teep technique can never be selected, in any state (planted)`() {
        for (state in states) {
            SetupRules.techniques(state).filter { !it.technique.autoJudged }.forEach { option ->
                assertFalse("$state ${option.technique}", option.enabled)
                assertEquals(SetupRules.NOT_AUTO_JUDGED, option.note)
            }
        }
        // Even a tap that reached the handler asks for nothing.
        val selection = BlockSelection(side = KickSide.LEFT)
        assertNull(SetupRules.withTechnique(selection, KickTechnique.ROUNDHOUSE))
        assertNull(SetupRules.withTechnique(selection, KickTechnique.SIDE_KICK))
        assertEquals(selection, SetupRules.withTechnique(selection, KickTechnique.TEEP))
    }

    @Test fun `the drill chip and picker are disabled while practising, connection settings while the video runs`() {
        for (state in states) {
            val drill = !state.practiceActive && !state.requestInFlight
            val connection = state.readiness !in running && !state.practiceActive && !state.requestInFlight
            assertEquals("$state", drill, SetupRules.drillEditable(state))
            assertEquals("$state", connection, SetupRules.connectionEditable(state))
            val rail = ControlRailModel.from(state, configured = true, permission = true)
            assertEquals("$state", drill, rail.drillEnabled)
            assertEquals("$state", connection, rail.settingsEnabled)
            // Teep follows the drill lock too.
            assertEquals("$state", drill, SetupRules.techniques(state).single { it.technique == KickTechnique.TEEP }.enabled)
        }
        // Planted: practising or live always locks them.
        val practising = AppState(readiness = CaptureReadiness.LIVE, practiceActive = true, practiceStarted = true, captureId = "capture-1")
        assertFalse(SetupRules.drillEditable(practising))
        assertFalse(ControlRailModel.from(practising, configured = true, permission = true).drillEnabled)
        running.forEach { readiness ->
            assertFalse("$readiness", SetupRules.connectionEditable(AppState(readiness = readiness)))
            assertFalse("$readiness", ControlRailModel.from(AppState(readiness = readiness), configured = true, permission = true).settingsEnabled)
        }
        // Live but not practising: the drill may change (M6b gives it a new block at once).
        assertTrue(SetupRules.drillEditable(AppState(readiness = CaptureReadiness.LIVE, captureId = "capture-1")))
    }

    @Test fun `the sheet has the drill, connection and audio parts`() {
        assertEquals(listOf("Drill", "Connection", "Audio"), SetupSection.entries.map { it.title })
    }
}
