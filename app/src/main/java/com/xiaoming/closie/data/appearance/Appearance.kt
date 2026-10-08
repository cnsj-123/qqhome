package com.xiaoming.closie.data.appearance

/** Appearance preferences only. These values are never canonical life facts. */
enum class ThemePreset(
    val id: String,
    val displayName: String,
    val accent: String,
    val paper: String,
    val secondary: String,
    val soft: String,
    val warm: String
) {
    // Source: V11Theme.presets in the supplied life_os_v11.html.
    ORIGINAL("original", "暮山紫原色", "#6F67A8", "#F6F5F9", "#B6C2CF", "#C6B7CF", "#E2DEEC"),
    LAVENDER("lavender", "薰衣草信笺", "#755493", "#F8F4FA", "#9FC5E6", "#B69CD2", "#EBC5CF"),
    WATER_LILY("waterlily", "睡莲花园", "#495A7B", "#F2F6F4", "#648665", "#A6BF86", "#E2C9CE"),
    PEARL("pearl", "珍珠与旧纸", "#2D3E56", "#F5F3EB", "#5A6E8E", "#B4B0A0", "#D8D2C0"),
    JELLYFISH("jellyfish", "水母来信", "#4A535C", "#F1F5F6", "#7F95AC", "#BECCCD", "#987D67");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

data class CustomTheme(val accent: String, val paper: String, val secondary: String) {
    fun normalized() = CustomTheme(
        HexColor.requireValid(accent), HexColor.requireValid(paper), HexColor.requireValid(secondary)
    )
}

data class AppearanceSettings(
    val selectedThemeId: String = ThemePreset.ORIGINAL.id,
    val custom: CustomTheme? = null
) {
    fun selectedColors(): CustomTheme {
        if (selectedThemeId == CUSTOM_ID && custom != null) return custom.normalized()
        val preset = ThemePreset.fromId(selectedThemeId) ?: ThemePreset.ORIGINAL
        return CustomTheme(preset.accent, preset.paper, preset.secondary)
    }

    companion object { const val CUSTOM_ID = "custom" }
}

object HexColor {
    private val pattern = Regex("^#[0-9a-fA-F]{6}$")
    fun normalizeOrNull(value: String?): String? = value?.trim()?.takeIf(pattern::matches)?.uppercase()
    fun requireValid(value: String): String = requireNotNull(normalizeOrNull(value)) {
        "颜色请填写六位 HEX，例如 #6F67A8"
    }
}
