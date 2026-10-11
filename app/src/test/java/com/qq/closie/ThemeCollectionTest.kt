package com.qq.closie

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.qq.closie.data.appearance.*
import org.junit.Assert.*
import org.junit.Test

class ThemeCollectionTest {
    @Test fun `legacy custom colors remain selected and become a named theme`() {
        val values = mutablePreferencesOf(AppearanceCodec.selected to "custom",
            AppearanceCodec.customAccent to "#765432", AppearanceCodec.customPaper to "#FFFFFF",
            AppearanceCodec.customSecondary to "#AABBCC")
        val decoded = AppearanceCodec.decode(values)
        assertEquals("custom", decoded.selectedThemeId)
        assertEquals(decoded.custom, decoded.namedThemes.single().colors)
        assertEquals(decoded.custom, decoded.selectedColors())
    }

    @Test fun `collection decoding preserves valid siblings and rejects invalid colors`() {
        val colors = CustomTheme("#665588", "#FFFFFF", "#AABBCC")
        val good = NamedTheme("palette-one", "花园", colors)
        val bad = NamedTheme("palette-two", "坏色", colors.copy(accent = "invalid"))
        val values = mutablePreferencesOf(AppearanceCodec.selected to good.id,
            AppearanceCodec.collection to com.google.gson.Gson().toJson(listOf(good, bad)))
        val decoded = AppearanceCodec.decode(values)
        assertEquals(listOf(good), decoded.namedThemes)
        assertEquals(colors, decoded.selectedColors())
    }

    @Test fun `black white transparent and noisy images produce usable deterministic palettes`() {
        val random = java.util.Random(41)
        val samples = listOf(IntArray(0), IntArray(200) { 0 }, IntArray(200) { -1 },
            IntArray(200) { 0xFF000000.toInt() }, IntArray(12000) { random.nextInt() or 0xFF000000.toInt() })
        samples.forEach { pixels ->
            val palette = ImagePalette.extract(pixels)
            assertEquals(palette, ImagePalette.extract(pixels))
            assertTrue(ThemePalette.contrast(palette.accent, palette.paper) >= 4.5)
            val tokens = ThemePalette.resolve(AppearanceSettings("custom", palette))
            assertTrue(ThemePalette.contrast(tokens.ink, tokens.paper) >= 4.5)
            assertTrue(ThemePalette.contrast(tokens.onAccent, tokens.accent) >= 4.5)
        }
    }
}
