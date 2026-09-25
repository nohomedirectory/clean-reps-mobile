package com.vaylith.cleanrepsmobile.model

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ClientIdentityTest {
    private fun identity(
        appVersionName: String = "0.3.0-rehearsal",
        appGitSha: String = "1051ea5",
        deviceManufacturer: String = "ExampleCo",
        deviceModel: String = "Example Phone 7 (2024)",
        sentAt: Instant = Instant.parse("2026-09-25T11:32:15.378Z"),
    ) = ClientIdentity(
        appVersionName = appVersionName,
        appVersionCode = 3,
        appGitSha = appGitSha,
        deviceManufacturer = deviceManufacturer,
        deviceModel = deviceModel,
        androidSdkInt = 34,
        cameraSensorOrientationDeg = 90,
        displayRotationDeg = 0,
        captureOrientation = CaptureOrientation.PORTRAIT,
        encodedWidth = 720,
        encodedHeight = 1280,
        sentAt = sentAt,
    )

    private fun ClientIdentity.text() = body().toString(Charsets.UTF_8)

    @Test fun `body is the exact C2 JSON with every key in contract order`() {
        assertEquals(
            """{"appVersionName":"0.3.0-rehearsal","appVersionCode":3,"appGitSha":"1051ea5",""" +
                """"deviceManufacturer":"ExampleCo","deviceModel":"Example Phone 7 (2024)","androidSdkInt":34,""" +
                """"cameraSensorOrientationDeg":90,"displayRotationDeg":0,"captureOrientation":"portrait",""" +
                """"encodedWidth":720,"encodedHeight":1280,"sentAt":"2026-09-25T11:32:15.378Z"}""",
            identity().text(),
        )
        assertEquals(C2_KEYS, Regex(""""([A-Za-z]+)":""").findAll(identity().text()).map { it.groupValues[1] }.toList())
        assertTrue(identity().copy(captureOrientation = CaptureOrientation.LANDSCAPE).text().contains(""""captureOrientation":"landscape""""))
    }

    @Test fun `identical inputs give byte-identical bodies so a retry re-sends the same bytes`() {
        val first = identity()
        assertArrayEquals(first.body(), identity().body())
        assertArrayEquals(first.body(), first.body())
        assertFalse(first.body().contentEquals(identity(sentAt = Instant.parse("2026-09-25T11:32:15.379Z")).body()))
    }

    @Test fun `maximal inputs stay within 4 KB and keep the typed C2 shape`() {
        val huge = "M".repeat(100_000)
        val maximal = ClientIdentity(
            appVersionName = huge,
            appVersionCode = Int.MAX_VALUE,
            appGitSha = "f".repeat(40),
            deviceManufacturer = huge,
            deviceModel = huge,
            androidSdkInt = Int.MAX_VALUE,
            cameraSensorOrientationDeg = 270,
            displayRotationDeg = 270,
            captureOrientation = CaptureOrientation.LANDSCAPE,
            encodedWidth = Int.MAX_VALUE,
            encodedHeight = Int.MAX_VALUE,
            sentAt = Instant.parse("9999-12-31T23:59:59.999999999Z"),
        )
        val body = maximal.body()
        assertTrue("body was ${body.size} bytes", body.size <= MAX_CLIENT_INFO_BYTES)
        assertTrue(maximal.text(), C2_SHAPE.matches(maximal.text()))
        assertTrue(maximal.text().contains(""""deviceModel":"${"M".repeat(MAX_IDENTITY_TEXT_LENGTH)}""""))
        assertFalse(maximal.text().contains("M".repeat(MAX_IDENTITY_TEXT_LENGTH + 1)))
        assertTrue(maximal.text().contains(""""sentAt":"9999-12-31T23:59:59.999Z""""))
    }

    @Test fun `URL-like, credential and host values never appear in the body`() {
        // One planted value per detector branch, each a synthetic placeholder.
        val planted = listOf(
            "rtsp://fixture.example.test:8554/cam",
            "fixture-user@example.test",
            "key=fixture-value",
            "Passphrase fixture-value",
            "MEDIAMTX_PUBLISH_PASSWORD",
            "passwd fixture-value",
            "publisher-secret",
            "Bearer token fixture-value",
            "streamid publish:fixture",
            "SRT:fixture.example.test:8890",
            "192.0.2.10",
            "host.tailnet.ts.net",
        )
        val leaks = listOf(
            "://", "@", "=", "fixture", "example.test", "192.0.2", "tailnet", "key", "passphrase", "password", "passwd",
            "secret", "token", "streamid", "srt",
        )
        planted.forEach { value ->
            listOf(
                identity(appVersionName = value),
                identity(appGitSha = value),
                identity(deviceManufacturer = value),
                identity(deviceModel = value),
            ).forEach { candidate ->
                val text = candidate.text()
                leaks.forEach { leak -> assertFalse("$leak leaked from $value: $text", text.contains(leak, ignoreCase = true)) }
                assertTrue(text, C2_SHAPE.matches(text))
                assertTrue(text, text.contains("\"unknown\""))
            }
        }
    }

    @Test fun `text needing JSON escaping is reduced to a plain token`() {
        val text = identity(deviceManufacturer = "  Acme \"Phone\"\\\n\u0000\u03A9  ", deviceModel = "SM-X100/DS").text()
        assertTrue(text, text.contains(""""deviceManufacturer":"Acme _Phone_____""""))
        assertTrue(text, text.contains(""""deviceModel":"SM-X100_DS""""))
        assertTrue(text, C2_SHAPE.matches(text))
        assertTrue(identity(deviceModel = " \t ").text().contains(""""deviceModel":"unknown""""))
    }

    @Test fun `git SHA is a short lowercase hex SHA or unknown and nothing else`() {
        assertEquals("1051ea5", gitShaOrUnknown("1051ea5"))
        assertEquals("0123456789abcdef0123456789abcdef01234567", gitShaOrUnknown("0123456789abcdef0123456789abcdef01234567"))
        listOf("unknown", "", "abc12", "1051EA5", "1051ea5-dirty", "0123456789abcdef0123456789abcdef012345678").forEach {
            assertEquals(it, "unknown", gitShaOrUnknown(it))
        }
    }

    @Test fun `sentAt is millisecond ISO time in UTC`() {
        assertTrue(identity(sentAt = Instant.parse("2026-09-25T11:32:15Z")).text().endsWith(""""sentAt":"2026-09-25T11:32:15.000Z"}"""))
        assertTrue(identity(sentAt = Instant.parse("2026-09-25T11:32:15.378999999Z")).text().endsWith(""""sentAt":"2026-09-25T11:32:15.378Z"}"""))
    }

    @Test fun `impossible versions and geometry are refused`() {
        val valid = identity()
        listOf<() -> ClientIdentity>(
            { valid.copy(appVersionCode = 0) },
            { valid.copy(androidSdkInt = 0) },
            { valid.copy(cameraSensorOrientationDeg = 45) },
            { valid.copy(cameraSensorOrientationDeg = 360) },
            { valid.copy(displayRotationDeg = -90) },
            { valid.copy(encodedWidth = 0) },
            { valid.copy(encodedHeight = -1280) },
        ).forEach { build -> assertThrows(IllegalArgumentException::class.java) { build() } }
    }

    private companion object {
        val C2_KEYS = listOf(
            "appVersionName", "appVersionCode", "appGitSha", "deviceManufacturer", "deviceModel", "androidSdkInt",
            "cameraSensorOrientationDeg", "displayRotationDeg", "captureOrientation", "encodedWidth", "encodedHeight", "sentAt",
        )
        const val TOKEN = """[A-Za-z0-9 ._()+-]{1,64}"""
        val C2_SHAPE = Regex(
            """\{"appVersionName":"$TOKEN","appVersionCode":[1-9]\d*,"appGitSha":"([0-9a-f]{7,40}|unknown)",""" +
                """"deviceManufacturer":"$TOKEN","deviceModel":"$TOKEN","androidSdkInt":[1-9]\d*,""" +
                """"cameraSensorOrientationDeg":(0|90|180|270),"displayRotationDeg":(0|90|180|270),""" +
                """"captureOrientation":"(portrait|landscape)","encodedWidth":[1-9]\d*,"encodedHeight":[1-9]\d*,""" +
                """"sentAt":"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z"\}""",
        )
    }
}
