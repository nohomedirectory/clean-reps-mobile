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
        assertTrue(view.toString(), "modifier = Modifier.fillMaxSize()" in view)
        val factory = view.single { it.startsWith("factory =") }
        assertTrue(factory, "setZOrderMediaOverlay(false)" in factory && "publisher.attachPreview(" in factory)
        assertEquals("onRelease = { publisher.releasePreview() }", view.single { it.startsWith("onRelease =") })
        assertTrue("one SurfaceView per publisher", Regex("""key\(publisher\)\s*\{\s*AndroidView\(""").containsMatchIn(screen))
        ui.forEach { (file, text) -> assertEquals(file, emptyList<String>(), stretches(text)) }
        // Nowhere in the app: AspectRatioMode.NONE stretches the stream to the surface.
        mainDir.walkTopDown().filter { it.extension == "kt" }.forEach { assertFalse(it.name, "AspectRatioMode.NONE" in code(it.readText())) }
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
