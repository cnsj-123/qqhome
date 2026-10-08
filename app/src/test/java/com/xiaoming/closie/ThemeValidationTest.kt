package com.xiaoming.closie

import com.xiaoming.closie.data.appearance.*
import com.xiaoming.closie.ui.lifeos.settings.AppearanceEditorState
import com.xiaoming.closie.ui.lifeos.settings.ThemeField
import org.junit.Assert.*
import org.junit.Test

class ThemeValidationTest {
    @Test
    fun `invalid HEX is rejected and edit retains last valid preview`() {
        for (value in listOf(null, "", "#FFF", "123456", "#GG00FF", "#12345678")) {
            assertNull(HexColor.normalizeOrNull(value))
        }
        assertEquals("#ABCDEF", HexColor.normalizeOrNull(" #abcdef "))
        val original = AppearanceSettings()
        val edited = AppearanceEditorState.from(original).edit(ThemeField.ACCENT, "#123456")
        val invalid = edited.edit(ThemeField.PAPER, "unfinished")
        assertEquals(edited.preview, invalid.preview)
        assertNotNull(invalid.validationError)
        assertEquals(ThemePreset.ORIGINAL.id, original.selectedThemeId) // Editing does not mutate saved settings.
        val corrected = invalid.edit(ThemeField.PAPER, "#ffffff")
        assertNull(corrected.validationError)
        assertEquals("#FFFFFF", corrected.preview.custom!!.paper)
    }

    @Test
    fun `V11 preset values and original semantic overrides are retained`() {
        assertEquals(5, ThemePreset.entries.size)
        val expected = listOf(
            listOf("#6F67A8", "#F6F5F9", "#B6C2CF"), listOf("#755493", "#F8F4FA", "#9FC5E6"),
            listOf("#495A7B", "#F2F6F4", "#648665"), listOf("#2D3E56", "#F5F3EB", "#5A6E8E"),
            listOf("#4A535C", "#F1F5F6", "#7F95AC")
        )
        ThemePreset.entries.zip(expected).forEach { (preset, colors) ->
            assertEquals(colors, listOf(preset.accent, preset.paper, preset.secondary))
        }
        val original = ThemePalette.resolve(AppearanceSettings())
        assertEquals("#FCFBFE", original.card)
        assertEquals("#ECEAF3", original.paperSecondary)
        assertEquals("#575091", original.accentDeep)
        assertEquals("#DEDAE8", original.line)
    }

    @Test
    fun `custom light dark and mid-tone palettes keep text and buttons readable`() {
        val choices = listOf(
            CustomTheme("#FFFFFF", "#FFFFFF", "#000000"), CustomTheme("#000000", "#000000", "#FFFFFF"),
            CustomTheme("#777777", "#777777", "#FF00FF"), CustomTheme("#FFFF00", "#223344", "#00FF00"),
            CustomTheme("#ABCDEF", "#F0EADC", "#FF0000")
        )
        choices.forEach { custom ->
            val tokens = ThemePalette.resolve(AppearanceSettings(AppearanceSettings.CUSTOM_ID, custom))
            for (surface in listOf(tokens.paper, tokens.card, tokens.paperSecondary, tokens.paperTertiary)) {
                for (text in listOf(tokens.ink, tokens.inkSecondary, tokens.muted, tokens.accent, tokens.accentDeep)) {
                    assertTrue("$text on $surface for $custom", ThemePalette.contrast(text, surface) >= 4.5)
                }
            }
            assertTrue(ThemePalette.contrast(tokens.onAccent, tokens.accent) >= 4.5)
        }
    }
}
