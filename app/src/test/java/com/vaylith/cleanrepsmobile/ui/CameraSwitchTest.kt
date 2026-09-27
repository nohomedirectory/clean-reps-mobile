package com.vaylith.cleanrepsmobile.ui

import com.vaylith.cleanrepsmobile.media.CameraFacing
import com.vaylith.cleanrepsmobile.model.CaptureReadiness
import com.vaylith.cleanrepsmobile.session.AppState
import org.junit.Assert.*
import org.junit.Test

class CameraSwitchTest {
    @Test fun `a stopped preview offers the opposite lens`() {
        val rear = CameraSwitchModel.from(AppState(), true, true, CameraFacing.BACK)
        val front = CameraSwitchModel.from(AppState(), true, true, CameraFacing.FRONT)
        assertTrue(rear.enabled)
        assertEquals("Switch to front camera", rear.description)
        assertEquals("Switch to rear camera", front.description)
    }

    @Test fun `pausing practice does not unlock the lens while video is running`() {
        for (readiness in listOf(CaptureReadiness.CONNECTING, CaptureReadiness.LIVE, CaptureReadiness.RECONNECTING)) {
            val paused = AppState(readiness = readiness, practiceActive = false, practiceStarted = true)
            val model = CameraSwitchModel.from(paused, true, true, CameraFacing.BACK)
            assertFalse(model.enabled)
            assertEquals("Stop video to switch camera", model.reason)
        }
    }

    @Test fun `permission missing camera and pending requests have explicit reasons`() {
        assertEquals("Allow camera to switch", CameraSwitchModel.from(AppState(), false, true, CameraFacing.BACK).reason)
        assertEquals("Camera switch unavailable", CameraSwitchModel.from(AppState(), true, false, CameraFacing.BACK).reason)
        assertEquals("Wait to switch camera", CameraSwitchModel.from(AppState(requestInFlight = true), true, true, CameraFacing.BACK).reason)
        assertFalse(CameraSwitchModel.from(AppState(practiceActive = true), true, true, CameraFacing.BACK).enabled)
    }
}
