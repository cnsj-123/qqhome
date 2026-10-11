package com.qq.closie.data.appearance

import kotlin.math.*

/** Bounded, deterministic local quantization + chroma/diversity scoring; no source image persistence. */
object ImagePalette {
    private data class Swatch(val r: Int, val g: Int, val b: Int, val count: Int) {
        val chroma get() = (maxOf(r, g, b) - minOf(r, g, b)) / 255.0
        val light get() = (maxOf(r, g, b) + minOf(r, g, b)) / 510.0
        val hex get() = "#%02X%02X%02X".format(r, g, b)
        fun distance(other: Swatch) = sqrt(((r-other.r).toDouble().pow(2) + (g-other.g).toDouble().pow(2) + (b-other.b).toDouble().pow(2))) / 441.7
    }
    fun extract(pixels: IntArray): CustomTheme {
        val bins = mutableMapOf<Int, IntArray>()
        val stride = maxOf(1, pixels.size / 12000)
        for (index in pixels.indices step stride) {
            val p = pixels[index]
            if ((p ushr 24) < 128) continue
            val r = (p ushr 16) and 255; val g = (p ushr 8) and 255; val b = p and 255
            val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            val bin = bins.getOrPut(key) { IntArray(4) }
            bin[0] += r; bin[1] += g; bin[2] += b; bin[3]++
        }
        val swatches = bins.values.map { Swatch(it[0]/it[3], it[1]/it[3], it[2]/it[3], it[3]) }
        val useful = swatches.filter { it.light in .10.. .90 && it.chroma > .10 }
        val accent = useful.maxByOrNull { sqrt(it.count.toDouble()) * (.2 + it.chroma) * (1.0 - abs(it.light - .45)) }
            ?: Swatch(111, 103, 168, 1)
        val secondary = useful.filter { it.distance(accent) > .20 }
            .maxByOrNull { sqrt(it.count.toDouble()) * it.distance(accent) * (.3 + it.chroma) } ?: Swatch(139, 168, 190, 1)
        val warm = useful.filter { it.r > it.b + 15 && it.distance(accent) > .15 }
            .maxByOrNull { sqrt(it.count.toDouble()) * (.2 + it.chroma) } ?: Swatch(213, 171, 146, 1)
        val paper = ThemePalette.mix(secondary.hex, "#FFFFFF", .95)
        var inkAccent = accent.hex
        repeat(30) { if (ThemePalette.contrast(inkAccent, paper) < 4.8) inkAccent = ThemePalette.mix(inkAccent, "#20202C", .12) }
        return CustomTheme(inkAccent, paper, ThemePalette.mix(secondary.hex, paper, .25),
            ThemePalette.mix(accent.hex, paper, .63), ThemePalette.mix(warm.hex, paper, .42)).normalized()
    }
}
