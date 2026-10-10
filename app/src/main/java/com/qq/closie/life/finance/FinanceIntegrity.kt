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
        val categories = data.categories.associateBy { it.id }
        data.categories.forEach { category ->
            category.parentId?.let { parent ->
                require(parent != category.id && categories[parent]?.parentId == null && parent in categories) { "分类层级无效" }
            }
        }
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
        data.v4?.let { v4 ->
            unique(v4.events) { it.id }; unique(v4.expected) { it.id }; unique(v4.links) { it.id }
            val events = v4.events.associateBy { it.id }
            val eventLinks = v4.activeLinks.groupBy { it.eventId }
            val transferLegs = legs.toSet()
            require(v4.activeLinks.map { it.eventId to it.entryId }.distinct().size == v4.activeLinks.size) { "事件关联重复" }
            v4.links.forEach { link ->
                val event = requireNotNull(events[link.eventId]) { "关联事件缺失" }
                val entry = requireNotNull(entries[link.entryId]) { "关联流水缺失" }
                require(link.allocatedMinor > 0 && entry.id !in transferLegs) { "分配金额或流水类型无效" }
                require(link.revision >= 1 && link.updatedAt >= link.createdAt &&
                    (link.voidedAt == null || link.voidedAt >= link.createdAt)) { "关系生命周期无效" }
                if (link.voidedAt != null) return@forEach
                require(entry.voidedAt == null) { "有效关系不能关联已作废流水" }
                require(accounts.getValue(entry.accountId).currencyCode == event.currencyCode) { "事件币种不一致" }
                require(event.relatedTransferId == null || link.role == FinanceFlowRole.FEE) { "转账手续费事件只能关联手续费" }
                if (entry.voidedAt == null) {
                    require(event.voidedAt == null) { "有效流水不能关联已作废事件" }
                    when (link.role) {
                        FinanceFlowRole.PAYMENT, FinanceFlowRole.FEE -> require(entry.direction == FinanceDirection.OUTFLOW) { "付款/手续费必须为流出" }
                        FinanceFlowRole.REFUND, FinanceFlowRole.REBATE, FinanceFlowRole.CASHBACK, FinanceFlowRole.REIMBURSEMENT ->
                            require(entry.direction == FinanceDirection.INFLOW) { "到账必须为流入" }
                        else -> Unit
                    }
                }
            }
            v4.activeLinks.groupBy { it.entryId }.forEach { (id, links) ->
                if (entries.getValue(id).voidedAt == null) require(
                    links.fold(java.math.BigInteger.ZERO) { sum, it -> sum + it.allocatedMinor.toBigInteger() } ==
                        entries.getValue(id).amountMinor.toBigInteger()) { "事件分配与实际流水不一致" }
            }
            v4.events.forEach {
                require(it.relatedTransferId == null || data.transfers.any { transfer -> transfer.id == it.relatedTransferId }) { "手续费对应的转账缺失" }
                require(it.description.isNotBlank() && (it.personalShareMinor == null || it.personalShareMinor >= 0)) { "事件信息无效" }
                FinanceMoney.fractionDigits(it.currencyCode)
                require(it.voidedAt != null || eventLinks[it.id].orEmpty().isNotEmpty()) { "有效事件缺少有效流水" }
                if (it.voidedAt == null && it.personalShareMinor != null) {
                    val paid = eventLinks[it.id].orEmpty().filter { link ->
                        entries[link.entryId]?.voidedAt == null && link.role in setOf(FinanceFlowRole.PAYMENT, FinanceFlowRole.FEE)
                    }.fold(java.math.BigInteger.ZERO) { sum, link -> sum + link.allocatedMinor.toBigInteger() }
                    require(it.personalShareMinor.toBigInteger() <= paid) { "个人承担超过当前有效付款，请先调整事件承担额" }
                }
            }
            v4.expected.forEach {
                require(it.eventId in events && (it.amountMinor == null || it.amountMinor > 0)) { "预计关系无效" }
                require(it.cancelledAt != null || (events.getValue(it.eventId).voidedAt == null &&
                    eventLinks[it.eventId].orEmpty().isNotEmpty())) { "有效预计关系不能依附孤立或已作废事件" }
            }
            unique(v4.batches) { it.id }; unique(v4.staging) { it.id }; unique(v4.proposals) { it.id }; unique(v4.rules) { it.id }
            require(v4.batches.map { it.digest }.distinct().size == v4.batches.size)
            require(v4.proposals.map { it.sourceKey }.distinct().size == v4.proposals.size)
            val batchIds = v4.batches.map { it.id }.toSet()
            val proposalIds = v4.proposals.map { it.id }.toSet()
            val ruleIds = v4.rules.map { it.id }.toSet()
            v4.staging.forEach { require(it.batchId in batchIds) }
            v4.proposalTags.forEach { require(it.proposalId in proposalIds) }
            v4.conditions.forEach { require(it.ruleId in ruleIds) }
            v4.actions.forEach { require(it.ruleId in ruleIds) }
            val canonicalIds = entries.keys + data.transfers.map { it.id }
            v4.proposals.forEach {
                require(it.amountMinor == null || it.amountMinor > 0)
                if (it.status == FinanceProposalStatus.CONFIRMED) require(it.canonicalId in canonicalIds) { "确认记录的正式流水缺失" }
            }
            val conditions = v4.conditions.groupBy { it.ruleId }
            val actions = v4.actions.groupBy { it.ruleId }
            v4.rules.filter { it.deletedAt == null }.forEach {
                FinanceRuleEngine.validate(FinanceRuleSet(it, conditions[it.id].orEmpty(), actions[it.id].orEmpty()))
            }
        }
        FinanceProjection.totals(FinanceProjection.ledger(data))
    }
}
