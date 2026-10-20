package com.nexus.downloader.data.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexus.downloader.data.model.AppConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(name = "nexus_config")

/**
 * 設定（Python 版 config.json）の永続化。
 * Preferences DataStore に JSON 文字列として丸ごと保存する。
 */
class ConfigRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val key = stringPreferencesKey("config_json")

    /** 設定の購読。未保存なら既定値。 */
    val config: Flow<AppConfig> = context.configDataStore.data.map { prefs ->
        decode(prefs[key])
    }

    /** 現在値を 1 回取得する。 */
    suspend fun current(): AppConfig = decode(context.configDataStore.data.first()[key])

    /** 設定を更新する。 */
    suspend fun update(transform: (AppConfig) -> AppConfig) {
        context.configDataStore.edit { prefs ->
            val currentValue = decode(prefs[key])
            prefs[key] = json.encodeToString(AppConfig.serializer(), transform(currentValue))
        }
    }

    /** 設定を丸ごと保存する。 */
    suspend fun save(config: AppConfig) {
        context.configDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(AppConfig.serializer(), config)
        }
    }

    private fun decode(raw: String?): AppConfig {
        if (raw.isNullOrBlank()) return AppConfig()
        return runCatching { json.decodeFromString(AppConfig.serializer(), raw) }
            .getOrElse { AppConfig() }
    }
}
