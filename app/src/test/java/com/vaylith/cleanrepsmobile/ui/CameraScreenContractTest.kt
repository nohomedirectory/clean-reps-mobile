package com.vaylith.cleanrepsmobile.ui

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The full-screen camera shell (M7a) as a source and resource contract: edge to edge into the
 * display cutout, immersive bars, a filled and never stretched preview, controls inside the
 * safe insets, and a screen that depends only on the publisher interface. Comments and string
 * contents are removed from Kotlin first, so a commented-out call cannot satisfy a rule. Each
 * rule is also run on a planted reverted form, which it must reject.
 */
class CameraScreenContractTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"
    // Gradle runs unit tests from the module directory; allow the repository root too.
    private val mainDir = listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
        ?: error("src/main not found from ${File(".").absolutePath}")
    private val sources = File(mainDir, "java/com/vaylith/cleanrepsmobile")
    private val activity = code(File(sources, "MainActivity.kt").readText())
    private val ui: Map<String, String> = File(sources, "ui").listFiles { file -> file.extension == "kt" }!!.associate { it.name to code(it.readText()) }
    private val screen = ui.getValue("CameraScreen.kt")
    private val baseTheme = File(mainDir, "res/values/themes.xml").readText()
    private val v28Theme = File(mainDir, "res/values-v28/themes.xml").readText()

    @Test fun `the window lays out into the display cutout on the short edges from API 28`() {
        assertEquals("shortEdges", themeItem(v28Theme, "Theme.CleanReps", "android:windowLayoutInDisplayCutoutMode"))
        // The API 28 style must keep everything else of the base theme.
        assertEquals("Base.Theme.CleanReps", style(v28Theme, "Theme.CleanReps")?.getAttribute("parent"))
        assertEquals("Base.Theme.CleanReps", style(baseTheme, "Theme.CleanReps")?.getAttribute("parent"))
        // minSdk 26: the attribute exists only from API 28, so it stays out of res/values.
        assertFalse("windowLayoutInDisplayCutoutMode" in baseTheme)
        // The application theme is this theme and MainActivity does not override it.
        val manifest = xml(File(mainDir, "AndroidManifest.xml").readText())
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("@style/Theme.CleanReps", application.getAttributeNS(androidNs, "theme"))
        val activities = manifest.getElementsByTagName("activity")
        val main = (0 until activities.length).map { activities.item(it) as Element }.single { it.getAttributeNS(androidNs, "name") == ".MainActivity" }
        assertEquals("", main.getAttributeNS(androidNs, "theme"))
    }

    @Test fun `the theme is dark and black behind the preview, with transparent bars`() {
        val base = style(baseTheme, "Base.Theme.CleanReps") ?: error("Base.Theme.CleanReps missing")
        val parent = base.getAttribute("parent")
        assertTrue(parent, "Theme.Material" in parent && "Light" !in parent)
        assertEquals("@android:color/black", themeItem(baseTheme, "Base.Theme.CleanReps", "android:windowBackground"))
        assertEquals("@android:color/transparent", themeItem(baseTheme, "Base.Theme.CleanReps", "android:statusBarColor"))
        assertEquals("@android:color/transparent", themeItem(baseTheme, "Base.Theme.CleanReps", "android:navigationBarColor"))
    }

    @Test fun `MainActivity opts in to edge to edge and immersive bars revealed by a swipe`() {
        assertTrue("MainActivity must call WindowCompat.setDecorFitsSystemWindows(window, false) and never true", optsInToEdgeToEdge(activity))
        assertTrue("MainActivity must hide systemBars() with BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE", immersiveBySwipe(activity))
        assertTrue("FLAG_KEEP_SCREEN_ON" in activity)
        val onCreate = activity.substring(activity.indexOf("override fun onCreate("))
        assertTrue("edge to edge is set before the content", onCreate.indexOf("setDecorFitsSystemWindows(") in 0 until onCreate.indexOf("setContent"))
        // A dialog or the permission prompt can bring the bars back.
        assertTrue("hideSystemBars()" in activity.substring(activity.indexOf("override fun onWindowFocusChanged(")))
    }

    @Test fun `MainActivity is reduced to window flags, permissions, the orientation lock and CameraScreen`() {
        assertTrue(Regex("""setContent\s*\{[\s\S]*CleanRepsTheme\s*\{[\s\S]*CameraScreen\(""").containsMatchIn(activity))
        assertTrue("RequestMultiplePermissions" in activity)
        // The old scrolling form is gone from MainActivity; the preview belongs to CameraScreen.
        listOf("Scaffold(", "TopAppBar(", "AndroidView(", "SurfaceView(", "FilterChip(", "MobileScreen").forEach {
            assertFalse("MainActivity still has $it", it in activity)
        }
    }

    @Test fun `the orientation lock keeps the current orientation while live, so Fill matches the stream`() {
        // OD-7: LOCKED keeps whatever orientation the window has at Go live; SENSOR follows the phone again.
        val lock = activity.substring(activity.indexOf("orientationLock ="), activity.indexOf("appScope ="))
        assertTrue(lock, "SCREEN_ORIENTATION_LOCKED" in lock && "SCREEN_ORIENTATION_SENSOR" in lock)
        assertFalse(Regex("""SCREEN_ORIENTATION_(?:SENSOR_|USER_|REVERSE_)?(?:LANDSCAPE|PORTRAIT)\b""").containsMatchIn(activity))
        // With the lock, Fill on a 20:9 window crops only the aspect difference (135 px each side);
        // a window turned against a landscape stream would show a quarter of its width.
        assertEquals(0.8, visible(2400, 1080, 1280, 720).second, 1e-9)
        assertEquals(1.0, visible(2400, 1080, 1280, 720).first, 1e-9)
        assertEquals(0.8, visible(1080, 2400, 720, 1280).first, 1e-9)
        assertEquals(1.0, visible(1080, 2400, 720, 1280).second, 1e-9)
        assertTrue(visible(1080, 2400, 1280, 720).first < 0.26)
    }

    @Test fun `the preview fills the window and is never stretched`() {
        val view = calls(screen, "AndroidView").single()
        // The view takes previewSurface's rectangle for the default mode, in exact pixels.
        assertTrue(view.toString(), "modifier = Modifier.previewSize(constraints)" in view)
        val sizing = screen.substring(screen.indexOf("private fun Modifier.previewSize("))
        assertTrue("val surface = previewSurface(window.maxWidth, window.maxHeight)" in sizing.substringBefore("\n}"))
        // In the default FILL mode that rectangle is the whole window, for any window shape.
        assertEquals(PreviewMode.FILL, PreviewLayout.DEFAULT_MODE)
        listOf(2400 to 1080, 1080 to 2400, 1920 to 1080, 1080 to 1920, 2340 to 1080, 1000 to 1000, 2401 to 1081).forEach { (w, h) ->
            assertEquals("${w}x$h", PixelRect(0, 0, w, h), previewSurface(w, h))
        }
        val factory = view.single { it.startsWith("factory =") }
        assertTrue(factory, "setZOrderMediaOverlay(false)" in factory && "publisher.attachPreview(" in factory)
        assertEquals("onRelease = { publisher.releasePreview() }", view.single { it.startsWith("onRelease =") })
        assertTrue("one SurfaceView per publisher", Regex("""key\(publisher\)\s*\{\s*AndroidView\(""").containsMatchIn(screen))
        ui.forEach { (file, text) -> assertEquals(file, emptyList<String>(), stretches(text)) }
        // Nowhere in the app: AspectRatioMode.NONE stretches the stream to the surface.
        mainDir.walkTopDown().filter { it.extension == "kt" }.forEach { assertFalse(it.name, "AspectRatioMode.NONE" in code(it.readText())) }
    }

    @Test fun `the OD-3 constant switches both the preview view and the publisher's draw mode`() {
        // FIT gives the view a stream-aspect box at the top left, which the publisher fills with Adjust.
        assertEquals(PixelRect(0, 0, 1920, 1080), previewSurface(2400, 1080, PreviewMode.FIT))
        assertEquals(PixelRect(0, 0, 1080, 1920), previewSurface(1080, 2400, PreviewMode.FIT))
        assertEquals(PixelRect(0, 0, 1920, 1080), previewSurface(1920, 1080, PreviewMode.FIT))
        listOf(2400 to 1080, 1080 to 2400).forEach { (w, h) ->
            assertEquals(previewSurface(w, h, PreviewLayout.DEFAULT_MODE), previewSurface(w, h))
        }
        val signature = screen.substring(screen.indexOf("internal fun previewSurface("), screen.indexOf("): PixelRect"))
        assertTrue(signature, "mode: PreviewMode = PreviewLayout.DEFAULT_MODE" in signature)
        // Nothing else picks a mode: the only mode literals are M3c's constant and the publisher's mapping.
        val mains = mainDir.walkTopDown().filter { it.extension == "kt" }.map { it.name to code(it.readText()) }.toList()
        val literals = mains.filter { (_, text) -> Regex("""PreviewMode\.(FILL|FIT)\b""").containsMatchIn(text) }.map { it.first }
        assertEquals(listOf("CanonicalSourcePublisher.kt", "PreviewLayout.kt"), literals.sorted())
        assertEquals(listOf("CameraScreen.kt", "CanonicalSourcePublisher.kt"),
            mains.filter { (_, text) -> "PreviewLayout.DEFAULT_MODE" in text }.map { it.first }.sorted())
        val publisher = mains.single { it.first == "CanonicalSourcePublisher.kt" }.second
        assertTrue("setAspectRatioMode(PreviewLayout.DEFAULT_MODE.glAspectRatioMode())" in publisher)
    }

    @Test fun `the rail's reason and hint lines wrap and are never cut, and the landscape rail scrolls`() {
        val rail = ui.getValue("ControlRail.kt")
        assertEquals(emptyList<String>(), cuts(rail))
        val railLine = rail.substring(rail.indexOf("private fun RailLine("))
        assertTrue("fontSize = RailText.SIZE_SP.sp" in railLine.substringBefore("\n}"))
        val landscape = calls(rail, "Column").map { it.first() }.single { "RailText.LANDSCAPE_WIDTH_DP" in it }
        assertTrue(landscape, "verticalScroll(rememberScrollState())" in landscape)
        // The margin the portrait width budget assumes is the screen's own.
        assertTrue(Regex("""windowInsetsPadding\(\s*WindowInsets\.safeDrawing\s*\)\.padding\(RailText\.EDGE_DP\.dp\)""").containsMatchIn(screen))
        // Planted: the defect the verifier found.
        assertEquals(listOf("maxLines", "TextOverflow.Ellipsis"),
            cuts(code("Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)")))
    }

    @Test fun `the setup sheet masks both credentials and edits the connection only while stopped`() {
        val sheet = ui.getValue("SetupSheet.kt")
        assertTrue(masksCredentials(sheet))
        assertTrue("val editable = SetupRules.connectionEditable(state)" in sheet)
        val fields = calls(sheet, "OutlinedTextField")
        assertEquals(4, fields.size)
        fields.forEach { assertTrue(it.toString(), "enabled = editable" in it) }
        assertTrue(calls(sheet, "Button").any { args -> "enabled = editable" in args && args.any { it.startsWith("onClick =") && "onSave(draft)" in it } })
        // The old dialog is gone from MainActivity; the sheet is its only home.
        listOf("ConnectionDialog", "AlertDialog(", "OutlinedTextField(", "PasswordVisualTransformation").forEach { assertFalse(it, it in activity) }
        // Planted: an unmasked secret field is caught.
        assertFalse(masksCredentials(code("""
            OutlinedTextField(value = draft.srtPassphrase, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(value = draft.publishPassword, singleLine = true)
        """)))
        assertTrue(masksCredentials(code("""
            OutlinedTextField(value = draft.srtPassphrase, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(value = draft.publishPassword, visualTransformation = PasswordVisualTransformation())
        """)))
        assertFalse(masksCredentials(code("OutlinedTextField(value = draft.publishPassword, visualTransformation = VisualTransformation.None)")))
    }

    @Test fun `technique chips are enabled only by the picker model and a tap goes through withTechnique`() {
        val sheet = ui.getValue("SetupSheet.kt")
        val techniqueChip = calls(sheet, "FilterChip").single { args -> args.any { "option.technique" in it } }
        assertTrue(techniqueChip.toString(), "enabled = option.enabled" in techniqueChip)
        assertTrue(techniqueChip.toString(), techniqueChip.any { it.startsWith("onClick =") && "SetupRules.withTechnique(selection, option.technique)" in it })
        assertTrue(Regex("""SetupRules\.techniques\(state\)\.forEach""").containsMatchIn(sheet))
    }

    @Test fun `the screen maps every overflow and banner action to the same controller calls as before`() {
        listOf(
            "OverflowAction.AUDIO_TEST -> onAudioTest()",
            "OverflowAction.VOICE_HINTS -> controller.toggleVoiceHints()",
            "OverflowAction.SPEAK_VERDICTS -> controller.toggleSpeakVerdicts()",
            "OverflowAction.MANUAL_MARKER -> controller.saveManualMarker()",
            "BannerAction.REOPEN_CAMERA -> controller.reopenCamera()",
            "BannerAction.STOP_VIDEO -> controller.stopVideo()",
            "RailCommand.OPEN_CONNECTION_SETTINGS -> sheet = SetupSection.CONNECTION",
            "onSettings = { sheet = SetupSection.CONNECTION }",
            "onDrill = { sheet = SetupSection.DRILL }",
            "onSelectDrill = controller::selectDrill",
        ).forEach { assertTrue(it, it in screen) }
        // The interim M7a panels are gone.
        listOf("InterimControls", "moreOpen", "FilterChip(").forEach { assertFalse(it, it in screen) }
        // The interim M7a status panel (its own Reopen camera button) is gone; M8a's StatusOverlay of ui/StatusOverlay.kt takes a model.
        assertFalse(Regex("""fun StatusOverlay\(\s*state: AppState""").containsMatchIn(screen + ui.values.joinToString("\n")))
        assertFalse("onReopenCamera" in screen)
        // The drill picker's controls live in the sheet only.
        assertFalse("FilterChip(" in ui.getValue("ControlRail.kt"))
    }

    @Test fun `the rotation banner comes from OrientationEventListener through the quantizer, only while the video runs`() {
        val banners = ui.getValue("Banners.kt")
        val listener = banners.substring(banners.indexOf("object : OrientationEventListener("))
        assertTrue("banner = watch.reading(orientation, SystemClock.elapsedRealtime())" in listener.substringBefore("onDispose"))
        assertTrue("PhysicalOrientation(Quadrant.forDisplayRotation(geometry.displayRotation))" in banners)
        assertTrue("rememberTurnedBanner(if (state.videoRunning) publisher.preparedGeometry else null)" in screen)
        assertTrue("val banners = Banners.select(state, turned)" in screen)
    }

    @Test fun `the C3 phone texts have one table, read by the rail hint, while the chip shows a short label`() {
        val texts = setOf("Starting analysis...", "Finding you...", "Tracking you", "Step into the frame", "Head not visible - move the phone back or higher",
            "Automatic analysis supports Teep only", "Analysis status unavailable")
        val owners = mainSources.filter { (_, source) -> literals(source).any { it in texts } }.map { it.first }
        assertEquals(listOf("LiveAnalysisModels.kt"), owners)
        // Orchestrator decision: the full instruction is the rail's hint (M3c), the chip names the state.
        assertTrue("phoneText(" in ui.getValue("PrimaryActionState.kt"))
        assertFalse("phoneText(" in ui.getValue("StatusOverlay.kt"))
        // Planted: a second table is found; a text quoted in a comment is not.
        assertEquals(listOf("Tracking you"), literals("val chip = \"Tracking you\" // \"Finding you...\"\n/* \"Step into the frame\" */").filter { it in texts })
    }

    @Test fun `the Paused badge is measured before the chip label, so a long label can never squeeze it away`() {
        assertTrue(badgeKeepsItsWidth(ui.getValue("StatusOverlay.kt")))
        // Planted: an unweighted label (the acffdff form) takes the width first.
        assertFalse(badgeKeepsItsWidth(code("""
            Text(chip.text, style = MaterialTheme.typography.titleMedium, color = Color.White)
            if (chip.paused) { Text(StatusOverlayModel.PAUSED, style = MaterialTheme.typography.labelLarge) }
        """)))
    }

    @Test fun `the server is polled only while the screen is visible, for the controller in use`() {
        val overlay = ui.getValue("StatusOverlay.kt")
        assertTrue(Regex("""repeatOnLifecycle\(Lifecycle\.State\.STARTED\)\s*\{\s*pollServerHealth\(""").containsMatchIn(overlay))
        assertTrue("rememberServerHealth(controller, controller::serverHealth)" in screen)
        assertTrue("StatusOverlayModel.from(state, health)" in screen)
    }

    @Test fun `the session counts have one writer, the server snapshot`() {
        val writers = mainSources.flatMap { (file, source) -> countWriters(code(source)).map { file to it } }
        assertEquals(listOf("SessionController.kt" to "counts"), writers)
        assertTrue(Regex("""onSessionCounts\s*=\s*\{\s*counts\s*->\s*update\s*\{\s*copy\(sessionCounts = counts\)""").containsMatchIn(code(mainSources.single { it.first == "SessionController.kt" }.second)))
        // Planted: a local increment is found.
        assertEquals(listOf("sessionCounts?.copy(accepted = sessionCounts.accepted + 1"),
            countWriters(code("update { copy(sessionCounts = sessionCounts?.copy(accepted = sessionCounts.accepted + 1)) }")))
    }

    @Test fun `the framing box is drawn in window pixels with the preview's own mode`() {
        assertTrue("FramingBox(state, publisher.preparedGeometry, constraints.maxWidth, constraints.maxHeight, PreviewLayout.DEFAULT_MODE)" in screen)
        val guide = ui.getValue("FramingGuide.kt")
        assertTrue("val shown = PreviewLayout.compute(mode, windowW, windowH, streamW, streamH)" in guide)
        assertTrue("return PreviewLayout.regionOnScreen(shown, windowW, windowH, region)" in guide)
    }

    @Test fun `CameraScreen depends only on the publisher interface and the controller`() {
        val signature = screen.substring(screen.indexOf("fun CameraScreen("), screen.indexOf(") {", screen.indexOf("fun CameraScreen(")))
        assertTrue(signature, "controller: SessionController" in signature && "publisher: CanonicalSourcePublisher" in signature)
        ui.forEach { (file, text) ->
            assertFalse("$file builds a RootEncoder object", "com.pedro" in text || "MediaMtxSrtPublisher" in text)
        }
        assertTrue("MediaMtxSrtPublisher(" in activity)
    }

    @Test fun `the controls keep to the safeDrawing insets`() {
        assertTrue(Regex("""windowInsetsPadding\(\s*WindowInsets\.safeDrawing\s*\)""").containsMatchIn(screen))
        assertTrue(Regex("""ControlRail\(""").containsMatchIn(screen))
    }

    @Test fun `the contract rules reject reverted forms (planted)`() {
        assertFalse(optsInToEdgeToEdge(code("WindowCompat.setDecorFitsSystemWindows(window, true)")))
        assertFalse(optsInToEdgeToEdge(code("// WindowCompat.setDecorFitsSystemWindows(window, false)")))
        assertFalse(optsInToEdgeToEdge(code("val note = \"WindowCompat.setDecorFitsSystemWindows(window, false)\"")))
        assertFalse(optsInToEdgeToEdge(code("WindowCompat.setDecorFitsSystemWindows(window, false)\nWindowCompat.setDecorFitsSystemWindows(window, true)")))
        assertTrue(optsInToEdgeToEdge(code("WindowCompat.setDecorFitsSystemWindows(window, false)")))
        assertFalse(immersiveBySwipe(code("controller.hide(WindowInsetsCompat.Type.systemBars())")))
        assertFalse(immersiveBySwipe(code("controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE")))
        val reverted = """<resources><style name="Theme.CleanReps" parent="Base.Theme.CleanReps">
            <item name="android:windowLayoutInDisplayCutoutMode">default</item></style></resources>"""
        assertEquals("default", themeItem(reverted, "Theme.CleanReps", "android:windowLayoutInDisplayCutoutMode"))
        assertNull(themeItem("""<resources><style name="Theme.CleanReps" parent="Base.Theme.CleanReps" /></resources>""",
            "Theme.CleanReps", "android:windowLayoutInDisplayCutoutMode"))
        assertEquals(listOf("aspectRatio(", "ContentScale.FillBounds", "scaleX"),
            stretches(code("Modifier.aspectRatio(16f / 9f); ContentScale.FillBounds; graphicsLayer { scaleX = 2f }")))
    }

    /** The visible fraction (x, y) of a filled stream on a window, from M3c's PreviewLayout. */
    private fun visible(windowW: Int, windowH: Int, streamW: Int, streamH: Int): Pair<Double, Double> {
        val shown = PreviewLayout.compute(PreviewMode.FILL, windowW, windowH, streamW, streamH)
        return minOf(1.0, windowW.toDouble() / shown.width) to minOf(1.0, windowH.toDouble() / shown.height)
    }

    private fun optsInToEdgeToEdge(text: String) =
        Regex("""WindowCompat\.setDecorFitsSystemWindows\(\s*window\s*,\s*false\s*\)""").containsMatchIn(text) &&
            !Regex("""setDecorFitsSystemWindows\([^)]*\btrue\b""").containsMatchIn(text)

    private fun immersiveBySwipe(text: String) =
        Regex("""systemBarsBehavior\s*=\s*WindowInsetsControllerCompat\.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE""").containsMatchIn(text) &&
            Regex("""\bhide\(\s*WindowInsetsCompat\.Type\.systemBars\(\)\s*\)""").containsMatchIn(text)

    /** Modifiers and scalings that would distort the preview instead of letting RootEncoder fill it. */
    private fun stretches(text: String): List<String> =
        listOf("aspectRatio(", "ContentScale.FillBounds", "ContentScale.Crop", "scaleX", "scaleY", "setScaleX(", "setScaleY(", "AspectRatioMode")
            .filter { it in text }

    /** Every text field for the video passphrase or the publish password is masked, and there is at least one of each. */
    private fun masksCredentials(text: String): Boolean {
        val secrets = calls(text, "OutlinedTextField").filter { args -> args.any { "srtPassphrase" in it || "publishPassword" in it } }
        val both = listOf("srtPassphrase", "publishPassword").all { name -> secrets.any { args -> args.any { name in it } } }
        return both && secrets.all { "visualTransformation = PasswordVisualTransformation()" in it } && "VisualTransformation.None" !in text
    }

    /**
     * The chip label is a weighted, non-filling child, so Compose measures the Paused badge (unweighted)
     * first and gives the label only what is left; the badge follows the label in the row.
     */
    private fun badgeKeepsItsWidth(text: String): Boolean {
        val label = calls(text, "Text").firstOrNull { args -> args.firstOrNull() == "chip.text" } ?: return false
        val badge = text.indexOf("Text(StatusOverlayModel.PAUSED")
        return label.any { it.replace(" ", "") == "modifier=Modifier.weight(1f,fill=false)" } && badge > text.indexOf("Text(chip.text")
    }

    /** Every main Kotlin file: its name and raw source. */
    private val mainSources: List<Pair<String, String>> by lazy {
        mainDir.walkTopDown().filter { it.extension == "kt" }.map { it.name to it.readText() }.toList()
    }

    /** The right-hand sides of every assignment to `sessionCounts` in comment- and string-free [text]. */
    private fun countWriters(text: String): List<String> =
        Regex("""\bsessionCounts\s*=(?!=)\s*([^\n]*)""").findAll(text).map { it.groupValues[1].trimEnd(' ', ')', '}', ',') }.toList()

    /** The contents of the string literals of Kotlin [source], without comments. */
    private fun literals(source: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> i = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                source.startsWith("/*", i) -> i = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it }
                    out += source.substring(i + 3, end)
                    i = end + 3
                }
                source[i] == '"' || source[i] == '\'' -> {
                    val quote = source[i]
                    var j = i + 1
                    while (j < source.length && source[j] != quote) j += if (source[j] == '\\') 2 else 1
                    if (quote == '"') out += source.substring(i + 1, minOf(j, source.length))
                    i = j + 1
                }
                else -> i++
            }
        }
        return out
    }

    /** Settings that would cut a line of text short instead of letting it wrap. */
    private fun cuts(text: String): List<String> =
        listOf("maxLines", "TextOverflow.Ellipsis", "TextOverflow.Clip", "softWrap = false").filter { it in text }

    private fun xml(text: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(InputSource(StringReader(text)))

    private fun style(themes: String, name: String): Element? {
        val styles = xml(themes).getElementsByTagName("style")
        return (0 until styles.length).map { styles.item(it) as Element }.singleOrNull { it.getAttribute("name") == name }
    }

    private fun themeItem(themes: String, styleName: String, item: String): String? {
        val items = style(themes, styleName)?.getElementsByTagName("item") ?: return null
        return (0 until items.length).map { items.item(it) as Element }.singleOrNull { it.getAttribute("name") == item }?.textContent?.trim()
    }

    /** The top-level argument texts of every call to [callee] in [text]. */
    private fun calls(text: String, callee: String): List<List<String>> =
        Regex("""\b${Regex.escape(callee)}\(""").findAll(text).map { match ->
            val open = match.range.last
            splitTopLevel(text.substring(open + 1, matching(text, open)))
        }.toList()

    private fun matching(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> if (--depth == 0) return i
            }
        }
        error("unbalanced bracket at $open")
    }

    private fun splitTopLevel(arguments: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        arguments.forEachIndexed { i, c ->
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    parts += arguments.substring(start, i).trim()
                    start = i + 1
                }
            }
        }
        parts += arguments.substring(start).trim()
        return parts.filter(String::isNotEmpty).map { it.replace(Regex("""\s+"""), " ") }
    }

    /** Kotlin source without comments, with string and character contents blanked. */
    private fun code(source: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> i = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                source.startsWith("/*", i) -> i = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                source.startsWith("\"\"\"", i) -> {
                    out.append("\"\"\"\"\"\"")
                    i = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it + 3 }
                }
                source[i] == '"' || source[i] == '\'' -> {
                    val quote = source[i]
                    var j = i + 1
                    while (j < source.length && source[j] != quote) j += if (source[j] == '\\') 2 else 1
                    out.append(quote).append(quote)
                    i = j + 1
                }
                else -> out.append(source[i++])
            }
        }
        return out.toString()
    }
}
