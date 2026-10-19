package com.qq.closie.life.finance

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Portable report only. The typed JSON preserves original IDs; it is never read as another ledger. */
object FinancePortableExport {
    fun csvCell(value:Any?):String {
        val text=value?.toString().orEmpty()
        // Spreadsheet applications also recognize formulas after leading whitespace/control characters.
        val safe=if(value !is Number && (text.trimStart().firstOrNull() in setOf('=','+','-','@') || text.firstOrNull() in setOf('\t','\r','\n'))) "'"+text else text
        return "\""+safe.replace("\"","\"\"")+"\""
    }
    fun write(s:FinanceSnapshot,output:OutputStream) {
        FinanceIntegrity.validate(s)
        val accounts=s.accounts.associateBy {it.id}
        ZipOutputStream(output).use {zip->
            fun entry(name:String,text:String) {zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray(Charsets.UTF_8));zip.closeEntry()}
            fun csv(name:String,header:List<String>,rows:List<List<Any?>>) {
                entry(name,"\uFEFF"+(listOf(header)+rows).joinToString("\r\n",postfix="\r\n") {row->row.joinToString(",",transform=::csvCell)})
            }
            entry("README.txt","Life OS 财务数据携出 v5\n本包为明文，请自行保管。金额同时提供精确最小币种单位与可读金额；负成本调整保持负值。\n资金流水、个人成本、义务与购买明细分别列出，不能将这些表简单相加。\nCSV文本防止表格公式执行，原始字段在typed JSON中保留。附件原件与完整跨域恢复请使用设置中的加密备份。\n")
            entry("finance-facts-v5.json",com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(s))
            csv("accounts.csv",listOf("account_id","name","kind","currency","opening_minor","anchor_at","archived_at"),s.accounts.map {listOf(it.id,it.name,it.kind,it.currencyCode,it.openingBalanceMinor,it.balanceAnchorAt,it.archivedAt)})
            csv("cash.csv",listOf("entry_id","account_id","direction","currency","amount_minor","amount","cash_at","recorded_at","description","category_id","revision","voided_at"),s.entries.map {listOf(it.id,it.accountId,it.direction,accounts.getValue(it.accountId).currencyCode,it.amountMinor,FinanceMoney.input(it.amountMinor,accounts.getValue(it.accountId).currencyCode),it.occurredAt,it.recordedAt,it.description,it.categoryId,it.revision,it.voidedAt)})
            csv("personal-cost.csv",listOf("cost_row_id","event_id","entry_id","currency","amount_minor_or_unknown","economic_or_adjustment_at","actual_adjustment","description"),FinanceCostProjection.rows(s).map {listOf(it.id,it.eventId,it.entryId,it.currencyCode,it.amountMinor,it.occurredAt,it.adjustment,it.description)})
            csv("event-links.csv",listOf("link_id","event_id","entry_id","role","allocated_minor","voided_at"),s.v4?.links.orEmpty().map {listOf(it.id,it.eventId,it.entryId,it.role,it.allocatedMinor,it.voidedAt)})
            csv("obligations.csv",listOf("obligation_id","event_id","creditor_id","debtor_id","currency","amount_minor","cash_settled_minor","noncash_resolved_minor","open_minor","cash_evidence_complete"),FinanceObligationProjection.rows(s).map {listOf(it.obligation.id,it.obligation.eventId,it.obligation.creditorId,it.obligation.debtorId,it.obligation.currencyCode,it.obligation.amountMinor,it.settledMinor,it.nonCashResolvedMinor,it.openMinor,it.cashEvidenceComplete)})
            csv("purchase-lines.csv",listOf("line_id","purchase_id","title","quantity","value_minor_or_unknown","voided_at"),s.v16?.purchaseLineRows.orEmpty().map {listOf(it.id,it.purchaseId,it.title,it.quantity,it.valueMinor,it.voidedAt)})
        }
    }
}
