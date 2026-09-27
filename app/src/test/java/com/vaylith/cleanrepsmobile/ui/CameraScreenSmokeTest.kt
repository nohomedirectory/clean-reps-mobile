package com.vaylith.cleanrepsmobile.ui

import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vaylith.cleanrepsmobile.api.ChallengeBackend
import com.vaylith.cleanrepsmobile.api.ChallengeEvents
import com.vaylith.cleanrepsmobile.api.ClientInfoResult
import com.vaylith.cleanrepsmobile.api.FetchResult
import com.vaylith.cleanrepsmobile.api.MobileVerdictEvent
import com.vaylith.cleanrepsmobile.api.ServerHealth
import com.vaylith.cleanrepsmobile.api.SessionCounts
import com.vaylith.cleanrepsmobile.diagnostics.DiagnosticsLog
import com.vaylith.cleanrepsmobile.feedback.AthleteSignals
import com.vaylith.cleanrepsmobile.feedback.FeedbackCue
import com.vaylith.cleanrepsmobile.media.CaptureGeometry
import com.vaylith.cleanrepsmobile.model.AthleteCue
import com.vaylith.cleanrepsmobile.model.BlockSelection
import com.vaylith.cleanrepsmobile.model.ConnectionSettings
import com.vaylith.cleanrepsmobile.model.LiveAnalysisStatus
import com.vaylith.cleanrepsmobile.model.ManualEvidenceWindow
import com.vaylith.cleanrepsmobile.model.SharedPreferencesDrillSelectionStore
import com.vaylith.cleanrepsmobile.model.SourceEpoch
import com.vaylith.cleanrepsmobile.session.AppState
import com.vaylith.cleanrepsmobile.session.ClientBuild
import com.vaylith.cleanrepsmobile.session.LostDeliveries
import com.vaylith.cleanrepsmobile.session.SessionController
import com.vaylith.cleanrepsmobile.session.SharedPreferencesPendingLostStore
import com.vaylith.cleanrepsmobile.session.lostServerKey
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * One JVM smoke render (L1): CameraScreen composes in portrait and in landscape under Robolectric
 * (SDK 35) with a real SessionController over fakes, and the primary button reads right in each
 * state: "Set up connection" without settings, "Go live" when configured with the camera allowed,
 * then "Connecting..." and "Start practice" as the fake transport reports. RootEncoder is never
 * built: the only publisher the controller can create is a [FakePublisher]. This is not a device
 * or visual proof; RootEncoder's GL and camera cannot run here, and nothing here shows that the
 * app opens on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CameraScreenSmokeTest {
    /**
     * The compose rule launches an empty ComponentActivity. The app's manifest does not declare
     * one and ui-test-manifest is kept out of the debug APK, so it is registered with
     * Robolectric's package manager before the compose rule starts.
     */
    @get:Rule(order = 0) val hostActivity = object : ExternalResource() {
        override fun before() {
            val app = RuntimeEnvironment.getApplication()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }

    @get:Rule(order = 1) val compose = createComposeRule()

    /** Main.immediate is Robolectric's main looper, as the Activity's lifecycleScope is the real main thread. */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** The app runs this on Dispatchers.IO; here it is the main looper too, so each step settles deterministically. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val backend = SmokeBackend()
    private val locks = mutableListOf<Boolean>()
    private val publishers = mutableListOf<FakePublisher>()
    private var shownAppearance: CameraThemePreferences? = null
    private var renderedRootView: View? = null
    private var shownPrimary: Color? = null
    private lateinit var renderedController: SessionController

    @After fun tearDown() {
        uiScope.cancel()
        appScope.cancel()
    }

    @Test @Config(qualifiers = PORTRAIT)
    fun `portrait without connection settings offers Set up connection, which opens the setup sheet`() =
        setUpConnection(Configuration.ORIENTATION_PORTRAIT)

    @Test @Config(qualifiers = LANDSCAPE)
    fun `landscape without connection settings offers Set up connection, which opens the setup sheet`() =
        setUpConnection(Configuration.ORIENTATION_LANDSCAPE)

    @Test @Config(qualifiers = PORTRAIT)
    fun `portrait with settings and the camera allowed offers Go live, then Connecting and Start practice`() =
        goLive(Configuration.ORIENTATION_PORTRAIT, PORTRAIT_GEOMETRY)

    @Test @Config(qualifiers = LANDSCAPE)
    fun `landscape with settings and the camera allowed offers Go live, then Connecting and Start practice`() =
        goLive(Configuration.ORIENTATION_LANDSCAPE, LANDSCAPE_GEOMETRY)

    @Test @Config(qualifiers = PORTRAIT)
    fun `portrait themes and motion change live without touching the capture`() =
        changeThemesWhileLive(Configuration.ORIENTATION_PORTRAIT, PORTRAIT_GEOMETRY)

    @Test @Config(qualifiers = LANDSCAPE)
    fun `landscape themes and motion change live without touching the capture`() =
        changeThemesWhileLive(Configuration.ORIENTATION_LANDSCAPE, LANDSCAPE_GEOMETRY)

    @Test @Config(qualifiers = LANDSCAPE)
    fun `return and restart keep controls visible while explicit stop offers the previous report`() {
        val publisher = render(CONFIGURED, true, LANDSCAPE_GEOMETRY, Configuration.ORIENTATION_LANDSCAPE)
        primary(PrimaryAction.GO_LIVE).performClick()
        compose.runOnIdle { publisher.live() }
        compose.runOnIdle { renderedController.onLeftScreen() }
        compose.onNodeWithText("Session check").assertDoesNotExist()
        primary(PrimaryAction.RESTART_VIDEO).assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { publisher.live() }
        compose.onNodeWithText("Session check").assertDoesNotExist()
        assertEquals(2, backend.calls.count { it == "attachCapture" })
        assertEquals(1, publishers.size)
        compose.onNodeWithText("Stop video").performClick()
        compose.onNodeWithText("Session check").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close Session check").performClick()
        compose.onNodeWithText("Session check").assertDoesNotExist()
        primary(PrimaryAction.RESTART_VIDEO).assertIsDisplayed().assertIsEnabled()
    }

    private fun changeThemesWhileLive(orientation: Int, geometry: CaptureGeometry) {
        val publisher = render(CONFIGURED, permission = true, geometry = geometry, orientation = orientation)
        primary(PrimaryAction.GO_LIVE).performClick()
        compose.runOnIdle { publisher.live() }
        primary(PrimaryAction.START_PRACTICE).assertIsDisplayed()

        val publisherCalls = publisher.calls.toList()
        val backendCalls = backend.calls.toList()
        val orientationLocks = locks.toList()
        val originalPreview = publisher.attachedViews.single()
        val store = CameraThemeStore(RuntimeEnvironment.getApplication())
        val primaryColors = mutableSetOf<Color?>()

        fun assertCaptureUnchanged() {
            assertEquals(1, publishers.size)
            assertSame(publisher, publishers.single())
            assertSame(originalPreview, publisher.attachedViews.single())
            assertEquals(publisherCalls, publisher.calls)
            assertEquals(backendCalls, backend.calls)
            assertEquals(orientationLocks, locks)
        }

        CameraThemeOption.entries.forEach { theme ->
            compose.onNodeWithContentDescription("More controls").performClick()
            compose.onNodeWithText("Themes").performClick()
            compose.onNodeWithText(theme.label).performScrollTo().performClick().assertIsSelected()
            compose.runOnIdle {
                assertEquals(theme, shownAppearance?.theme)
                assertEquals(theme.palette.accent, shownPrimary)
                assertEquals(theme, store.load().theme)
                primaryColors += shownPrimary
                assertCaptureUnchanged()
            }
            compose.onNodeWithContentDescription("Close Themes").performClick()
            compose.onNodeWithText("LIVE - ${geometry.label}").assertIsDisplayed()
            primary(PrimaryAction.START_PRACTICE).assertIsDisplayed().assertIsEnabled()
            captureThemeIfRequested(theme, orientation)
        }
        assertEquals("each theme changes the displayed control palette", CameraThemeOption.entries.size, primaryColors.size)

        compose.onNodeWithContentDescription("More controls").performClick()
        compose.onNodeWithText("Themes").performClick()
        val motion = compose.onNodeWithContentDescription("Theme motion").performScrollTo()
        motion.assertIsOn().performClick().assertIsOff()
        compose.runOnIdle {
            assertEquals(false, shownAppearance?.motionEnabled)
            assertEquals(false, store.load().motionEnabled)
            assertCaptureUnchanged()
        }
        motion.performClick().assertIsOn()
        compose.runOnIdle {
            assertEquals(true, store.load().motionEnabled)
            assertCaptureUnchanged()
        }
        compose.onNodeWithContentDescription("Close Themes").performClick()
    }

    /**
     * Optional real Compose screenshots for a Robolectric NATIVE graphics run. Ordinary
     * tests do not capture or write files. The SurfaceView is a fake: these prove the
     * themed controls' rendering, never a physical camera image or video transport.
     */
    private fun captureThemeIfRequested(theme: CameraThemeOption, orientation: Int) {
        val directory = System.getenv("CLEAN_REPS_THEME_SCREENSHOT_DIR")?.takeIf { it.isNotBlank() } ?: return
        val output = File(directory).apply { mkdirs() }
        val screen = if (orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        val file = File(output, "camera-$screen-${theme.name.lowercase()}.png")
        // Draw the host controls directly: PixelCopy waits for a real window redraw
        // that this Robolectric host does not provide. The camera SurfaceView stays blank.
        compose.runOnIdle {
            val view = checkNotNull(renderedRootView)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { assertTrue("PNG was written", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }

    private fun setUpConnection(orientation: Int) {
        val publisher = render(ConnectionSettings(), permission = false, geometry = geometryFor(orientation), orientation = orientation)

        primary(PrimaryAction.SET_UP_CONNECTION).assertIsDisplayed().assertIsEnabled()
        assertRailPlacement(orientation, PrimaryAction.SET_UP_CONNECTION)
        // Without the camera permission there is no preview, so nothing is attached.
        assertEquals(emptyList<String>(), publisher.calls)

        primary(PrimaryAction.SET_UP_CONNECTION).performClick()
        compose.onNodeWithText("Clean Reps address").assertIsDisplayed()
        // The landscape side sheet is as tall as the window and scrolls to its buttons.
        compose.onNodeWithText("Save connection").performScrollTo().assertIsDisplayed()
        assertEquals(emptyList<String>(), backend.calls)
    }

    private fun goLive(orientation: Int, geometry: CaptureGeometry) {
        val publisher = render(CONFIGURED, permission = true, geometry = geometry, orientation = orientation)

        primary(PrimaryAction.GO_LIVE).assertIsDisplayed().assertIsEnabled()
        assertRailPlacement(orientation, PrimaryAction.GO_LIVE)
        // The one SurfaceView of the preview went to the fake, not to RootEncoder.
        assertEquals(listOf("attachPreview"), publisher.calls)
        assertEquals(1, publisher.attachedViews.size)

        // A stopped preview can change lens without creating a session or replacing the publisher.
        compose.onNodeWithContentDescription("Switch to front camera").assertIsDisplayed().performClick()
        compose.onNodeWithText("Front").assertIsDisplayed()
        compose.onNodeWithContentDescription("Switch to rear camera").performClick()
        compose.onNodeWithText("Rear").assertIsDisplayed()
        assertEquals(emptyList<String>(), backend.calls)
        assertEquals(listOf("attachPreview", "switchCamera", "switchCamera"), publisher.calls)

        primary(PrimaryAction.GO_LIVE).performClick()
        primary(PrimaryAction.CONNECTING).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(PrimaryActionState.CONNECTING_REASON).assertIsDisplayed()
        // OD-7: the orientation lock went on at the tap.
        assertEquals(listOf(true), locks)
        assertEquals(listOf("createSession", "attachCapture"), backend.calls.take(2))
        assertEquals(listOf("attachPreview", "switchCamera", "switchCamera", "start:0"), publisher.calls)
        compose.onNodeWithContentDescription("Stop video to switch camera").assertIsNotEnabled()

        compose.runOnIdle { publisher.live() }
        primary(PrimaryAction.START_PRACTICE).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("LIVE - ${geometry.label}").assertIsDisplayed()
        compose.onNodeWithText("Stop video").assertIsDisplayed()
        assertRailPlacement(orientation, PrimaryAction.START_PRACTICE)
        assertTrue(backend.calls.toString(), "createBlock" in backend.calls)
        assertEquals(1, publishers.size)
        compose.onNodeWithContentDescription("Stop video to switch camera").assertIsNotEnabled()
    }

    /** The primary button; the status pill can show the same words ("Connecting..."), but it is not clickable. */
    private fun primary(action: PrimaryAction) = compose.onNode(hasText(action.label) and hasClickAction())

    /** Renders CameraScreen as MainActivity does and checks that the window has the expected orientation. */
    private fun render(settings: ConnectionSettings, permission: Boolean, geometry: CaptureGeometry, orientation: Int): FakePublisher {
        val controller = controller(settings, geometry).also { renderedController = it }
        var shown: Int? = null
        compose.setContent {
            shown = LocalConfiguration.current.orientation
            CleanRepsTheme {
                shownAppearance = LocalCameraTheme.current.preferences
                renderedRootView = LocalView.current.rootView
                shownPrimary = MaterialTheme.colorScheme.primary
                CameraScreen(
                    controller = controller,
                    publisher = controller.publisher,
                    settings = settings,
                    permission = permission,
                    onRequestPermission = {},
                    onSaveConnection = {},
                    onAudioTest = {},
                )
            }
        }
        compose.waitForIdle()
        assertEquals(35, Build.VERSION.SDK_INT)
        assertEquals(orientation, shown)
        val publisher = publishers.single()
        assertSame(publisher, controller.publisher)
        return publisher
    }

    /** A real SessionController, built as MainActivity builds it, over fakes and Robolectric's SharedPreferences. */
    private fun controller(settings: ConnectionSettings, geometry: CaptureGeometry): SessionController {
        val context = RuntimeEnvironment.getApplication()
        return SessionController(
            backend = backend,
            events = SmokeEvents(),
            newPublisher = { listener -> FakePublisher(listener, geometry, SOURCE).also { publishers += it } },
            signals = SilentSignals(),
            diagnostics = DiagnosticsLog(clock = { 0L }, sink = { _, _, _ -> }),
            pendingLost = SharedPreferencesPendingLostStore(context),
            lostDeliveries = LostDeliveries(),
            serverKey = lostServerKey(settings.apiBaseUrl),
            drills = SharedPreferencesDrillSelectionStore(context),
            orientationLock = { locked -> locks += locked },
            appScope = appScope,
            uiScope = uiScope,
            isMainThread = { Looper.myLooper() == Looper.getMainLooper() },
            elapsedRealtime = SystemClock::elapsedRealtime,
            wallClock = { START },
            isScreenVisible = { true },
            build = BUILD,
            sourceId = SOURCE,
            initial = AppState(),
        ).also { it.start() }
    }

    /**
     * The rail is a column on the right in landscape and a band along the bottom in portrait, so
     * the primary button sits in the window's right third or bottom third. The landscape column
     * is centred vertically, so in a tall window it would fail the portrait rule.
     */
    private fun assertRailPlacement(orientation: Int, action: PrimaryAction) {
        val window = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val button = primary(action).fetchSemanticsNode().boundsInRoot
        val label = action.label
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            assertTrue("$window", window.width > window.height)
            assertTrue("$label at $button in $window", button.center.x > window.width * 2 / 3)
        } else {
            assertTrue("$window", window.height > window.width)
            assertTrue("$label at $button in $window", button.center.y > window.height * 2 / 3)
        }
    }

    private fun geometryFor(orientation: Int) =
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) LANDSCAPE_GEOMETRY else PORTRAIT_GEOMETRY

    /** Answers every call at once and records its name; the controller's own logic does the rest. */
    private class SmokeBackend : ChallengeBackend {
        val calls = mutableListOf<String>()
        private var blocks = 0

        override suspend fun createSession(challengeId: String): String {
            calls += "createSession"
            return "session-1"
        }

        override suspend fun createBlock(sessionId: String, selection: BlockSelection): String {
            calls += "createBlock"
            return "block-${++blocks}"
        }

        override suspend fun markReacquired(sessionId: String, blockId: String) {
            calls += "markReacquired"
        }

        override suspend fun pausePractice(sessionId: String, blockId: String) {
            calls += "pausePractice"
        }

        override suspend fun resumePractice(sessionId: String, blockId: String) {
            calls += "resumePractice"
        }

        override suspend fun attachCapture(sessionId: String, sourceId: String, epoch: SourceEpoch): String {
            calls += "attachCapture"
            return "capture-1"
        }

        override suspend fun reportSourceHealth(captureId: String, status: String, detail: String) {
            calls += "health:$status"
        }

        override suspend fun logManualAttempt(
            sessionId: String, blockId: String, captureId: String, sourceId: String, epoch: SourceEpoch,
            occurredAt: String, window: ManualEvidenceWindow,
        ): String {
            calls += "logManualAttempt"
            return "event-1"
        }

        override suspend fun clientInfo(captureId: String, body: ByteArray): ClientInfoResult {
            calls += "clientInfo"
            return ClientInfoResult.STORED
        }

        override suspend fun health() = ServerHealth(reachable = true, release = null)
        override suspend fun qualityReport(captureId: String): FetchResult<String> = FetchResult.NotFound
        override suspend fun thumbnail(captureId: String, index: Int): FetchResult<ByteArray> = FetchResult.NotFound
    }

    /** A session stream that never delivers anything. */
    private class SmokeEvents : ChallengeEvents {
        override fun start(
            scope: CoroutineScope,
            sessionId: String,
            onVerdict: (MobileVerdictEvent) -> Unit,
            onCue: (AthleteCue) -> Unit,
            onCueSafe: (AthleteCue) -> Unit,
            onError: (String) -> Unit,
            onChallengeTotal: (Long) -> Unit,
            onLiveAnalysis: (LiveAnalysisStatus?) -> Unit,
            onSessionCounts: (SessionCounts) -> Unit,
        ) = Unit

        override fun stop() = Unit
    }

    private class SilentSignals : AthleteSignals {
        override fun cue(cue: FeedbackCue) = Unit
        override fun speak(text: String) = Unit
    }

    private companion object {
        /** Phone-sized windows; the rail moves from the bottom band to a right-hand column. */
        const val PORTRAIT = "w411dp-h914dp-port"
        const val LANDSCAPE = "w914dp-h411dp-land"
        const val SOURCE = "million-kicks-camera"
        val START: Instant = Instant.parse("2030-01-01T00:00:00Z")
        val BUILD = ClientBuild("0.3.0-rehearsal", 3, "abc1234", "robolectric", "smoke", 35)
        /** Synthetic RFC 2606/5737 values that pass ConnectionSettings.validationError. */
        val CONFIGURED = ConnectionSettings(
            apiBaseUrl = "https://api.example.test",
            srtHost = "192.0.2.10:8890",
            srtPassphrase = "synthetic-smoke-key",
            publishPassword = "synthetic-publish",
        )
        /** Display rotation 0 and 1 with the usual back-camera sensor at 90 degrees. */
        val PORTRAIT_GEOMETRY: CaptureGeometry = CaptureGeometry.forDisplayRotation(0, 90).getOrThrow()
        val LANDSCAPE_GEOMETRY: CaptureGeometry = CaptureGeometry.forDisplayRotation(1, 90).getOrThrow()
    }
}
