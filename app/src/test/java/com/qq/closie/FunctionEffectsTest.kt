package com.qq.closie

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.qq.closie.data.appearance.*
import org.junit.Assert.*
import org.junit.Test

class FunctionEffectsTest {
    @Test fun `old or malformed preferences retain recommended effects`() {
        assertEquals(FunctionPageEffects(), AppearanceCodec.decode(emptyPreferences()).functionEffects)
        assertEquals(FunctionPageEffects(50, 50, 50).normalized(), AppearanceCodec.decode(mutablePreferencesOf(
            AppearanceCodec.atmosphere to 50, AppearanceCodec.opacity to 50)).functionEffects)
    }
    @Test fun `effects decode independently and are bounded without changing semantic colors`() {
        val decoded = AppearanceCodec.decode(mutablePreferencesOf(AppearanceCodec.atmosphere to 200,
            AppearanceCodec.opacity to -1, AppearanceCodec.blur to 0))
        assertEquals(FunctionPageEffects(100, 0, 0), decoded.functionEffects)
        assertEquals(AppearanceSettings().selectedColors(), decoded.selectedColors())
        assertEquals(0f, decoded.functionEffects.surfaceAlpha, .0001f)
        assertEquals(1f, FunctionPageEffects(opacity = 100).surfaceAlpha, .0001f)
        assertEquals(0f, decoded.functionEffects.blurDp, .0001f)
    }
}
