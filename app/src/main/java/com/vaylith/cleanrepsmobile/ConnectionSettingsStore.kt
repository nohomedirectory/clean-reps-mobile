package com.vaylith.cleanrepsmobile

import android.content.Context
import com.vaylith.cleanrepsmobile.model.ConnectionSettings

/** MODE_PRIVATE plus allowBackup=false keeps pairing settings on this device. */
class ConnectionSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("private-connection", Context.MODE_PRIVATE)
    fun load() = ConnectionSettings(
        preferences.getString("api", BuildConfig.CHALLENGE_API_BASE_URL).orEmpty(),
        preferences.getString("host", BuildConfig.MEDIAMTX_SRT_HOST).orEmpty(),
        preferences.getString("passphrase", BuildConfig.MEDIAMTX_SRT_PASSPHRASE).orEmpty(),
        preferences.getString("password", BuildConfig.MEDIAMTX_PUBLISH_PASSWORD).orEmpty(),
    )
    fun save(settings: ConnectionSettings) {
        require(settings.validationError() == null) { "Connection settings are invalid" }
        check(preferences.edit().putString("api", settings.apiBaseUrl).putString("host", settings.srtHost)
            .putString("passphrase", settings.srtPassphrase).putString("password", settings.publishPassword).commit()) {
            "Could not save connection settings on this device"
        }
    }
}
