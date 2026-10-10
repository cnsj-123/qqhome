package com.qq.closie.life.finance

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic source only; fixtures never read the owner's private workbook. */
internal object SyntheticLegacyLedger {
    fun row(vararg values: Pair<String, String>): List<String> {
        val cells = mapOf("日期" to "2026-10-01 12:00:00", "金额" to "12.34", "类型" to "支出",
            "备注" to "合成记录", "账户" to "合成钱包") + values.toMap()
        return LegacyLedgerReader.headers.map { cells[it].orEmpty() }
    }
    fun csv(file: File, rows: List<List<String>>) {
        file.writeText((listOf(LegacyLedgerReader.headers) + rows).joinToString("\r\n") {
            it.joinToString(",") { value -> "\"" + value.replace("\"", "\"\"") + "\"" }
        }, Charsets.UTF_8)
    }
    fun xlsx(file: File, rows: List<List<String>>, dimension: String = "A1:V1") {
        fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val sheet = (listOf(LegacyLedgerReader.headers) + rows).mapIndexed { rowIndex, cells ->
            "<row r=\"" + (rowIndex + 1) + "\">" + cells.mapIndexed { col, value ->
                "<c r=\"" + ('A' + col) + (rowIndex + 1) + "\" t=\"inlineStr\"><is><t>" + xml(value) + "</t></is></c>"
            }.joinToString("") + "</row>"
        }.joinToString("")
        ZipOutputStream(file.outputStream()).use { out ->
            fun part(name: String, text: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
            part("[Content_Types].xml", """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
            part("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            part("xl/workbook.xml", """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="账单列表" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            part("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
            part("xl/worksheets/sheet1.xml", """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><dimension ref="$dimension"/><sheetData>$sheet</sheetData></worksheet>""")
        }
    }
}
