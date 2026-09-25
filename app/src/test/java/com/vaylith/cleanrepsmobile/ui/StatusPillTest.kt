package com.vaylith.cleanrepsmobile.ui

import com.vaylith.cleanrepsmobile.media.CaptureGeometry
import com.vaylith.cleanrepsmobile.model.CaptureReadiness
import com.vaylith.cleanrepsmobile.session.AppState
import org.junit.Assert.*
import org.junit.Test

class StatusPillTest {
    private fun geometry(displayRotation: Int) = CaptureGeometry.forDisplayRotation(displayRotation, 90).getOrThrow().label

    @Test fun `LIVE shows the geometry the encoder was prepared with`() {
        assertEquals("LIVE - landscape 1280x720", StatusPill.label(AppState(readiness = CaptureReadiness.LIVE, streamGeometry = geometry(1))))
        assertEquals("LIVE - landscape 1280x720", StatusPill.label(AppState(readiness = CaptureReadiness.LIVE, streamGeometry = geometry(3))))
        assertEquals("LIVE - portrait 720x1280", StatusPill.label(AppState(readiness = CaptureReadiness.LIVE, streamGeometry = geometry(0))))
        // The pill is the controller's LIVE pill, not a second formatting of it.
        val live = AppState(readiness = CaptureReadiness.LIVE, streamGeometry = geometry(0))
        assertEquals(live.livePill, StatusPill.label(live))
        assertEquals("LIVE", StatusPill.label(AppState(readiness = CaptureReadiness.LIVE)))
    }

    @Test fun `every video state has a label, and only LIVE carries a geometry`() {
        val expected = mapOf(
            CaptureReadiness.NOT_CONFIGURED to "Video off",
            CaptureReadiness.PUBLISHER_UNAVAILABLE to "Video unavailable",
            CaptureReadiness.CONNECTING to "Connecting...",
            CaptureReadiness.RECONNECTING to "Reconnecting...",
            CaptureReadiness.STOPPED to "Video off",
            CaptureReadiness.ERROR to "Video error",
        )
        assertEquals(CaptureReadiness.entries.toSet() - CaptureReadiness.LIVE, expected.keys)
        expected.forEach { (readiness, label) ->
            // A geometry still known from the last start does not appear once the video is not LIVE.
            assertEquals("$readiness", label, StatusPill.label(AppState(readiness = readiness, streamGeometry = geometry(1))))
        }
    }

    @Test fun `the dot reads live, waiting, error or off`() {
        assertEquals(PillTone.LIVE, StatusPill.tone(CaptureReadiness.LIVE))
        assertEquals(PillTone.WAITING, StatusPill.tone(CaptureReadiness.CONNECTING))
        assertEquals(PillTone.WAITING, StatusPill.tone(CaptureReadiness.RECONNECTING))
        assertEquals(PillTone.ERROR, StatusPill.tone(CaptureReadiness.ERROR))
        assertEquals(PillTone.ERROR, StatusPill.tone(CaptureReadiness.PUBLISHER_UNAVAILABLE))
        assertEquals(PillTone.OFF, StatusPill.tone(CaptureReadiness.STOPPED))
        assertEquals(PillTone.OFF, StatusPill.tone(CaptureReadiness.NOT_CONFIGURED))
    }
}
