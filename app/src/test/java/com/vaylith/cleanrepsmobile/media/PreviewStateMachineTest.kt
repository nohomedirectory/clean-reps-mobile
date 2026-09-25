package com.vaylith.cleanrepsmobile.media

import com.vaylith.cleanrepsmobile.model.CaptureOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * L0 proof of the preview lifecycle against a RootEncoder model taken from its
 * bytecode. Real surface-callback order on a phone is not proved here.
 */
class PreviewStateMachineTest {
    private class Rig {
        val port = FakeEncoderPort()
        val statuses = mutableListOf<PreviewStatus>()
        val machine = PreviewStateMachine(port) { statuses += it }
        private var nextSurface = 1

        fun surface() = FakeSurface(nextSurface++)

        fun readyCount() = statuses.count { it == PreviewStatus.Ready }

        /** The surface callbacks for a newly created view: created, then changed with real dimensions. */
        fun showSurface(surface: FakeSurface = surface(), width: Int = 2400, height: Int = 1080): FakeSurface {
            machine.surfaceAvailable(surface, width, height)
            machine.surfaceChanged(width, height)
            return surface
        }

        fun destroySurface(surface: FakeSurface) {
            machine.surfaceLost()
            surface.valid = false
        }

        /** The publisher's Go-live order: ask the machine, then start only on Ready. */
        fun goLive(geometry: CaptureGeometry): StreamGate {
            val gate = machine.streamRequested(geometry)
            if (gate is StreamGate.Ready) {
                port.startStream()
                machine.streamStarted()
            }
            return gate
        }

        fun stop() {
            if (port.isRecording) port.stopRecord()
            if (port.isStreaming) port.stopStream()
            machine.streamStopped()
        }

        fun assertNoViolations() = assertEquals(emptyList<String>(), port.violations)
    }

    // (a)
    @Test fun `create, destroy, create brings the preview back`() {
        val rig = Rig()
        rig.machine.geometryRequested(LANDSCAPE)
        val first = rig.showSurface()
        assertTrue(rig.port.isOnPreview)
        assertSame(first, rig.port.previewSurface)
        assertEquals(listOf(PreviewStatus.Starting, PreviewStatus.Ready), rig.statuses)

        rig.destroySurface(first)
        assertFalse(rig.port.isOnPreview)
        assertFalse("I4: the old surface is not retained", rig.machine.hasSurface)
        assertEquals(PreviewStatus.Lost, rig.statuses.last())

        val second = rig.showSurface()
        assertTrue(rig.port.isOnPreview)
        assertSame(second, rig.port.previewSurface)
        assertEquals("no re-prepare for a new surface", 1, rig.port.calls.count { it.startsWith("prepareVideo") })
        assertEquals(2, rig.readyCount())
        rig.assertNoViolations()
    }

    // (b)
    @Test fun `destroy while streaming, then create, re-attaches without stopping the stream or preparing again`() {
        val rig = Rig()
        rig.machine.geometryRequested(LANDSCAPE)
        val first = rig.showSurface()
        assertEquals(StreamGate.Ready(LANDSCAPE), rig.goLive(LANDSCAPE))
        rig.port.startRecord()
        rig.port.calls.clear()

        rig.destroySurface(first)
        assertTrue(rig.port.isStreaming)
        assertTrue(rig.port.isRecording)
        val second = rig.showSurface()
        assertEquals(listOf("stopPreview", "startPreview($second,2400x1080)"), rig.port.calls)
        assertTrue(rig.port.isStreaming)
        assertSame(second, rig.port.previewSurface)
        assertEquals(LANDSCAPE, rig.machine.preparedGeometry)
        assertEquals(PreviewStatus.Ready, rig.statuses.last())
        rig.assertNoViolations()
    }

    // (c)
    @Test fun `rotation while idle re-prepares once`() {
        val rig = Rig()
        rig.machine.geometryRequested(LANDSCAPE)
        val surface = rig.showSurface()
        rig.port.calls.clear()

        rig.machine.geometryRequested(PORTRAIT)
        assertEquals(listOf("stopPreview", "prepareVideo(90)", "startPreview($surface,2400x1080)"), rig.port.calls)
        assertEquals(PORTRAIT, rig.machine.preparedGeometry)
        assertTrue(rig.port.isOnPreview)

        rig.port.calls.clear()
        rig.machine.geometryRequested(PORTRAIT)
        assertEquals("the same geometry again is a no-op", emptyList<String>(), rig.port.calls)
        rig.assertNoViolations()
    }

    // (d)
    @Test fun `rotation while streaming is pending, then applied after stop`() {
        val rig = Rig()
        rig.machine.geometryRequested(LANDSCAPE)
        rig.showSurface()
        rig.goLive(LANDSCAPE)
        rig.port.startRecord()
        rig.port.calls.clear()

        rig.machine.geometryRequested(PORTRAIT)
        assertEquals(PreviewStatus.RotationPending(PORTRAIT), rig.statuses.last())
        assertEquals("I3: frozen while streaming", LANDSCAPE, rig.machine.preparedGeometry)
        assertEquals(PORTRAIT, rig.machine.pendingGeometry)
        assertTrue(rig.port.calls.none { it.startsWith("prepareVideo") })

        rig.stop()
        assertEquals("I7: applied after stop", PORTRAIT, rig.machine.preparedGeometry)
        assertNull(rig.machine.pendingGeometry)
        assertTrue(rig.port.isOnPreview)
        assertEquals(1, rig.port.calls.count { it == "prepareVideo(90)" })
        rig.assertNoViolations()
    }

    // (e)
    @Test fun `camera error, then retry`() {
        val rig = Rig()
        rig.machine.geometryRequested(LANDSCAPE)
        rig.showSurface()
        rig.machine.cameraError("Camera disconnected")
        assertEquals(PreviewStatus.CameraError("Camera disconnected"), rig.statuses.last())

        rig.port.calls.clear()
        rig.machine.retry()
        assertEquals(PreviewStatus.Ready, rig.statuses.last())
        assertNull(rig.machine.cameraError)
        assertTrue(rig.port.isOnPreview)
        assertEquals(1, rig.port.calls.count { it.startsWith("startPreview") })

        // "Camera in use": each retry makes exactly one attempt and never loops.
        rig.port.startPreviewFailure = "Camera in use by another app"
        rig.machine.cameraError("Camera in use by another app")
        rig.port.calls.clear()
        rig.machine.retry()
        assertEquals(1, rig.port.calls.count { it.startsWith("startPreview") })
        assertTrue(rig.statuses.last() is PreviewStatus.CameraError)
        rig.port.calls.clear()
        rig.machine.retry()
        assertEquals(1, rig.port.calls.count { it.startsWith("startPreview") })

        rig.port.startPreviewFailure = null
        rig.machine.retry()
        assertEquals(PreviewStatus.Ready, rig.statuses.last())

        rig.port.calls.clear()
        rig.machine.retry()
        assertEquals("retry without an error does nothing", emptyList<String>(), rig.port.calls)
        rig.assertNoViolations()
    }

    // (f)
    @Test fun `surfaceChanged before or after the geometry request`() {
        val surfaceFirst = Rig()
        val early = surfaceFirst.showSurface()
        assertTrue("nothing to prepare yet", surfaceFirst.port.calls.isEmpty())
        assertFalse(surfaceFirst.port.isOnPreview)
        surfaceFirst.machine.geometryRequested(LANDSCAPE)
        assertTrue(surfaceFirst.port.isOnPreview)
        assertSame(early, surfaceFirst.port.previewSurface)

        val geometryFirst = Rig()
        geometryFirst.machine.geometryRequested(LANDSCAPE)
        assertEquals(listOf("prepareVideo(0)", "prepareAudio"), geometryFirst.port.calls)
        geometryFirst.showSurface()
        assertTrue(geometryFirst.port.isOnPreview)

        for (rig in listOf(surfaceFirst, geometryFirst)) {
            assertEquals(LANDSCAPE, rig.machine.preparedGeometry)
            assertEquals(1, rig.port.calls.count { it.startsWith("prepareVideo") })
            assertEquals(1, rig.readyCount())
            rig.assertNoViolations()
        }
    }

    // (g)
    @Test fun `a double surfaceChanged is idempotent`() {
        val rig = Rig()
        rig.machine.geometryRequested(LANDSCAPE)
        rig.showSurface(width = 2400, height = 1080)
        rig.port.calls.clear()
        val before = rig.statuses.toList()

        rig.machine.surfaceChanged(2400, 1080)
        rig.machine.surfaceChanged(2400, 1080)
        assertEquals(emptyList<String>(), rig.port.calls)
        assertEquals(before, rig.statuses)

        rig.machine.surfaceChanged(1080, 2400)
        assertEquals(listOf("setPreviewResolution(1080x2400)"), rig.port.calls)
        rig.assertNoViolations()
    }

    // (h)
    @Test fun `a startPreview exception gives CAMERA_ERROR and no PREVIEW_READY`() {
        val rig = Rig()
        rig.port.startPreviewFailure = "Camera in use by another app"
        rig.machine.geometryRequested(LANDSCAPE)
        rig.showSurface()
        // M4c: the reason names the exception class; its message goes only to the diagnostics log.
        assertEquals(
            listOf(PreviewStatus.Starting, PreviewStatus.CameraError("Camera preview failed (RuntimeException)")),
            rig.statuses,
        )
        assertEquals(0, rig.readyCount())
        assertEquals(0, rig.port.successfulPreviewStarts)
        assertFalse(rig.port.isOnPreview)
    }

    // (i)
    @Test fun `a rotation interleaved with Go live, in every order, never streams a stale geometry`() {
        val positions = listOf("before request", "after request", "after startStream", "after streamStarted")
        var runs = 0
        for (withSurface in listOf(true, false)) for (initial in GEOMETRIES_90) for (live in GEOMETRIES_90) for (rotated in GEOMETRIES_90) {
            for (position in positions) {
                val rig = Rig()
                rig.machine.geometryRequested(initial)
                if (withSurface) rig.showSurface()
                if (position == "before request") rig.machine.geometryRequested(rotated)
                val gate = rig.machine.streamRequested(live)
                assertEquals(StreamGate.Ready(live), gate)
                if (position == "after request") rig.machine.geometryRequested(rotated)
                rig.port.startStream()
                if (position == "after startStream") rig.machine.geometryRequested(rotated)
                rig.machine.streamStarted()
                if (position == "after streamStarted") rig.machine.geometryRequested(rotated)

                val case = "initial=${initial.rotationArg} live=${live.rotationArg} rotated=${rotated.rotationArg} $position surface=$withSurface"
                assertEquals("I8 $case", listOf(live), rig.port.streamStarts)
                rig.stop()
                val latest = if (position == "before request") live else rotated
                assertEquals("I7 $case", latest, rig.machine.preparedGeometry)
                assertEquals("preview $case", withSurface, rig.port.isOnPreview)
                rig.assertNoViolations()
                runs++
            }
        }
        assertEquals(2 * 4 * 4 * 4 * 4, runs)
    }

    @Test fun `an unsupported camera size is a camera error and blocks Go live`() {
        val rig = Rig()
        rig.port.prepareFailure = "Resolution 1280x720 not supported"
        rig.showSurface()
        val gate = rig.machine.streamRequested(LANDSCAPE)
        assertTrue(gate is StreamGate.NotReady)
        assertTrue(rig.statuses.last() is PreviewStatus.CameraError)
        assertTrue(rig.port.streamStarts.isEmpty())
        assertNull(rig.machine.preparedGeometry)
        rig.assertNoViolations()
    }

    @Test fun `unsupported sizes give the two exact owner messages`() {
        val cameraSize = Rig()
        cameraSize.port.prepareFailure = "Unsupported resolution: 1280x720"
        cameraSize.machine.geometryRequested(PORTRAIT)
        assertEquals(PreviewStatus.CameraError("This camera cannot provide 1280x720 video"), cameraSize.statuses.single())
        assertEquals(PreviewStateMachine.UNSUPPORTED_CAMERA_SIZE, cameraSize.machine.cameraError)

        val encoder = Rig()
        encoder.port.encoderRefuses = { it.orientation == CaptureOrientation.PORTRAIT }
        for (portrait in GEOMETRIES_90.filter { it.orientation == CaptureOrientation.PORTRAIT }) {
            encoder.statuses.clear()
            encoder.machine.geometryRequested(portrait)
            assertEquals(PreviewStatus.CameraError("Portrait video not supported on this phone - use landscape"), encoder.statuses.single())
        }
        val landscapeRefused = Rig()
        landscapeRefused.port.encoderRefuses = { true }
        landscapeRefused.machine.geometryRequested(LANDSCAPE)
        assertEquals(PreviewStatus.CameraError("This device cannot prepare the video encoder for landscape 1280x720"), landscapeRefused.statuses.single())
        listOf(cameraSize, encoder, landscapeRefused).forEach { it.assertNoViolations() }
    }

    @Test fun `a successful prepare clears only a prepare failure`() {
        val rig = Rig()
        rig.port.encoderRefuses = { it.orientation == CaptureOrientation.PORTRAIT }
        rig.showSurface(width = 1080, height = 2400)
        assertEquals(StreamGate.NotReady(PreviewStateMachine.PORTRAIT_UNSUPPORTED), rig.machine.streamRequested(PORTRAIT))
        assertFalse(rig.port.isOnPreview)

        // Landscape remains available: the refusal belonged to portrait.
        rig.machine.geometryRequested(LANDSCAPE)
        assertNull(rig.machine.cameraError)
        assertEquals(PreviewStatus.Ready, rig.statuses.last())
        assertEquals(StreamGate.Ready(LANDSCAPE), rig.goLive(LANDSCAPE))
        rig.stop()

        // A camera error is not the encoder's: re-preparing for a rotation keeps it.
        rig.machine.cameraError("Camera disconnected")
        rig.port.encoderRefuses = { false }
        rig.machine.geometryRequested(PORTRAIT)
        assertEquals(PORTRAIT, rig.machine.preparedGeometry)
        assertEquals("Camera disconnected", rig.machine.cameraError)
        assertFalse(rig.port.isOnPreview)
        rig.machine.retry()
        assertEquals(PreviewStatus.Ready, rig.statuses.last())
        rig.assertNoViolations()
    }

    @Test fun `seeded fuzz of 10,000 sequences keeps I1 to I8 after every step`() {
        val baseSeed = 20_260_925L
        val coverage = Coverage()
        for (index in 0 until 10_000) fuzzSequence(baseSeed + index, coverage)
        // The fuzz must actually reach the states the invariants are about.
        val minimum = 200
        println(
            "fuzz coverage: streams=${coverage.streamsStarted} deferred=${coverage.rotationsDeferred} " +
                "pendingApplied=${coverage.pendingApplied} liveness=${coverage.livenessChecks} " +
                "reattached=${coverage.reattachedWhileStreaming} recovered=${coverage.recoveredByRetry}",
        )
        mapOf(
            "streams started (I8 checked)" to coverage.streamsStarted,
            "rotations deferred while streaming or recording (I3)" to coverage.rotationsDeferred,
            "pending geometries applied at stop (I7)" to coverage.pendingApplied,
            "surfaceChanged liveness checks (I5)" to coverage.livenessChecks,
            "preview re-attached while streaming" to coverage.reattachedWhileStreaming,
            "camera errors recovered by retry" to coverage.recoveredByRetry,
        ).forEach { (what, count) -> assertTrue("$what: only $count", count >= minimum) }
    }

    private class Coverage {
        var streamsStarted = 0
        var rotationsDeferred = 0
        var pendingApplied = 0
        var livenessChecks = 0
        var reattachedWhileStreaming = 0
        var recoveredByRetry = 0
    }

    private enum class Event {
        SURFACE_AVAILABLE, SURFACE_CHANGED, SURFACE_LOST, SURFACE_INVALIDATED, GEOMETRY_REQUESTED,
        STREAM_REQUESTED, START_STREAM, STREAM_STARTED, START_RECORD, STOP, CAMERA_ERROR, RETRY,
        TOGGLE_CAMERA_BUSY, TOGGLE_PREPARE_FAILURE,
    }

    private fun fuzzSequence(seed: Long, coverage: Coverage) {
        val random = Random(seed)
        val rig = Rig()
        val trace = mutableListOf<String>()
        var surface: FakeSurface? = null
        var latestRequested: CaptureGeometry? = null
        var latestStreamRequested: CaptureGeometry? = null
        var gate: StreamGate? = null
        var startedNotYetReported = false

        fun fail(message: String): Nothing =
            throw AssertionError("fuzz seed=$seed: $message\nevents:\n  ${trace.joinToString("\n  ")}\nport calls: ${rig.port.calls}")

        val length = random.nextInt(1, 41)
        repeat(length) {
            val event = Event.entries[random.nextInt(Event.entries.size)]
            val busyBefore = rig.port.isStreaming || rig.port.isRecording
            val preparedBefore = rig.port.preparedGeometry
            val pendingBefore = rig.machine.pendingGeometry
            val errorBefore = rig.machine.cameraError
            val readyBefore = rig.readyCount()
            val startsBefore = rig.port.successfulPreviewStarts
            try {
                when (event) {
                    Event.SURFACE_AVAILABLE -> {
                        surface?.valid = false
                        val next = rig.surface()
                        val (width, height) = SIZES[random.nextInt(SIZES.size)]
                        trace += "surfaceAvailable($next, ${width}x$height)"
                        surface = next
                        rig.machine.surfaceAvailable(next, width, height)
                    }
                    Event.SURFACE_CHANGED -> {
                        val (width, height) = SIZES[1 + random.nextInt(SIZES.size - 1)]
                        trace += "surfaceChanged(${width}x$height)"
                        rig.machine.surfaceChanged(width, height)
                    }
                    Event.SURFACE_LOST -> {
                        trace += "surfaceLost"
                        rig.machine.surfaceLost()
                        surface?.valid = false
                        surface = null
                    }
                    Event.SURFACE_INVALIDATED -> {
                        trace += "surface invalidated before its callback"
                        surface?.valid = false
                    }
                    Event.GEOMETRY_REQUESTED -> {
                        val geometry = ALL_GEOMETRIES[random.nextInt(ALL_GEOMETRIES.size)]
                        trace += "geometryRequested(${geometry.rotationArg}/${geometry.sensorOrientationDeg})"
                        latestRequested = geometry
                        rig.machine.geometryRequested(geometry)
                    }
                    Event.STREAM_REQUESTED -> {
                        val geometry = ALL_GEOMETRIES[random.nextInt(ALL_GEOMETRIES.size)]
                        trace += "streamRequested(${geometry.rotationArg}/${geometry.sensorOrientationDeg})"
                        if (!rig.port.isStreaming) latestRequested = geometry
                        latestStreamRequested = geometry
                        gate = rig.machine.streamRequested(geometry)
                        trace += "  -> $gate"
                    }
                    Event.START_STREAM -> {
                        val ready = gate as? StreamGate.Ready
                        if (ready != null && !rig.port.isStreaming) {
                            trace += "startStream"
                            rig.port.startStream()
                            startedNotYetReported = true
                            coverage.streamsStarted++
                            val streamed = rig.port.streamStarts.last()
                            if (streamed != latestStreamRequested || streamed != ready.geometry) {
                                fail("I8: stream started with ${streamed.label}/${streamed.rotationArg} but the latest request was ${latestStreamRequested?.rotationArg}")
                            }
                        }
                    }
                    Event.STREAM_STARTED -> if (startedNotYetReported) {
                        trace += "streamStarted"
                        startedNotYetReported = false
                        rig.machine.streamStarted()
                    }
                    Event.START_RECORD -> if (rig.port.isStreaming && !rig.port.isRecording) {
                        trace += "startRecord"
                        rig.port.startRecord()
                    }
                    Event.STOP -> {
                        trace += "stop (record, stream, streamStopped)"
                        rig.stop()
                        gate = null
                        startedNotYetReported = false
                    }
                    Event.CAMERA_ERROR -> {
                        trace += "cameraError"
                        rig.machine.cameraError("Camera disconnected")
                    }
                    Event.RETRY -> {
                        trace += "retry"
                        rig.machine.retry()
                    }
                    Event.TOGGLE_CAMERA_BUSY -> {
                        rig.port.startPreviewFailure = if (rig.port.startPreviewFailure == null) "Camera in use" else null
                        trace += "camera busy=${rig.port.startPreviewFailure != null}"
                    }
                    Event.TOGGLE_PREPARE_FAILURE -> {
                        // Rare: an unsupported size makes every later prepare fail until cleared.
                        if (random.nextInt(4) == 0 || rig.port.prepareFailure != null) {
                            rig.port.prepareFailure = if (rig.port.prepareFailure == null) "Resolution not supported" else null
                            trace += "prepare fails=${rig.port.prepareFailure != null}"
                        }
                    }
                }
            } catch (error: AssertionError) {
                throw error
            } catch (error: Exception) {
                fail("an exception escaped the machine: $error")
            }

            val port = rig.port
            val machine = rig.machine
            val busyAfter = port.isStreaming || port.isRecording
            // I1, I2: the fake recorded every illegal RootEncoder call.
            if (port.violations.isNotEmpty()) fail(port.violations.joinToString())
            if (machine.preparedGeometry != port.preparedGeometry) {
                fail("machine thinks ${machine.preparedGeometry?.rotationArg} is prepared, port has ${port.preparedGeometry?.rotationArg}")
            }
            if (machine.requestedGeometry != latestRequested) fail("machine lost the latest geometry request")
            // I3
            if (busyBefore && busyAfter && port.preparedGeometry != preparedBefore) {
                fail("I3: geometry changed while streaming or recording")
            }
            if (event == Event.GEOMETRY_REQUESTED && busyBefore && latestRequested != preparedBefore) coverage.rotationsDeferred++
            // I4
            if (event == Event.SURFACE_LOST && (port.isOnPreview || machine.hasSurface)) {
                fail("I4: preview on or surface retained after surfaceLost")
            }
            // I5
            val current = surface
            if (event == Event.SURFACE_CHANGED && current != null && current.valid && machine.cameraError == null &&
                latestRequested != null
            ) {
                coverage.livenessChecks++
                if (!port.isOnPreview || port.previewSurface !== current) {
                    fail("I5: valid surface, known geometry and no camera error, but the preview is off")
                }
            }
            if (busyAfter && port.successfulPreviewStarts > startsBefore) coverage.reattachedWhileStreaming++
            if (event == Event.RETRY && errorBefore != null && rig.readyCount() > readyBefore) coverage.recoveredByRetry++
            // I6
            if (rig.readyCount() - readyBefore != port.successfulPreviewStarts - startsBefore) {
                fail("I6: PREVIEW_READY count does not match successful startPreview calls")
            }
            // I7
            if (event == Event.STOP && !busyAfter && machine.cameraError == null &&
                (machine.pendingGeometry != null || port.preparedGeometry != latestRequested)
            ) {
                fail("I7: pending geometry ${machine.pendingGeometry?.rotationArg} not applied after stop")
            }
            if (event == Event.STOP && pendingBefore != null && port.preparedGeometry == pendingBefore) coverage.pendingApplied++
        }
    }

    private companion object {
        val PORTRAIT = geometry(0, 90)
        val LANDSCAPE = geometry(1, 90)
        val GEOMETRIES_90 = (0..3).map { geometry(it, 90) }
        /** M2a's table: display rotations 0-3 for back sensors at 90 and 270 degrees. */
        val ALL_GEOMETRIES = listOf(90, 270).flatMap { sensor -> (0..3).map { geometry(it, sensor) } }
        val SIZES = listOf(0 to 0, 2400 to 1080, 1080 to 2400, 1920 to 1080)

        fun geometry(displayRotation: Int, sensorOrientation: Int) =
            CaptureGeometry.forDisplayRotation(displayRotation, sensorOrientation).getOrThrow()
    }
}
