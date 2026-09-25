package com.vaylith.cleanrepsmobile.session

import android.content.Context
import android.content.SharedPreferences

/**
 * Captures whose terminal `lost` report the server has not confirmed. A capture
 * is added before its first attempt and removed once the server answers, so a
 * report cut short by process death is sent again on the next app start. While
 * any capture is pending, the phone attaches no new capture: an unreported
 * `lost` leaves a second selectable session, and the live supervisor then
 * answers `ambiguous_active_sessions` and analyses nothing.
 */
interface PendingLostStore {
    /** Stores [captureId] durably before returning. */
    fun add(captureId: String, detail: HealthDetail)

    fun remove(captureId: String)

    /** Every pending capture with the detail its `lost` report carries. */
    fun pending(): Map<String, HealthDetail>
}

/**
 * [PendingLostStore] in the SharedPreferences file [FILE_NAME]: one key per
 * capture id, holding the detail's wire value. [add] commits synchronously; a
 * value that is not an allowlisted detail reads as [HealthDetail.STOPPED].
 */
class SharedPreferencesPendingLostStore(private val preferences: SharedPreferences) : PendingLostStore {
    constructor(context: Context) : this(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE))

    override fun add(captureId: String, detail: HealthDetail) {
        check(preferences.edit().putString(captureId, detail.wireValue).commit()) { "Could not store the pending lost report on this phone" }
    }

    override fun remove(captureId: String) {
        check(preferences.edit().remove(captureId).commit()) { "Could not clear the pending lost report on this phone" }
    }

    override fun pending(): Map<String, HealthDetail> =
        preferences.all.mapValues { (_, value) -> HealthDetail.fromWire(value as? String) ?: HealthDetail.STOPPED }

    companion object {
        const val FILE_NAME = "pending-lost"
    }
}
