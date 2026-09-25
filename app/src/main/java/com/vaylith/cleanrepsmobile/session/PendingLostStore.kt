package com.vaylith.cleanrepsmobile.session

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * Captures whose terminal `lost` report the server has not confirmed. A capture
 * is added before its first attempt and removed once the server answers, so a
 * report cut short by process death is sent again on the next app start. While
 * any capture of the configured server is pending, the phone attaches no new
 * capture: an unreported `lost` leaves a second selectable session, and the live
 * supervisor then answers `ambiguous_active_sessions` and analyses nothing.
 */
interface PendingLostStore {
    /** Stores [captureId] durably before returning. */
    fun add(captureId: String, lost: PendingLost)

    fun remove(captureId: String)

    /** Every pending capture, whichever server it belongs to. */
    fun pending(): Map<String, PendingLost>
}

/**
 * A pending report: its allowlisted detail and the [server] that holds the
 * capture ([lostServerKey] of that server's address). An entry is delivered only
 * to its own server, and only its own server's entries hold up a new capture.
 */
data class PendingLost(val detail: HealthDetail, val server: String)

/**
 * The key of a Clean Reps server for [PendingLost.server]: the first 16 hex
 * digits of the SHA-256 of its address without a trailing slash. The address
 * itself is never stored a second time.
 */
fun lostServerKey(apiBaseUrl: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(apiBaseUrl.trim().trimEnd('/').toByteArray(Charsets.UTF_8))
    return digest.take(8).joinToString("") { "%02x".format(it) }
}

/**
 * [PendingLostStore] in the SharedPreferences file [FILE_NAME]: one key per
 * capture id, holding `<server key>|<detail wire value>`. [add] commits
 * synchronously. A detail outside the allowlist reads as [HealthDetail.STOPPED],
 * and a value without a server key reads as belonging to no server in particular
 * (any configured server may take it, as before server keys existed).
 */
class SharedPreferencesPendingLostStore(private val preferences: SharedPreferences) : PendingLostStore {
    constructor(context: Context) : this(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE))

    override fun add(captureId: String, lost: PendingLost) {
        check(preferences.edit().putString(captureId, "${lost.server}|${lost.detail.wireValue}").commit()) {
            "Could not store the pending lost report on this phone"
        }
    }

    override fun remove(captureId: String) {
        check(preferences.edit().remove(captureId).commit()) { "Could not clear the pending lost report on this phone" }
    }

    override fun pending(): Map<String, PendingLost> = preferences.all.mapValues { (_, value) ->
        val text = value as? String ?: ""
        val server = if ('|' in text) text.substringBefore('|') else ANY_SERVER
        PendingLost(HealthDetail.fromWire(text.substringAfter('|')) ?: HealthDetail.STOPPED, server)
    }

    companion object {
        const val FILE_NAME = "pending-lost"

        /** The server of an entry written without a server key. */
        const val ANY_SERVER = ""
    }
}
