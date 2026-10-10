package com.qq.closie.data.appearance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.lifeOsAppearanceStore by preferencesDataStore(name = "life_os_appearance")

/** The only new persistent store in Task 1: UI appearance preferences, outside Closet storage. */
class AppearanceRepository(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val records = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map(AppearanceCodec::decode)

    val settings: StateFlow<AppearanceSettings?> = records.stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun read(): AppearanceSettings = records.first()

    suspend fun selectFunctionPageStyle(style: FunctionPageStyle) {
        store.edit { it[AppearanceCodec.functionPageStyle] = style.name }
    }

    suspend fun selectPreset(preset: ThemePreset) {
        store.edit { it[AppearanceCodec.selected] = preset.id }
    }

    suspend fun selectSavedCustom() {
        store.edit { preferences ->
            requireNotNull(AppearanceCodec.decode(preferences).custom) { "还没有保存自定义配色" }
            preferences[AppearanceCodec.selected] = AppearanceSettings.CUSTOM_ID
        }
    }

    suspend fun saveCustom(custom: CustomTheme) {
        val valid = custom.normalized() // Reject invalid HEX before any storage mutation.
        store.edit {
            it[AppearanceCodec.customAccent] = valid.accent
            it[AppearanceCodec.customPaper] = valid.paper
            it[AppearanceCodec.customSecondary] = valid.secondary
            it[AppearanceCodec.selected] = AppearanceSettings.CUSTOM_ID
        }
    }

    companion object {
        fun create(context: Context) = AppearanceRepository(context.applicationContext.lifeOsAppearanceStore)
    }
}

internal object AppearanceCodec {
    val functionPageStyle = stringPreferencesKey("functionPageStyle")
    val selected = stringPreferencesKey("selectedThemeId")
    val customAccent = stringPreferencesKey("customAccent")
    val customPaper = stringPreferencesKey("customPaper")
    val customSecondary = stringPreferencesKey("customSecondary")

    fun decode(values: Preferences): AppearanceSettings {
        val accent = HexColor.normalizeOrNull(values[customAccent])
        val paper = HexColor.normalizeOrNull(values[customPaper])
        val secondary = HexColor.normalizeOrNull(values[customSecondary])
        val custom = if (accent != null && paper != null && secondary != null) CustomTheme(accent, paper, secondary) else null
        val rawId = values[selected]
        val id = when {
            rawId == AppearanceSettings.CUSTOM_ID && custom != null -> rawId
            ThemePreset.fromId(rawId) != null -> rawId!!
            else -> ThemePreset.ORIGINAL.id
        }
        return AppearanceSettings(id, custom, FunctionPageStyle.entries.firstOrNull {
            it.name == values[functionPageStyle]
        } ?: FunctionPageStyle.LAYERED)
    }
}
