package com.qq.closie.life.finance

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.zip.ZipFile
import com.google.gson.Gson

data class LegacyLedgerTable(val sheetName: String, val rows: List<List<String>>, val formulaRows: Set<Int> = emptySet(),
    val date1904: Boolean = false)

/** Bounded tabular reader, not an Excel engine. No formula evaluation, network, or DAO access. */
object LegacyLedgerReader {
    val headers = listOf("日期", "金额", "账本", "类型", "分类", "子分类", "备注", "标签", "账户", "转入账户",
        "债务人", "转账手续费", "是否为报销", "账单状态", "报销金额", "报销日期", "退款金额", "退款日期",
        "原价", "不计入预算", "不计入收支", "账单图片")
    const val MAX_FILE_BYTES = 16 * 1024 * 1024
    private const val MAX_XML_BYTES = 48 * 1024 * 1024
    private const val MAX_ROWS = 100_000

    fun digest(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun fingerprint(values: List<String>): String = MessageDigest.getInstance("SHA-256")
        .digest(Gson().toJson(values).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun read(file: File, fileName: String): LegacyLedgerTable {
        require(file.length() in 1..MAX_FILE_BYTES.toLong()) { "文件为空或超过 16 MB" }
        return when (fileName.substringAfterLast('.').lowercase()) {
            "xlsx" -> xlsx(file)
            "csv" -> {
                val bytes = file.readBytes()
                val text = try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
                    catch (_: java.nio.charset.CharacterCodingException) {
                        java.nio.charset.Charset.forName("GB18030").newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    }
                LegacyLedgerTable("CSV", csv(text.removePrefix("\uFEFF")))
            }
            else -> throw IllegalArgumentException("请选择 XLSX 或 CSV 账单")
        }.also { table ->
            require(table.rows.isNotEmpty() && table.rows.first().map(String::trim) == headers) { "账单表头与支持的 22 列格式不一致，原文件未修改" }
        }
    }

    private fun xml(text: String): XmlPullParser {
        require(!text.contains("<!DOCTYPE", ignoreCase = true)) { "不支持含外部定义的工作簿" }
        return Xml.newPullParser().apply { setInput(StringReader(text)) }
    }
    private fun xlsx(file: File): LegacyLedgerTable = ZipFile(file).use { zip ->
        var totalBytes = 0
        fun part(name: String): String {
            require(!name.startsWith("/") && !name.contains("..") && !name.contains("\\")) { "工作簿路径无效" }
            val entry = requireNotNull(zip.getEntry(name)) { "工作簿缺少必需部分" }
            return zip.getInputStream(entry).use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val count = input.read(buf); if (count < 0) break
                    totalBytes += count
                    require(totalBytes <= MAX_XML_BYTES) { "工作簿展开后过大" }
                    out.write(buf, 0, count)
                }
                out.toString("UTF-8")
            }
        }
        val workbook = xml(part("xl/workbook.xml"))
        val sheets = mutableListOf<Pair<String, String>>()
        var date1904 = false
        while (workbook.next() != XmlPullParser.END_DOCUMENT) if (workbook.eventType == XmlPullParser.START_TAG) {
            if (workbook.name == "workbookPr") date1904 = workbook.getAttributeValue(null, "date1904") in setOf("1", "true")
            if (workbook.name == "sheet") {
                val id = (0 until workbook.attributeCount).firstOrNull { workbook.getAttributeName(it).endsWith("id") }
                sheets += workbook.getAttributeValue(null, "name") to requireNotNull(id?.let { workbook.getAttributeValue(it) })
            }
        }
        val relations = xml(part("xl/_rels/workbook.xml.rels"))
        val targets = mutableMapOf<String, String>()
        while (relations.next() != XmlPullParser.END_DOCUMENT) if (relations.eventType == XmlPullParser.START_TAG && relations.name == "Relationship") {
            if (relations.getAttributeValue(null, "TargetMode") == "External") continue
            val target = relations.getAttributeValue(null, "Target")
            targets[relations.getAttributeValue(null, "Id")] = if (target.startsWith("/xl/")) target.drop(1) else "xl/$target"
        }
        val shared = mutableListOf<String>()
        if (zip.getEntry("xl/sharedStrings.xml") != null) {
            val parser = xml(part("xl/sharedStrings.xml"))
            var current: StringBuilder? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "si") current = StringBuilder()
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "t") current?.append(parser.nextText())
                if (parser.eventType == XmlPullParser.END_TAG && parser.name == "si") {
                    require(shared.size < 500_000 && (current?.length ?: 0) <= 65_536) { "工作簿文本过大" }
                    shared += current.toString(); current = null
                }
            }
        }
        val sheet = sheets.find { it.first == "账单列表" } ?: sheets.singleOrNull()
            ?: throw IllegalArgumentException("请选择包含“账单列表”的工作簿")
        val parser = xml(part(requireNotNull(targets[sheet.second])))
        val rows = mutableListOf<List<String>>()
        val formulas = mutableSetOf<Int>()
        var cells = MutableList(22) { "" }
        var column = 0
        var cellType = ""
        var cellValue = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) when (parser.name) {
                "row" -> { require(rows.size < MAX_ROWS) { "记录超过 10 万条" }; cells = MutableList(22) { "" } }
                "c" -> {
                    val ref = parser.getAttributeValue(null, "r").orEmpty().takeWhile(Char::isLetter)
                    require(ref.isNotEmpty()) { "单元格位置缺失" }
                    column = ref.fold(0) { n, c -> n * 26 + c.uppercaseChar().code - 'A'.code + 1 } - 1
                    require(column in 0..1023) { "单元格范围无效" }
                    cellType = parser.getAttributeValue(null, "t").orEmpty(); cellValue = ""
                }
                "f" -> formulas += rows.size + 1
                "v" -> cellValue = parser.nextText()
                "t" -> if (cellType == "inlineStr") cellValue += parser.nextText()
            }
            if (parser.eventType == XmlPullParser.END_TAG) when (parser.name) {
                "c" -> {
                    if (cellType == "s") cellValue = shared.getOrNull(cellValue.toIntOrNull() ?: -1)
                        ?: throw IllegalArgumentException("工作簿文本索引无效")
                    if (column < 22) cells[column] = cellValue
                    else require(cellValue.isBlank()) { "工作簿含未识别的额外列" }
                }
                "row" -> rows += cells.toList()
            }
        }
        LegacyLedgerTable(sheet.first, rows, formulas, date1904)
    }

    /** RFC 4180 quoting, embedded newlines and trailing empty cells. */
    internal fun csv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false; var closedQuote = false; var i = 0
        fun endField() { row += field.toString(); field.setLength(0); closedQuote = false }
        fun endRow() { endField(); rows += row.toList(); row.clear(); require(rows.size <= MAX_ROWS) }
        while (i < text.length) {
            val c = text[i++]
            if (quoted) {
                if (c == '"') {
                    if (i < text.length && text[i] == '"') { field.append('"'); i++ }
                    else { quoted = false; closedQuote = true }
                } else field.append(c)
            } else when (c) {
                '"' -> { require(field.isEmpty() && !closedQuote) { "CSV 引号格式无效" }; quoted = true }
                ',' -> endField()
                '\n' -> endRow()
                '\r' -> { if (i < text.length && text[i] == '\n') i++; endRow() }
                else -> { require(!closedQuote) { "CSV 引号之后有额外字符" }; field.append(c) }
            }
            require(field.length <= 65_536 && row.size <= 22) { "CSV 列数或文本长度超限" }
        }
        require(!quoted) { "CSV 引号未闭合" }
        if (field.isNotEmpty() || row.isNotEmpty() || closedQuote) endRow()
        return rows
    }

    fun stage(table: LegacyLedgerTable, batchId: String): List<FinanceImportRowEntity> {
        val seen = hashSetOf<String>()
        return table.rows.drop(1).mapIndexedNotNull { index, values ->
            if (values.all(String::isBlank)) return@mapIndexedNotNull null
            val raw = headers.zip(values + List(22) { "" }).toMap()
            fun v(name: String) = raw[name].orEmpty().trim()
            fun amount(name: String): Long = BigDecimal(v(name).ifBlank { "0" }).movePointRight(2).longValueExact()
            val issues = mutableListOf<String>()
            val kind = when (v("类型")) { "支出" -> FinanceProposalKind.EXPENSE; "收入" -> FinanceProposalKind.INCOME; "转账" -> FinanceProposalKind.TRANSFER; else -> null }
            if (kind == null) issues += "此类型需要人工解释"
            val minor = runCatching { amount("金额").also { require(it > 0) } }.getOrNull()
            val time = runCatching { date(v("日期"), table.date1904) }.getOrNull()
            val fee = runCatching { amount("转账手续费").also { require(it >= 0) } }.getOrNull()
            val invalid = values.size != 22 || minor == null || time == null || fee == null || v("账户").isBlank() ||
                ((fee ?: 0) > 0 && kind != FinanceProposalKind.TRANSFER) ||
                listOf("不计入收支", "不计入预算", "是否为报销").any { v(it) !in setOf("", "是", "否") } ||
                (kind == FinanceProposalKind.TRANSFER && (v("转入账户").isBlank() || v("转入账户") == v("账户"))) ||
                index + 2 in table.formulaRows
            if (invalid) issues += "金额、日期、账户、列数或公式需要检查"
            val reimbursement = v("是否为报销") == "是" || v("账单状态").contains("报销") || runCatching { amount("报销金额") != 0L }.getOrDefault(true)
            val refund = v("账单状态").contains("退款") || runCatching { amount("退款金额") != 0L }.getOrDefault(true)
            if (reimbursement) issues += "报销字段仅保留证据，不生成到账"
            if (refund) issues += "退款字段仅保留证据，不生成到账"
            if ((fee ?: 0) != 0L) issues += "手续费需确认是否另计实际支出"
            if (v("债务人").isNotBlank()) issues += "存在债务关系，暂不推断"
            if (listOf("不计入收支", "不计入预算", "是否为报销").any { v(it) !in setOf("", "是", "否") }) issues += "未识别的状态标记"
            val fingerprint = fingerprint(values)
            val duplicate = !seen.add(fingerprint)
            FinanceImportRowEntity("$batchId:${index + 2}", batchId, index + 2, fingerprint, Gson().toJson(raw),
                when { invalid -> FinanceRowStatus.INVALID; duplicate -> FinanceRowStatus.DUPLICATE; issues.isNotEmpty() -> FinanceRowStatus.REVIEW; else -> FinanceRowStatus.READY },
                issues.joinToString("；"), v("账户"), v("转入账户"), kind, minor, time, v("备注"),
                v("分类"), v("子分类"), v("标签"), fee ?: 0, reimbursement, refund,
                if (v("不计入收支") == "是") FinanceStatPolicy.EXCLUDE else FinanceStatPolicy.INCLUDE,
                if (v("不计入预算") == "是") FinanceBudgetPolicy.EXCLUDE else FinanceBudgetPolicy.INCLUDE)
        }
    }

    private fun date(value: String, date1904: Boolean): Long {
        if (Regex("[0-9]+(\\.[0-9]+)?").matches(value)) {
            val serial = BigDecimal(value)
            require(serial >= BigDecimal.ZERO && serial < BigDecimal("200000"))
            val days = serial.setScale(0, RoundingMode.FLOOR).longValueExact()
            require(date1904 || days != 60L) { "无效的 Excel 日期" }
            val epoch = if (date1904) LocalDate.of(1904, 1, 1) else LocalDate.of(1899, 12, 31)
            val actualDays = if (!date1904 && days > 60) days - 1 else days
            return epoch.plusDays(actualDays).atStartOfDay().plusNanos(
                serial.subtract(BigDecimal(days)).multiply(BigDecimal("86400000000000")).setScale(0, RoundingMode.HALF_UP).longValueExact()
            ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        val normalized = value.replace('/', '-').replace('T', ' ')
        val formatter = DateTimeFormatter.ofPattern("uuuu-M-d[ H:m[:s]]").withResolverStyle(ResolverStyle.STRICT)
        return runCatching { LocalDateTime.parse(normalized, formatter) }.getOrElse { LocalDate.parse(normalized, formatter).atStartOfDay() }
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
