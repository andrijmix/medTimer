package com.futsch1.medtimer.core.datastore

import android.content.SharedPreferences
import androidx.core.content.edit
import com.futsch1.medtimer.core.common.di.ApplicationScope
import com.futsch1.medtimer.core.datastore.di.McpPreferences
import com.futsch1.medtimer.core.domain.model.McpSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists [McpSettings] in its own preferences file, so the secret is not part of [PreferencesDataSource] and therefore not part of any
 * settings backup. The settings screen binds its preferences to the same file ([FILE_NAME]).
 */
@Singleton
class McpSettingsDataSource @Inject constructor(
    @param:McpPreferences private val sharedPreferences: SharedPreferences,
    @param:ApplicationScope scope: CoroutineScope
) {
    val settings: StateFlow<McpSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.stateIn(scope, started = SharingStarted.Eagerly, initialValue = read())

    fun setEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(ENABLED, enabled) }
    }

    fun setLastError(error: String) {
        sharedPreferences.edit { putString(LAST_ERROR, error) }
    }

    /**
     * Returns the stored secret, generating and storing one first if there is none yet.
     */
    @Synchronized
    fun ensureSecret(): String {
        val existing = sharedPreferences.getString(SECRET, null)
        if (!existing.isNullOrEmpty()) {
            return existing
        }
        return regenerateSecret()
    }

    /**
     * Replaces the secret. The connector URL changes, so the old one stops working immediately.
     */
    @Synchronized
    fun regenerateSecret(): String {
        val secret = McpSecretGenerator.generate()
        sharedPreferences.edit { putString(SECRET, secret) }
        return secret
    }

    private fun read(): McpSettings = McpSettings(
        enabled = sharedPreferences.getBoolean(ENABLED, false),
        // The port comes from an EditTextPreference and is therefore stored as a string.
        port = sharedPreferences.getString(PORT, null)?.let(McpSettings::parsePort) ?: McpSettings.DEFAULT_PORT,
        publicBaseUrl = sharedPreferences.getString(PUBLIC_BASE_URL, null).orEmpty(),
        secret = sharedPreferences.getString(SECRET, null).orEmpty(),
        lastError = sharedPreferences.getString(LAST_ERROR, null).orEmpty()
    )

    companion object {
        const val FILE_NAME = "mcp_server"
        const val ENABLED = "mcp_enabled"
        const val PORT = "mcp_port"
        const val PUBLIC_BASE_URL = "mcp_public_base_url"
        const val SECRET = "mcp_secret"
        const val LAST_ERROR = "mcp_last_error"
    }
}
