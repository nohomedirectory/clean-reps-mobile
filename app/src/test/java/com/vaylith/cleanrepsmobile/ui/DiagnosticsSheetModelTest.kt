package com.vaylith.cleanrepsmobile.ui

import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticStep
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticsLog
import com.vaylith.cleanrepsmobile.diagnostics.Redaction
import com.vaylith.cleanrepsmobile.media.CaptureGeometry
import com.vaylith.cleanrepsmobile.model.ConnectionSettings
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsSheetModelTest {
    private val build = BuildIdentity("0.3.0-rehearsal", 3, "4acbcce0123456789abcdef0123456789abcdef0", "Motorola", "moto g power", 34)
    private val landscape = CaptureGeometry.forDisplayRotation(1, 90).getOrThrow()
    private val sensor270 = CaptureGeometry.forDisplayRotation(1, 270).getOrThrow()

    /** Synthetic settings (RFC 2606/5737 values only). */
    private val settings = ConnectionSettings(
        apiBaseUrl = "https://api.example.test",
        srtHost = "192.0.2.10:8890",
        srtPassphrase = "fixture-passphrase-7Q",
        publishPassword = "fixture-publish-3Z",
    )

    private fun log(redaction: Redaction = Redaction.forSettings(settings)) = DiagnosticsLog(clock = { 1_000L }, sink = { _, _, _ -> }, redaction = redaction)

    @Test fun `build identity and the camera geometry`() {
        val model = DiagnosticsModel.from(build, 90, landscape, emptyList(), "")
        assertEquals(
            listOf("App 0.3.0-rehearsal (version code 3)", "Git SHA 4acbcce0123456789abcdef0123456789abcdef0", "Phone Motorola moto g power, Android SDK 34"),
            model.identity,
        )
        assertEquals(
            listOf("Back camera sensor orientation: 90 degrees", "Prepared geometry: landscape 1280x720, display rotation 90 degrees, rotation argument 0"),
            model.camera,
        )
        assertFalse(model.cameraUnverified)
        val unknown = DiagnosticsModel.from(build, null, null, emptyList(), "")
        assertEquals(listOf("Back camera sensor orientation: unavailable", "Prepared geometry: not prepared yet"), unknown.camera)
        assertEquals(listOf("No events yet"), unknown.events)
    }

    @Test fun `a 270-degree sensor is flagged as unverified`() {
        assertTrue(sensor270.sensorCompensationUnverified)
        val model = DiagnosticsModel.from(build, 270, sensor270, emptyList(), "")
        assertTrue(model.cameraUnverified)
        assertEquals(DiagnosticsModel.UNVERIFIED_270, model.camera.last())
        // The sensor reading alone is enough, before any prepare.
        assertTrue(DiagnosticsModel.from(build, 270, null, emptyList(), "").cameraUnverified)
    }

    @Test fun `the events are the log's last 100, newest first, and Copy is the log's redacted export`() {
        val log = log()
        repeat(130) { log.info(DiagnosticStep.PREVIEW_START, "event $it") }
        val model = DiagnosticsModel.from(build, 90, landscape, log.entries(), log.exportText())
        assertEquals(100, model.events.size)
        assertTrue(model.events.first().endsWith("event 129"))
        assertTrue(model.events.last().endsWith("event 30"))
        assertEquals(log.exportText(), model.copyText)
        assertTrue(model.copyText.startsWith("Clean Reps diagnostics: 100 events, oldest first, redacted"))
    }

    @Test fun `a secret injected into an event never appears in the copy text or the event lines (planted)`() {
        val log = log()
        log.fail(DiagnosticStep.SRT_CONNECT, "connect to 192.0.2.10:8890 with ${settings.srtPassphrase} failed")
        log.fail(DiagnosticStep.CREATE_SESSION, "server said", IOException("password ${settings.publishPassword} rejected by api.example.test"))
        log.info(DiagnosticStep.CLIENT_INFO, "publishing as ${settings.publishPassword}")
        val model = DiagnosticsModel.from(build, 90, landscape, log.entries(), log.exportText())
        for (secret in listOf(settings.srtPassphrase, settings.publishPassword)) {
            assertFalse("copy text leaks $secret", secret in model.copyText)
            model.events.forEach { assertFalse("event leaks $secret: $it", secret in it) }
        }
        assertEquals(3, model.events.size)
    }

    @Test fun `a secret recorded before the settings were known is redacted at Copy time (planted)`() {
        val late = "late-configured-secret-55"
        val log = log(Redaction.PATTERNS_ONLY)
        log.info(DiagnosticStep.PUBLISHER_START, "value $late seen")
        assertTrue("the entry was recorded before the settings knew it", log.entries().single().redactedMessage.contains(late))
        log.redaction = Redaction.forSettings(settings.copy(publishPassword = late))
        assertFalse(late in DiagnosticsModel.from(build, 90, landscape, log.entries(), log.exportText()).copyText)
    }

    @Test fun `Frame check reports the whole transmitted frame at the encoder size`() {
        assertEquals("Whole transmitted frame: 1280x720 (landscape)", DiagnosticsModel.frameCheckText(1280, 720))
        assertEquals("Whole transmitted frame: 720x1280 (portrait)", DiagnosticsModel.frameCheckText(720, 1280))
        assertEquals("No frame: the preview is not running, or no frame arrived in time.", DiagnosticsModel.frameCheckText(0, 0))
        // The encoder size is the prepared geometry's, not the cropped preview's.
        assertEquals(DiagnosticsModel.frameCheckText(landscape.encodedWidth, landscape.encodedHeight), DiagnosticsModel.frameCheckText(1280, 720))
    }
}
