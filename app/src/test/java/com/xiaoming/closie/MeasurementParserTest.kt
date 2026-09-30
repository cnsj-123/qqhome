package com.xiaoming.closie

import com.xiaoming.closie.data.ocr.MeasurementParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementParserTest {

    private fun names(text: String, preferredSize: String? = null): List<String> =
        MeasurementParser.parse(text, preferredSize).map { it.name }

    private fun first(text: String) = MeasurementParser.parse(text).first()

    // ---- Single-value charts ----

    @Test
    fun `parses chinese single values`() {
        val text = "衣长 122 cm\n胸围：82cm\n腰围 68\n臀围 86 cm"
        val parsed = MeasurementParser.parse(text)
        assertEquals(listOf("衣长", "胸围", "腰围", "臀围"), parsed.map { it.name })
        assertEquals("122", parsed[0].value)
        assertEquals("82", parsed[1].value)
        assertEquals("cm", parsed[0].unit)
    }

    @Test
    fun `parses english fields`() {
        val text = "shoulder 35 cm\nchest 96cm\nsleeve 60\ninseam 70 cm"
        val parsed = MeasurementParser.parse(text)
        assertEquals(listOf("肩宽", "胸围", "袖长", "内长"), parsed.map { it.name })
        assertEquals("96", parsed[1].value)
    }

    @Test
    fun `detects inch and mm units`() {
        assertEquals("inch", first("衣长 40 inch").unit)
        assertEquals("mm", first("袖长 600 mm").unit)
        assertEquals("cm", first("衣长 122").unit)
    }

    @Test
    fun `parses decimals`() {
        assertEquals("96.5", first("胸围 96.5").value)
    }

    @Test
    fun `ignores lines without a known label`() {
        assertTrue(names("这件衣服很好看\n价格 299").isEmpty())
    }

    // ---- Size tables ----

    @Test
    fun `letter size table picks the preferred column`() {
        val text = """
            尺码 S M L XL
            胸围 82 86 90 94
            腰围 68 72 76 80
            衣长 122 123 124 125
        """.trimIndent()
        val parsed = MeasurementParser.parse(text, preferredSize = "M")
        assertEquals(listOf("胸围", "腰围", "衣长"), parsed.map { it.name })
        assertEquals("86", parsed[0].value)
        assertEquals("72", parsed[1].value)
        assertEquals("123", parsed[2].value)
    }

    @Test
    fun `numeric size table picks the preferred column`() {
        val text = """
            尺码 160 165 170
            胸围 80 84 88
            衣长 120 123 126
        """.trimIndent()
        val parsed = MeasurementParser.parse(text, preferredSize = "165")
        assertEquals("84", parsed.first { it.name == "胸围" }.value)
        assertEquals("123", parsed.first { it.name == "衣长" }.value)
    }

    @Test
    fun `missing preferred size leaves the table unmatched`() {
        val text = "尺码 S M L XL\n胸围 82 86 90 94"
        assertTrue(MeasurementParser.parse(text, preferredSize = "XXL").isEmpty())
    }

    @Test
    fun `rows with a wrong column count are not guessed`() {
        // Three numbers against a four-column header: the mapping is ambiguous -> unmatched.
        val text = "尺码 S M L XL\n胸围 82 86 90"
        assertTrue(MeasurementParser.parse(text, preferredSize = "M").isEmpty())
    }

    @Test
    fun `partially broken tables keep only the reliable rows`() {
        val text = "尺码 S M L XL\n胸围 82 86 90 94\n衣长 122 123"
        val parsed = MeasurementParser.parse(text, preferredSize = "L")
        assertEquals(listOf("胸围"), parsed.map { it.name })
        assertEquals("90", parsed[0].value)
    }

    @Test
    fun `blank preferred size falls back to single values`() {
        val text = "胸围 82\n腰围 68"
        assertEquals(listOf("胸围", "腰围"), names(text, preferredSize = ""))
    }
}
