package com.qq.closie.data.appearance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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

    /** One DataStore transaction for the whole editor, including deletion and effect preferences. */
    suspend fun saveTheme(selectedId: String, name: String, colors: CustomTheme, replacingId: String?,
        style: FunctionPageStyle, effects: FunctionPageEffects, removedIds: Set<String>) {
        val valid = colors.normalized()
        val effect = effects.normalized()
        require(name.trim().length in 1..40)
        require("legacy-custom" !in removedIds)
        store.edit { values ->
            var themes = AppearanceCodec.decode(values).namedThemes.filterNot { it.id in removedIds }
            val id = when {
                replacingId != null && replacingId != "legacy-custom" -> {
                    require(themes.any { it.id == replacingId }) { "主题已不存在" }
                    themes = themes.map { if (it.id == replacingId) it.copy(name = name.trim(), colors = valid) else it }
                    replacingId
                }
                selectedId == AppearanceSettings.CUSTOM_ID || replacingId == "legacy-custom" -> {
                    require(themes.size < 24) { "已保存 24 个主题，请先删除不再使用的主题" }
                    val created = NamedTheme("palette-" + java.util.UUID.randomUUID(), name.trim(), valid)
                    themes = themes + created
                    created.id
                }
                else -> { require(ThemePreset.fromId(selectedId) != null || themes.any { it.id == selectedId }); selectedId }
            }
            values[AppearanceCodec.collection] = AppearanceCodec.encodeNamedThemes(themes)
            values[AppearanceCodec.selected] = id
            values[AppearanceCodec.functionPageStyle] = style.name
            values[AppearanceCodec.atmosphere] = effect.atmosphere
            values[AppearanceCodec.opacity] = effect.opacity
            values[AppearanceCodec.blur] = effect.blur
            values[AppearanceCodec.homeBlur] = effect.homeBlur
            values[AppearanceCodec.gradient] = effect.gradient
            values[AppearanceCodec.homeGradient] = effect.homeGradient
        }
    }

    suspend fun saveModuleOrder(ids: List<String>) {
        store.edit { it[AppearanceCodec.moduleOrder] = ids.distinct().joinToString(",") }
    }

    suspend fun selectPreset(preset: ThemePreset) {
        store.edit { it[AppearanceCodec.selected] = preset.id }
    }

    suspend fun selectNamed(id: String) {
        store.edit { values ->
            require(AppearanceCodec.decode(values).namedThemes.any { it.id == id }) { "主题已不存在" }
            values[AppearanceCodec.selected] = id
        }
    }

    suspend fun saveNamed(name: String, colors: CustomTheme) {
        val valid = colors.normalized()
        require(name.trim().isNotEmpty() && name.trim().length <= 40) { "主题名称请填写 1–40 个字" }
        store.edit { values ->
            val existing = AppearanceCodec.decode(values).namedThemes
            require(existing.size < 24) { "已保存 24 个主题，请先删除不再使用的主题" }
            val theme = NamedTheme("palette-" + java.util.UUID.randomUUID(), name.trim(), valid)
            values[AppearanceCodec.collection] = AppearanceCodec.encodeNamedThemes(existing + theme)
            values[AppearanceCodec.selected] = theme.id
        }
    }

    suspend fun deleteNamed(id: String) {
        // Preserve old preference fields and their sole selectable compatibility entry.
        require(id != "legacy-custom") { "旧配色暂时保留；可以另存为新主题" }
        store.edit { values ->
            val settings = AppearanceCodec.decode(values)
            values[AppearanceCodec.collection] = AppearanceCodec.encodeNamedThemes(settings.namedThemes.filterNot { it.id == id })
            if (values[AppearanceCodec.selected] == id) values[AppearanceCodec.selected] = ThemePreset.ORIGINAL.id
        }
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
            it[AppearanceCodec.customSoft] = valid.soft
            it[AppearanceCodec.customWarm] = valid.warm
            it[AppearanceCodec.selected] = AppearanceSettings.CUSTOM_ID
        }
    }

    companion object {
        fun create(context: Context) = AppearanceRepository(context.applicationContext.lifeOsAppearanceStore)
    }
}

internal object AppearanceCodec {
    val homeBlur = intPreferencesKey("homeBlur")
    val gradient = intPreferencesKey("functionGradient")
    val homeGradient = intPreferencesKey("homeGradient")
    val atmosphere = intPreferencesKey("functionAtmosphere")
    val opacity = intPreferencesKey("functionOpacity")
    val blur = intPreferencesKey("functionBlur")
    val collection = stringPreferencesKey("namedThemesV1")
    val moduleOrder = stringPreferencesKey("moduleOrder")
    val customSoft = stringPreferencesKey("customSoft")
    val customWarm = stringPreferencesKey("customWarm")
    val functionPageStyle = stringPreferencesKey("functionPageStyle")
    val selected = stringPreferencesKey("selectedThemeId")
    val customAccent = stringPreferencesKey("customAccent")
    val customPaper = stringPreferencesKey("customPaper")
    val customSecondary = stringPreferencesKey("customSecondary")

    fun decode(values: Preferences): AppearanceSettings {
        val accent = HexColor.normalizeOrNull(values[customAccent])
        val paper = HexColor.normalizeOrNull(values[customPaper])
        val secondary = HexColor.normalizeOrNull(values[customSecondary])
        val custom = if (accent != null && paper != null && secondary != null) CustomTheme(accent, paper, secondary,
            HexColor.normalizeOrNull(values[customSoft]) ?: secondary,
            HexColor.normalizeOrNull(values[customWarm]) ?: paper) else null
        val rawId = values[selected]
        val themes = decodeThemes(values[collection]).toMutableList()
        if (custom != null && themes.none { it.id == "legacy-custom" }) themes.add(0, NamedTheme("legacy-custom", "以前的我的配色", custom))
        val id = when {
            rawId == AppearanceSettings.CUSTOM_ID && custom != null -> rawId
            ThemePreset.fromId(rawId) != null -> rawId!!
            themes.any { it.id == rawId } -> rawId!!
            else -> ThemePreset.ORIGINAL.id
        }
        return AppearanceSettings(id, custom, FunctionPageStyle.entries.firstOrNull {
            it.name == values[functionPageStyle]
        } ?: FunctionPageStyle.LAYERED, values[moduleOrder].orEmpty().split(',').filter(String::isNotBlank).distinct(), themes,
            FunctionPageEffects(values[atmosphere] ?: 50,
                values[opacity] ?: 50, values[blur] ?: 50, values[homeBlur] ?: 0,
                values[gradient] ?: 50, values[homeGradient] ?: 0).normalized())
    }

    // Compatibility entries are derived from old fields, never records in namedThemesV1.
    fun encodeNamedThemes(themes: List<NamedTheme>): String =
        com.google.gson.Gson().toJson(themes.filter { it.id.startsWith("palette-") })

    private fun decodeThemes(raw: String?): List<NamedTheme> = runCatching {
        com.google.gson.JsonParser.parseString(raw ?: "[]").asJsonArray.mapNotNull { element ->
            runCatching {
                val json = element.asJsonObject
                val id = json.get("id").asString
                require(id.startsWith("palette-"))
                val name = json.get("name").asString.trim().take(40).also { require(it.isNotBlank()) }
                val c = json.getAsJsonObject("colors")
                NamedTheme(id, name, CustomTheme(c.get("accent").asString, c.get("paper").asString,
                    c.get("secondary").asString, c.get("soft").asString, c.get("warm").asString).normalized())
            }.getOrNull()
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())
}
