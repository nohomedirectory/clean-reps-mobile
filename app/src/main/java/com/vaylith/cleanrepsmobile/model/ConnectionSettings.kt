package com.vaylith.cleanrepsmobile.model

import java.net.URI

/** Device-local settings. Never include the credentials in diagnostics. */
data class ConnectionSettings(
    val apiBaseUrl: String = "",
    val srtHost: String = "",
    val srtPassphrase: String = "",
    val publishPassword: String = "",
) {
    fun validationError(): String? {
        val api = runCatching { URI(apiBaseUrl) }.getOrNull()
            ?: return "Enter the private Clean Reps address."
        if (api.host.isNullOrBlank() || api.rawUserInfo != null || api.rawQuery != null || api.rawFragment != null ||
            api.path.orEmpty().trim('/').isNotEmpty() || api.port !in -1..65535 || api.port == 0) {
            return "Use the Clean Reps server address without a path, login or query."
        }
        if (api.scheme != "https" && !(api.scheme == "http" && isPrivateHost(api.host))) {
            return "Use HTTPS, or HTTP through a private network or Tailscale."
        }
        val media = runCatching { URI("srt://$srtHost") }.getOrNull()
        if (srtHost.isBlank() || media?.host.isNullOrBlank() || media?.rawUserInfo != null ||
            !media?.path.isNullOrEmpty() || media?.rawQuery != null || media?.rawFragment != null ||
            (media?.port ?: -1) !in -1..65535 || media?.port == 0) {
            return "Enter the video server host, optionally followed by its port."
        }
        if (srtPassphrase.length !in 10..79) return "The video passphrase must contain 10–79 characters."
        if (publishPassword.isBlank()) return "Enter the video publish password."
        if (srtPassphrase.any { it.isISOControl() || it in "&#" } || publishPassword.any { it.isISOControl() || it in "&,#" }) {
            return "Video credentials contain characters unsupported by the SRT connection format."
        }
        return null
    }

    override fun toString() = "ConnectionSettings(configured=${validationError() == null})"
}

internal fun isPrivateHost(host: String): Boolean {
    val normalized = host.lowercase().removeSuffix(".")
    if (normalized.endsWith(".ts.net")) return true
    val octets = normalized.split('.').map { part ->
        if (part.isEmpty() || part.any { !it.isDigit() }) return false
        part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
    }
    if (octets.size != 4) return false
    return octets[0] == 10 ||
        (octets[0] == 172 && octets[1] in 16..31) ||
        (octets[0] == 192 && octets[1] == 168) ||
        (octets[0] == 100 && octets[1] in 64..127)
}
