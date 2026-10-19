package com.qq.closie.life.finance

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Test
import org.junit.Assert.*

class FinancePortableExportTest {
    @Test fun csvProtectsTextFormulasAndKeepsNumericNegativeAdjustmentsNumeric() {
        assertEquals("\"' =HYPERLINK(\"\"x\"\")\"",FinancePortableExport.csvCell(" =HYPERLINK(\"x\")"))
        assertEquals("\"-4000\"",FinancePortableExport.csvCell(-4000L))
        assertEquals("\"'@SUM(A1)\"",FinancePortableExport.csvCell("@SUM(A1)"))
        assertEquals("\"a,b\"\"c\"",FinancePortableExport.csvCell("a,b\"c"))
    }
    @Test fun emptyPortableArchiveContainsTypedFactsAndSeparateReadableTables() {
        val output=ByteArrayOutputStream();FinancePortableExport.write(FinanceSnapshot(),output)
        val names=mutableSetOf<String>()
        ZipInputStream(output.toByteArray().inputStream()).use {zip->while(true){val entry=zip.nextEntry ?: break;names.add(entry.name);zip.readBytes()}}
        assertTrue(names.containsAll(setOf("README.txt","finance-facts-v5.json","cash.csv","personal-cost.csv","obligations.csv","event-links.csv","purchase-lines.csv")))
    }
}
