package com.qq.closie.data.ocr

/** A single measurement recognized from a size-chart image. */
data class ParsedMeasurement(
    val name: String,
    val value: String,
    val unit: String
)

/**
 * Rule-based measurement extraction from OCR text. Matches common Chinese and English size-chart
 * labels against a following number. Supports both single-value charts and size-table charts.
 * No AI — deterministic and unit-testable.
 *
 * Size-table example:
 *
 *   尺码  S   M   L   XL
 *   胸围 82  86  90  94
 *   腰围 68  72  76  80
 *   衣长 122 123 124 125
 *
 * With `preferredSize = "M"` this yields 胸围 86 / 腰围 72 / 衣长 123. When the column mapping is
 * unreliable (wrong column count, or the preferred size is absent) the row is left unmatched
 * rather than guessed, and the caller surfaces it for manual confirmation.
 */
object MeasurementParser {

    private val ALIASES: Map<String, String> = linkedMapOf(
        "衣长" to "衣长", "length" to "衣长",
        "肩宽" to "肩宽", "shoulder" to "肩宽",
        "胸围" to "胸围", "chest" to "胸围", "bust" to "胸围",
        "腰围" to "腰围", "waist" to "腰围",
        "臀围" to "臀围", "hip" to "臀围",
        "袖长" to "袖长", "sleeve" to "袖长",
        "裤长" to "裤长",
        "内长" to "内长", "inseam" to "内长",
        "前裆" to "前裆",
        "后裆" to "后裆",
        "大腿围" to "大腿围",
        "下摆" to "下摆"
    )

    private val NUMBER = Regex("""\d+(?:\.\d+)?""")

    private val SIZE_TOKEN = Regex("""(?i)^(XXXS|XXS|XS|S|M|L|XL|XXL|XXXL|\d+)$""")

    /**
     * Parses OCR text. When [preferredSize] is non-blank and a size-table header is present, the
     * matching column is used; an unresolvable table stays unmatched instead of being guessed.
     * Otherwise each line is parsed as an independent single value.
     */
    fun parse(text: String, preferredSize: String? = null): List<ParsedMeasurement> {
        if (!preferredSize.isNullOrBlank()) {
            val hasTable = text.lines().map { it.trim() }.any { isHeaderLine(it) }
            if (hasTable) return dedupe(parseTable(text, preferredSize))
        }
        return dedupe(parseSingleValue(text))
    }

    /** Line-based single-value parsing: returns the first occurrence of each measurement kind. */
    private fun parseSingleValue(text: String): List<ParsedMeasurement> {
        val results = mutableListOf<ParsedMeasurement>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val match = parseLine(line) ?: continue
            results.add(match)
        }
        return results
    }

    private fun dedupe(list: List<ParsedMeasurement>): List<ParsedMeasurement> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<ParsedMeasurement>()
        for (m in list) if (seen.add(m.name)) out.add(m)
        return out
    }

    private fun parseLine(line: String): ParsedMeasurement? {
        // Longest alias first so e.g. "大腿围" wins over a hypothetical shorter alias.
        for ((alias, canonical) in ALIASES.entries.sortedByDescending { it.key.length }) {
            val idx = line.indexOf(alias, ignoreCase = true)
            if (idx < 0) continue
            val after = line.substring(idx + alias.length)
            val number = NUMBER.find(after) ?: continue
            return ParsedMeasurement(
                name = canonical,
                value = number.value,
                unit = detectUnit(after)
            )
        }
        return null
    }

    /**
     * Parses a size-table (header row + data rows) and returns only the rows whose column mapping
     * is unambiguous. Returns an empty list when no reliable table can be found.
     */
    private fun parseTable(text: String, preferredSize: String): List<ParsedMeasurement> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val headerLine = lines.firstOrNull { isHeaderLine(it) } ?: return emptyList()
        val sizeColumns = parseSizeColumns(headerLine) ?: return emptyList()
        val targetIndex = sizeColumns.indexOfFirst { it.equals(preferredSize, ignoreCase = true) }
        if (targetIndex < 0) return emptyList() // preferred size absent -> do not guess.

        val results = mutableListOf<ParsedMeasurement>()
        for (line in lines) {
            if (line == headerLine) continue
            val parsed = parseTableRow(line, targetIndex, sizeColumns.size) ?: continue
            results.add(parsed)
        }
        return results
    }

    private fun isHeaderLine(line: String): Boolean {
        val hasLabel = Regex("""(?i)尺码|尺寸|size|规格""").containsMatchIn(line)
        if (!hasLabel) return false
        return parseSizeColumns(line) != null
    }

    private fun parseSizeColumns(headerLine: String): List<String>? {
        val cleaned = headerLine.replace(Regex("""(?i)尺码|尺寸|size|规格"""), " ")
        val tokens = cleaned.split(Regex("""[\s/|,，]+""")).map { it.trim() }.filter { it.isNotEmpty() }
        val sizes = tokens.filter { SIZE_TOKEN.matches(it) }
        return sizes.takeIf { it.size >= 2 }
    }

    private fun parseTableRow(line: String, targetIndex: Int, columnCount: Int): ParsedMeasurement? {
        for ((alias, canonical) in ALIASES.entries.sortedByDescending { it.key.length }) {
            val idx = line.indexOf(alias, ignoreCase = true)
            if (idx < 0) continue
            val after = line.substring(idx + alias.length)
            val numbers = NUMBER.findAll(after).map { it.value }.toList()
            // Only trust the column mapping when the number of values matches the header exactly.
            if (numbers.size != columnCount) return null
            if (targetIndex >= numbers.size) return null
            return ParsedMeasurement(
                name = canonical,
                value = numbers[targetIndex],
                unit = detectUnit(after)
            )
        }
        return null
    }

    private fun detectUnit(after: String): String = when {
        Regex("""(?i)inch|英寸""").containsMatchIn(after) -> "inch"
        Regex("""(?i)\bmm\b|毫米""").containsMatchIn(after) -> "mm"
        else -> "cm"
    }
}
