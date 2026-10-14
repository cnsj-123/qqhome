package com.qq.closie.life.finance

/** Validates persisted/archived facts; never invents or silently repairs financial relationships. */
object FinanceIntegrity {
    fun validate(data: FinanceSnapshot) {
        fun <T> unique(rows: List<T>, id: (T) -> String) {
            require(rows.all { id(it).isNotBlank() } && rows.map(id).distinct().size == rows.size) { "账目身份重复或缺失" }
        }
        unique(data.accounts) { it.id }; unique(data.entries) { it.id }; unique(data.transfers) { it.id }
        unique(data.categories) { it.id }; unique(data.tags) { it.id }
        val accounts = data.accounts.associateBy { it.id }
        val entries = data.entries.associateBy { it.id }
        data.accounts.forEach {
            require(it.name.isNotBlank() && it.kind in FinanceAccountKind.entries) { "账户名称或类型无效" }
            FinanceMoney.fractionDigits(it.currencyCode)
        }
        data.categories.forEach { require(it.name.isNotBlank()) }
        data.tags.forEach { require(it.name.isNotBlank()) }
        data.entries.forEach {
            require(it.amountMinor > 0 && it.accountId in accounts && it.direction in FinanceDirection.entries) { "流水金额或账户无效" }
            require(it.categoryId == null || data.categories.any { category -> category.id == it.categoryId }) { "流水分类缺失" }
        }
        val legs = data.transfers.flatMap { listOf(it.outflowEntryId, it.inflowEntryId) }
        require(legs.size == legs.distinct().size) { "同一条流水不能属于多个转账" }
        data.transfers.forEach { transfer ->
            val out = requireNotNull(entries[transfer.outflowEntryId]) { "转出流水缺失" }
            val incoming = requireNotNull(entries[transfer.inflowEntryId]) { "转入流水缺失" }
            require(out.direction == FinanceDirection.OUTFLOW && incoming.direction == FinanceDirection.INFLOW &&
                out.accountId != incoming.accountId && out.amountMinor == incoming.amountMinor &&
                out.occurredAt == incoming.occurredAt && out.voidedAt == transfer.voidedAt && incoming.voidedAt == transfer.voidedAt &&
                accounts.getValue(out.accountId).currencyCode == accounts.getValue(incoming.accountId).currencyCode) { "转账两侧流水不一致" }
        }
        require(data.entryTags.distinct().size == data.entryTags.size)
        data.entryTags.forEach { require(it.entryId in entries && data.tags.any { tag -> tag.id == it.tagId }) { "流水标签关联缺失" } }
        data.accounts.forEach { FinanceProjection.balance(it, data.entries) }
        FinanceProjection.totals(FinanceProjection.ledger(data))
    }
}
