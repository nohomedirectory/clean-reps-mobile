package com.vaylith.cleanrepsmobile.model

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class CaptureOrientation(val wireValue: String) { PORTRAIT("portrait"), LANDSCAPE("landscape") }

/**
 * Per-capture client identity: the body of clean-reps contract C2,
 * `POST /v1/captures/{captureId}/client-info`. Pure; the caller supplies the
 * build, device and camera values. The server answers 409 to a different body
 * for the same capture, so the caller builds [body] once per capture and
 * re-sends exactly those bytes on retry.
 *
 * Text values come from the build or the device, so each is reduced to a short
 * plain token before it is sent. A value that looks like a URL, a credential or
 * a network host becomes `unknown` as a whole, never partially stripped; any
 * other character outside `[A-Za-z0-9 ._()+-]` becomes `_`. No JSON escaping is
 * therefore ever needed, and nothing but the listed fields is sent.
 */
data class ClientIdentity(
    val appVersionName: String,
    val appVersionCode: Int,
    val appGitSha: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidSdkInt: Int,
    val cameraSensorOrientationDeg: Int,
    val displayRotationDeg: Int,
    val captureOrientation: CaptureOrientation,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val sentAt: Instant,
) {
    init {
        require(appVersionCode > 0) { "app version code must be positive" }
        require(androidSdkInt > 0) { "Android SDK level must be positive" }
        require(cameraSensorOrientationDeg in RIGHT_ANGLES) { "camera sensor orientation must be 0, 90, 180 or 270" }
        require(displayRotationDeg in RIGHT_ANGLES) { "display rotation must be 0, 90, 180 or 270" }
        require(encodedWidth > 0 && encodedHeight > 0) { "encoded size must be positive" }
    }

    /** UTF-8 JSON in the contract's key order; identical inputs give identical bytes. */
    fun body(): ByteArray {
        val fields = listOf(
            "appVersionName" to quotedToken(appVersionName),
            "appVersionCode" to appVersionCode.toString(),
            "appGitSha" to "\"${gitShaOrUnknown(appGitSha)}\"",
            "deviceManufacturer" to quotedToken(deviceManufacturer),
            "deviceModel" to quotedToken(deviceModel),
            "androidSdkInt" to androidSdkInt.toString(),
            "cameraSensorOrientationDeg" to cameraSensorOrientationDeg.toString(),
            "displayRotationDeg" to displayRotationDeg.toString(),
            "captureOrientation" to "\"${captureOrientation.wireValue}\"",
            "encodedWidth" to encodedWidth.toString(),
            "encodedHeight" to encodedHeight.toString(),
            "sentAt" to "\"${SENT_AT_FORMAT.format(sentAt)}\"",
        )
        val bytes = fields.joinToString(",", "{", "}") { (key, value) -> "\"$key\":$value" }
            .toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_CLIENT_INFO_BYTES) { "client-info body exceeds $MAX_CLIENT_INFO_BYTES bytes" }
        return bytes
    }
}

/** C2 accepts at most 4 KB. */
const val MAX_CLIENT_INFO_BYTES = 4096
internal const val MAX_IDENTITY_TEXT_LENGTH = 64

private const val UNKNOWN = "unknown"
private val RIGHT_ANGLES = setOf(0, 90, 180, 270)
private val GIT_SHA = Regex("[0-9a-f]{7,40}")
private val URL_CREDENTIAL_OR_HOST = Regex(
    """://|@|=|passphrase|password|passwd|secret|token|streamid|srt:|\.ts\.net|\b\d{1,3}(\.\d{1,3}){3}\b""",
    RegexOption.IGNORE_CASE,
)
private val OUTSIDE_TOKEN = Regex("[^A-Za-z0-9 ._()+-]")

// Matches JavaScript's Date.toISOString(), which the server reads.
private val SENT_AT_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

private fun quotedToken(value: String) = "\"${plainToken(value)}\""

internal fun plainToken(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || URL_CREDENTIAL_OR_HOST.containsMatchIn(trimmed)) return UNKNOWN
    return trimmed.replace(OUTSIDE_TOKEN, "_").take(MAX_IDENTITY_TEXT_LENGTH).trimEnd()
}

internal fun gitShaOrUnknown(value: String): String = value.trim().takeIf(GIT_SHA::matches) ?: UNKNOWN
