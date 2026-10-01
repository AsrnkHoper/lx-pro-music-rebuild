package com.lxpro.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.lxProDataStore: DataStore<Preferences> by preferencesDataStore(name = "lxpro_settings")

/**
 * 设置项存储（DataStore Preferences，02 §6.2）。
 * ⚠️ 只存**轻量设置**；歌单/收藏/历史走 Room。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val paletteId = stringPreferencesKey("palette_id")
        val ambientEnabled = booleanPreferencesKey("ambient_enabled")
        val preferredQuality = stringPreferencesKey("preferred_quality")
    }

    /** 当前配色 id；null 表示从未选择，由上层回落 [LXPalette.default] 对应的 id */
    val paletteId: Flow<String?> = context.lxProDataStore.data.map { it[Keys.paletteId] }

    /** 氛围动效开关；关闭后氛围层完全不绘制（省电） */
    val ambientEnabled: Flow<Boolean> = context.lxProDataStore.data.map { it[Keys.ambientEnabled] ?: true }

    val preferredQuality: Flow<String?> = context.lxProDataStore.data.map { it[Keys.preferredQuality] }

    suspend fun setPaletteId(id: String) {
        context.lxProDataStore.edit { it[Keys.paletteId] = id }
    }

    suspend fun setAmbientEnabled(enabled: Boolean) {
        context.lxProDataStore.edit { it[Keys.ambientEnabled] = enabled }
    }

    suspend fun setPreferredQuality(quality: String) {
        context.lxProDataStore.edit { it[Keys.preferredQuality] = quality }
    }
}