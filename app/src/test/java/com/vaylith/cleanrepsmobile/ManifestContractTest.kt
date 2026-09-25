package com.vaylith.cleanrepsmobile

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ManifestContractTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun mainActivity(): Element {
        // Gradle runs unit tests from the module directory; allow the repository root too.
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .firstOrNull { it.isFile } ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val activities = document.getElementsByTagName("activity")
        val matches = (0 until activities.length).map { activities.item(it) as Element }
            .filter { it.getAttributeNS(androidNs, "name") == ".MainActivity" }
        assertEquals("MainActivity declarations", 1, matches.size)
        return matches.single()
    }

    @Test fun `MainActivity follows the physical orientation`() {
        assertEquals("sensor", mainActivity().getAttributeNS(androidNs, "screenOrientation"))
    }

    @Test fun `rotation is handled in place instead of recreating MainActivity`() {
        val configChanges = mainActivity().getAttributeNS(androidNs, "configChanges").split('|').map(String::trim).toSet()
        listOf("orientation", "screenSize", "screenLayout", "smallestScreenSize", "keyboardHidden").forEach {
            assertTrue("configChanges $configChanges lacks $it", it in configChanges)
        }
    }
}
