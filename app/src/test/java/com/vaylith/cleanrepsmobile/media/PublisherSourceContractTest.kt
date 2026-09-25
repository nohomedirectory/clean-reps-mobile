package com.vaylith.cleanrepsmobile.media

import android.graphics.Bitmap
import android.view.SurfaceView
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.vaylith.cleanrepsmobile.model.SourceEpoch
import com.vaylith.cleanrepsmobile.ui.PreviewLayout
import com.vaylith.cleanrepsmobile.ui.PreviewMode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Reads the publisher's sources and fails on the patterns behind the black
 * preview and the sideways video, so they cannot come back unnoticed. Comments
 * and string contents are removed first, so documentation that names a
 * forbidden call does not count and code inside a string cannot hide one.
 */
class PublisherSourceContractTest {
    private val mainRoot = listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
        ?: error("main sources not found from ${File(".").absolutePath}")
    private val mediaDir = File(mainRoot, "com/vaylith/cleanrepsmobile/media")
    private val media: Map<String, String> = mediaDir.listFiles { file -> file.extension == "kt" }!!
        .associate { it.name to code(it.readText()) }
    private val publisher = media.getValue("CanonicalSourcePublisher.kt")
    private val coordinator = media.getValue("PreviewCoordinator.kt")

    @Test fun `every startPreview call uses the Surface overload with a size and no boolean`() {
        val calls = media.flatMap { (file, text) -> calls(text, ".startPreview").map { file to it } }
        assertTrue("expected startPreview calls in media/", calls.isNotEmpty())
        calls.forEach { (file, args) ->
            // The SurfaceView and TextureView overloads take one or two arguments; the
            // two-argument form with `true` installs the callback that went black.
            assertEquals("$file startPreview$args", 3, args.size)
            assertTrue("$file startPreview$args", args.none { it == "true" || it == "false" })
        }
    }

    @Test fun `the stream is never asked to remove its preview callbacks`() {
        media.forEach { (file, text) ->
            calls(text, ".stopPreview").forEach { args -> assertFalse("$file stopPreview$args", "true" in args) }
            calls(text, "stream.stopPreview").forEach { args -> assertEquals("$file stream.stopPreview$args", listOf("false"), args) }
        }
    }

    @Test fun `the preview is filled and never stretched`() {
        media.forEach { (file, text) -> assertFalse(file, "AspectRatioMode.NONE" in text) }
        // The draw mode comes from the one OD-3 constant that also sizes the preview view, and it is Fill.
        val modes = calls(publisher, "setAspectRatioMode")
        assertEquals(listOf(listOf("PreviewLayout.DEFAULT_MODE.glAspectRatioMode()")), modes)
        assertEquals(AspectRatioMode.Fill, PreviewLayout.DEFAULT_MODE.glAspectRatioMode())
        assertEquals(AspectRatioMode.Fill, PreviewMode.FILL.glAspectRatioMode())
        assertEquals(AspectRatioMode.Adjust, PreviewMode.FIT.glAspectRatioMode())
        assertTrue(PreviewMode.entries.none { it.glAspectRatioMode() == AspectRatioMode.NONE })
    }

    @Test fun `video is prepared at 1280x720 with the upright rotation argument`() {
        val prepares = media.flatMap { (_, text) -> calls(text, "stream.prepareVideo") }
        assertEquals(1, prepares.size)
        val args = prepares.single()
        assertEquals(6, args.size)
        assertEquals(listOf("CaptureGeometry.PREPARE_WIDTH", "CaptureGeometry.PREPARE_HEIGHT"), args.take(2))
        assertEquals("geometry.rotationArg", args.last())
    }

    @Test fun `Go live passes the state machine before startStream (I8)`() {
        val start = body(publisher, "override suspend fun start(")
        val gate = start.indexOf("coordinator.goLive()")
        val startStream = start.indexOf("stream.startStream(")
        assertTrue("goLive at $gate, startStream at $startStream", gate in 0 until startStream)
    }

    @Test fun `preview code never reports through the transport channel`() {
        listOf("PreviewCoordinator.kt", "PreviewSurfaceBinder.kt", "SrtStreamEncoderPort.kt", "PreviewStateMachine.kt").forEach {
            assertFalse(it, "onPublisherStatus" in media.getValue(it))
        }
        listOf("override fun attachPreview(", "override fun releasePreview(").forEach {
            assertFalse(it, "onPublisherStatus" in body(publisher, it))
        }
        media.forEach { (file, text) -> assertFalse(file, "PublisherStatus.PREVIEW_READY" in text) }
        assertFalse("previewAttached" in publisher)
        // M6a deleted the constant with MainActivity's exhaustive `when`: a preview status cannot be a transport status.
        assertFalse(Regex("""\bPREVIEW_READY\b""").containsMatchIn(body(publisher, "enum class PublisherStatus")))
    }

    @Test fun `every publisher member used outside media is declared on the interface`() {
        val declared = Regex("""\b(?:fun|val)\s+(\w+)""").findAll(body(publisher, "interface CanonicalSourcePublisher"))
            .map { it.groupValues[1] }.toSet()
        val used = mainRoot.walkTopDown()
            .filter { it.extension == "kt" && !it.startsWith(mediaDir) }
            .flatMap { Regex("""\bpublisher(?:\.|::)(\w+)""").findAll(code(it.readText())).map { match -> match.groupValues[1] } }
            .toSet()
        assertTrue("MainActivity uses the publisher", used.isNotEmpty())
        assertTrue("used $used, declared $declared", declared.containsAll(used))
        // And a JVM fake can implement it without any RootEncoder object.
        val fake: CanonicalSourcePublisher = FakePublisher()
        assertNull(fake.preparedGeometry)
    }

    private class FakePublisher : CanonicalSourcePublisher {
        override suspend fun start(epoch: SourceEpoch): PublisherResult = PublisherResult.Connecting("million-kicks-camera")
        override suspend fun stop() = Unit
        override val isAvailable = true
        override fun attachPreview(view: SurfaceView) = Unit
        override fun releasePreview() = Unit
        override val preparedGeometry: CaptureGeometry? = null
        override val sensorOrientationDeg: Int? = 90
        override fun reopenCamera() = Unit
        override fun frameCheck(callback: (Bitmap?, Int, Int) -> Unit) = callback(null, 0, 0)
        override fun release() = Unit
    }

    @Test fun `M4c members are declared on the interface, so controller and UI tests can fake them`() {
        val declared = Regex("""\bfun\s+(\w+)""").findAll(body(publisher, "interface CanonicalSourcePublisher"))
            .map { it.groupValues[1] }.toSet()
        assertTrue("$declared", declared.containsAll(listOf("reopenCamera", "frameCheck", "release")))
        var answered: Triple<Bitmap?, Int, Int>? = null
        FakePublisher().frameCheck { frame, width, height -> answered = Triple(frame, width, height) }
        assertEquals(Triple<Bitmap?, Int, Int>(null, 0, 0), answered)
    }

    @Test fun `no publisher failure is swallowed without a diagnostics record`() {
        media.forEach { (file, text) -> assertFalse("$file uses runCatching", "runCatching" in text) }
        // The pure state machine has no log; the next test covers its catch sites.
        val sites = media.filterKeys { it != "PreviewStateMachine.kt" }
            .flatMap { (file, text) -> catchSites(text).map { file to it } }
        assertTrue("found ${sites.size} catch sites", sites.size >= 9)
        sites.forEach { (file, site) ->
            assertTrue("$file: catch (${site.parameter}) ${site.body} records no diagnostics failure", logsFailure(media.getValue(file), site))
        }
    }

    @Test fun `every port call the pure state machine catches is logged by the coordinator first`() {
        val machine = media.getValue("PreviewStateMachine.kt")
        assertFalse("the state machine stays pure", "diagnostics" in machine)
        val caught = blocksAfter(machine, "try").flatMap { block ->
            Regex("""\bport\.(\w+)\(""").findAll(block).map { it.groupValues[1] }
        }.toSet()
        assertEquals(setOf("prepareVideo", "prepareAudio", "startPreview"), caught)
        val loggingPort = body(coordinator, "inner class LoggingPort")
        caught.forEach { name ->
            val override = loggingPort.split("override fun ").firstOrNull { it.startsWith("$name(") }
            assertNotNull("LoggingPort does not override $name", override)
            assertTrue("LoggingPort.$name is not logged", "logged(" in override!!)
        }
    }

    @Test fun `exception text reaches only the redacting diagnostics log`() {
        val reads = media.flatMap { (file, text) -> messageReads(text).map { file to it } }
        // The one read is CaptureGeometry's own fixed validation text (e.g. "Unsupported
        // camera orientation (0 degrees)"), never a library's or the system's message.
        assertEquals(listOf("PreviewCoordinator.kt"), reads.map { it.first })
        val geometryFor = body(coordinator, "private fun geometryFor(")
        assertEquals(1, messageReads(geometryFor).size)
        assertTrue("CaptureGeometry.forDisplayRotation(" in geometryFor)
    }

    @Test fun `camera callbacks reach only the preview coordinator, never the transport`() {
        val callbacks = calls(publisher, ".setCameraCallback").single().single()
        listOf("listener.", "onPublisherStatus", "onSourceDiscontinuity", "intentionallyStopped", "stopStream", "stopRecord")
            .forEach { assertFalse("camera callbacks use $it", it in callbacks) }
        listOf("coordinator.cameraError(", "coordinator.cameraOpened()", "coordinator.cameraDisconnected()")
            .forEach { assertTrue("camera callbacks miss $it", it in callbacks) }
        // Camera callbacks come from RootEncoder's camera thread; the coordinator runs on the main thread.
        assertEquals(3, Regex("""mainThread\.post\s*\{\s*coordinator\.""").findAll(callbacks).count())
        listOf("fun cameraError(", "fun cameraDisconnected(", "private fun cameraFailed(").forEach {
            assertFalse(it, "listener." in body(coordinator, it))
        }
    }

    @Test fun `Frame check renders the whole transmitted frame through takePhoto and GL errors are logged`() {
        assertTrue(Regex("""getGlInterface\(\)\.takePhoto\s*[({]""").containsMatchIn(body(publisher, "override fun frameCheck(")))
        assertTrue("diagnostics?.fail(" in calls(publisher, ".setRenderErrorCallback").single().single())
    }

    @Test fun `release ends the capture graph in order before unregistering the callbacks`() {
        assertInOrder(body(publisher, "override fun release("), "coordinator.release(port)", "binder.uninstall()", "rotationWatcher.stop()")
        assertInOrder(
            body(coordinator, "fun release(control: StreamControl)"),
            "control.stopRecord()", "control.stopStream()", "port.stopPreview()", "control.release()",
        )
    }

    @Test fun `the logging rules catch swallowed failures and exception text`() {
        val bad = code(
            """
            fun a() { try { x() } catch (_: Exception) { } }
            fun b() { try { x() } catch (error: Exception) { listener.onPublisherStatus(ERROR, "failed") } }
            fun c() { try { x() } catch (error: Exception) { diagnostics?.fail(step, "failed") } }
            fun d() { try { x() } catch (error: Exception) { diagnostics?.fail(step, "failed", error) } }
            fun e() { try { x() } catch (error: Exception) { report(error) } }
            private fun report(error: Exception) { diagnostics?.fail(step, "failed", error) }
            """.trimIndent(),
        )
        assertEquals(listOf(false, false, false, true, true), catchSites(bad).map { logsFailure(bad, it) })
        assertEquals(1, messageReads(code("val reason = \"failed: \${error.message}\" + StepMessages.message(step, kind)")).size)
        assertEquals(0, messageReads(code("// error.message in a comment\nval text = \"error.message in a string\"")).size)
    }

    private data class CatchSite(val parameter: String, val body: String)

    private fun catchSites(text: String): List<CatchSite> =
        Regex("""\bcatch\s*\(\s*(\w+)\s*:""").findAll(text).map { match ->
            val open = text.indexOf('{', match.range.last)
            CatchSite(match.groupValues[1], text.substring(open, matching(text, open, '{', '}') + 1))
        }.toList()

    /**
     * The catch passes its exception to `diagnostics?.fail`, directly or through
     * one helper in the same file whose body calls it.
     */
    private fun logsFailure(text: String, site: CatchSite): Boolean {
        if (site.parameter == "_") return false
        if (calls(site.body, "diagnostics?.fail").any { it.lastOrNull() == site.parameter }) return true
        return Regex("""\b(\w+)\(\s*${Regex.escape(site.parameter)}\s*\)""").findAll(site.body).any { helper ->
            val header = "fun ${helper.groupValues[1]}("
            header in text && "diagnostics?.fail(" in body(text, header)
        }
    }

    /** Reads of a throwable's `message` or `localizedMessage` (not calls such as `StepMessages.message(`). */
    private fun messageReads(text: String): List<Int> =
        Regex("""\.(?:message|localizedMessage)\b(?!\s*\()""").findAll(text).map { it.range.first }.toList()

    /** The braced blocks that follow every [keyword] (e.g. each `try { ... }`). */
    private fun blocksAfter(text: String, keyword: String): List<String> =
        Regex("""\b$keyword\s*\{""").findAll(text).map { match ->
            val open = match.range.last
            text.substring(open, matching(text, open, '{', '}') + 1)
        }.toList()

    private fun assertInOrder(text: String, vararg parts: String) {
        val positions = parts.map { text.indexOf(it) }
        assertTrue("${parts.toList()} at $positions", positions.none { it < 0 } && positions == positions.sorted())
    }

    @Test fun `the contract rules catch the forbidden forms`() {
        val bad = code(
            """
            fun attach(view: SurfaceView) {
                // stream.startPreview(surface, width, height) in a comment does not count
                stream.startPreview(view, true)
                stream.startPreview(view)
                stream.stopPreview(true)
                glInterface.setAspectRatioMode(AspectRatioMode.NONE)
                val text = "stream.startPreview(surface, 1, 2)"
            }
            """.trimIndent(),
        )
        assertEquals(listOf(listOf("view", "true"), listOf("view")), calls(bad, ".startPreview"))
        assertEquals(listOf(listOf("true")), calls(bad, ".stopPreview"))
        assertTrue("AspectRatioMode.NONE" in bad)
    }

    /** The argument lists of every call to [callee] (a receiver-qualified name) in [text]. */
    private fun calls(text: String, callee: String): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var index = text.indexOf("$callee(")
        while (index >= 0) {
            val open = index + callee.length
            val close = matching(text, open, '(', ')')
            result += splitTopLevel(text.substring(open + 1, close))
            index = text.indexOf("$callee(", close)
        }
        return result
    }

    /** The braced body that follows the first occurrence of [header]. */
    private fun body(text: String, header: String): String {
        val start = text.indexOf(header)
        assertTrue("missing $header", start >= 0)
        val open = text.indexOf('{', start)
        return text.substring(open, matching(text, open, '{', '}') + 1)
    }

    private fun matching(text: String, open: Int, opening: Char, closing: Char): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                opening -> depth++
                closing -> if (--depth == 0) return i
            }
        }
        error("unbalanced $opening at $open")
    }

    private fun splitTopLevel(arguments: String): List<String> {
        if (arguments.isBlank()) return emptyList()
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
        return parts.filter(String::isNotEmpty)
    }

    /**
     * Kotlin source with comments removed and string contents blanked (string
     * template expressions stay code), so the rules see only code.
     */
    private fun code(source: String): String {
        val out = StringBuilder()
        // Each entry is an open string (its delimiter) or a template's brace depth.
        val stack = ArrayDeque<Any>()
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val top = stack.lastOrNull()
            if (top is String) {
                when {
                    top == "\"" && c == '\\' -> i += 2
                    source.startsWith(top, i) -> { out.append(top); stack.removeLast(); i += top.length }
                    source.startsWith("\${", i) -> { out.append("\${"); stack.addLast(1); i += 2 }
                    else -> i++
                }
                continue
            }
            when {
                source.startsWith("//", i) -> i = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                source.startsWith("/*", i) -> i = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                source.startsWith("\"\"\"", i) -> { out.append("\"\"\""); stack.addLast("\"\"\""); i += 3 }
                c == '"' -> { out.append('"'); stack.addLast("\""); i++ }
                c == '\'' -> {
                    // A character literal, possibly escaped.
                    val end = source.indexOf('\'', if (source.getOrNull(i + 1) == '\\') i + 3 else i + 2)
                    out.append("' '")
                    i = if (end < 0) source.length else end + 1
                }
                top is Int && c == '{' -> { stack[stack.lastIndex] = top + 1; out.append(c); i++ }
                top is Int && c == '}' -> {
                    out.append(c)
                    if (top == 1) stack.removeLast() else stack[stack.lastIndex] = top - 1
                    i++
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
