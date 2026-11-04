package com.clouddroid.app.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "clouddroid_settings")

enum class StreamProfile(val label: String, val height: Int, val fps: Int, val kbps: Int) {
    LOW("Low · 480p · 24 fps", 480, 24, 1000),
    BALANCED("Balanced · 720p · 30 fps", 720, 30, 3000),
    HIGH("High · 1080p · 60 fps", 1080, 60, 8000),
}

data class AppSettings(
    val serverUrl: String = "",
    val token: String = "",
    val profile: StreamProfile = StreamProfile.BALANCED,
    val autoQuality: Boolean = true,
    val audioEnabled: Boolean = true,
    val autoBackup: Boolean = true,
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val token = stringPreferencesKey("token")
        val profile = stringPreferencesKey("profile")
        val autoQuality = booleanPreferencesKey("auto_quality")
        val audio = booleanPreferencesKey("audio")
        val autoBackup = booleanPreferencesKey("auto_backup")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            serverUrl = p[Keys.serverUrl] ?: "",
            token = p[Keys.token] ?: "",
            profile = p[Keys.profile]?.let { runCatching { StreamProfile.valueOf(it) }.getOrNull() }
                ?: StreamProfile.BALANCED,
            autoQuality = p[Keys.autoQuality] ?: true,
            audioEnabled = p[Keys.audio] ?: true,
            autoBackup = p[Keys.autoBackup] ?: true,
        )
    }

    suspend fun setServer(url: String, token: String) = context.dataStore.edit {
        it[Keys.serverUrl] = url.trim().trimEnd('/')
        it[Keys.token] = token.trim()
    }

    suspend fun setProfile(profile: StreamProfile) = context.dataStore.edit { it[Keys.profile] = profile.name }
    suspend fun setAutoQuality(v: Boolean) = context.dataStore.edit { it[Keys.autoQuality] = v }
    suspend fun setAudio(v: Boolean) = context.dataStore.edit { it[Keys.audio] = v }
    suspend fun setAutoBackup(v: Boolean) = context.dataStore.edit { it[Keys.autoBackup] = v }
}
