package com.qq.closie.data.appearance

import kotlin.math.pow
import kotlin.math.roundToInt

/** Pure semantic palette resolver, ported from V11Theme.resolve (no Android Color parser). */
data class ThemeTokens(
    val paper: String, val paperSecondary: String, val paperTertiary: String,
    val card: String, val ink: String, val inkSecondary: String, val muted: String,
    val accent: String, val accentDeep: String, val secondary: String,
    val line: String, val onAccent: String, val shade: String, val isDark: Boolean
)

object ThemePalette {
    private fun rgb(hex: String) = listOf(1, 3, 5).map {
        HexColor.requireValid(hex).substring(it, it + 2).toInt(16)
    }

    fun mix(a: String, b: String, weight: Double): String {
        val aa = rgb(a); val bb = rgb(b)
        return "#" + aa.indices.joinToString("") {
            (aa[it] * (1 - weight) + bb[it] * weight).roundToInt().coerceIn(0, 255)
                .toString(16).padStart(2, '0')
        }.uppercase()
    }

    private fun luminance(hex: String): Double {
        val channels = rgb(hex).map { it / 255.0 }.map {
            if (it <= .04045) it / 12.92 else ((it + .055) / 1.055).pow(2.4)
        }
        return channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722
    }

    fun contrast(a: String, b: String): Double {
        val aa = luminance(a); val bb = luminance(b)
        return (maxOf(aa, bb) + .05) / (minOf(aa, bb) + .05)
    }

    private fun readable(color: String, background: String, minimum: Double): String {
        var value = HexColor.requireValid(color)
        val end = if (contrast("#000000", background) > contrast("#FFFFFF", background)) "#000000" else "#FFFFFF"
        repeat(100) { if (contrast(value, background) < minimum) value = mix(value, end, .09) }
        // Integer RGB mixing can stop one channel short of the endpoint. Mid-tone paper may
        // also make the requested ratio impossible; use the most readable endpoint in that case.
        return if (contrast(value, background) < minimum) end else value
    }

    fun resolve(settings: AppearanceSettings): ThemeTokens {
        val choice = settings.selectedColors()
        val paper = choice.paper
        val dark = contrast("#FFFFFF", paper) > contrast("#000000", paper)
        val inkBase = if (dark) "#F5F4F1" else "#3A3748"
        var card = mix(paper, if (dark) "#181C22" else "#FFFFFF", .72)
        var paper2 = mix(paper, choice.secondary, .11)
        var paper3 = mix(paper, choice.accent, .15)
        var ink = readable(inkBase, paper, 7.0)
        var ink2 = readable(mix(inkBase, choice.accent, .2), paper, 6.0)
        val muted = readable(mix(inkBase, paper, .38), paper, 4.7)
        var accent = readable(choice.accent, if (dark) card else "#FFFFFF", 4.8)
        var deep = readable(accent, paper, 6.0)
        var line = mix(paper, choice.accent, .19)
        if (settings.selectedThemeId == ThemePreset.ORIGINAL.id) {
            paper2 = "#ECEAF3"; paper3 = "#E2DEEC"; card = "#FCFBFE"
            ink = "#3A3748"; ink2 = "#565270"; accent = "#6F67A8"; deep = "#575091"; line = "#DEDAE8"
        }
        if (settings.selectedThemeId == AppearanceSettings.CUSTOM_ID) {
            accent = readable(choice.accent, paper, 4.7)
            deep = readable(accent, paper, 6.0)
            val foregrounds = listOf(ink, ink2, muted, accent, deep)
            fun safeSurface(initial: String): String {
                var surface = initial
                repeat(100) {
                    if (foregrounds.any { contrast(it, surface) < 4.5 }) surface = mix(surface, paper, .14)
                }
                return if (foregrounds.any { contrast(it, surface) < 4.5 }) paper else surface
            }
            card = safeSurface(card); paper2 = safeSurface(paper2); paper3 = safeSurface(paper3)
        }
        return ThemeTokens(
            paper, paper2, paper3, card, ink, ink2, muted, accent, deep, choice.secondary, line,
            if (contrast(accent, "#FFFFFF") >= 4.5) "#FFFFFF" else "#000000",
            mix(inkBase, choice.accent, .3), dark
        )
    }
}
