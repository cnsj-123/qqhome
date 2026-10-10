package com.qq.closie.life.finance

/** Pure deterministic prefilling. Conditions inspect original evidence; first scalar suggestion wins. */
data class FinanceRuleSet(val rule: FinanceRuleEntity, val conditions: List<FinanceRuleConditionEntity>,
    val actions: List<FinanceRuleActionEntity>)
data class FinanceRuleResult(val proposal: FinanceProposalEntity, val tags: List<String>, val matched: List<String>)

object FinanceRuleEngine {
    fun validate(set: FinanceRuleSet) {
        require(set.rule.name.isNotBlank() && set.conditions.isNotEmpty() && set.actions.isNotEmpty()) { "请填写规则名称、条件与建议" }
        set.conditions.forEach { condition ->
            require(condition.value.isNotBlank())
            when (condition.kind) {
                FinanceConditionKind.SOURCE -> require(FinanceSource.entries.any { it.name == condition.value && it != FinanceSource.LEGACY_IMPORT })
                FinanceConditionKind.DIRECTION -> require(FinanceProposalKind.entries.any { it.name == condition.value })
                FinanceConditionKind.AMOUNT_MIN, FinanceConditionKind.AMOUNT_MAX -> require((condition.value.toLongOrNull() ?: -1) >= 0) { "金额条件使用非负分值" }
                else -> Unit
            }
        }
        set.actions.forEach { action ->
            require(action.value.isNotBlank())
            if (action.kind == FinanceActionKind.SUGGEST_BUSINESS_NATURE) require(FinanceNature.entries.any { it.name == action.value })
            if (action.kind == FinanceActionKind.SUGGEST_CATEGORY) require(action.value.split('/').size <= 2)
        }
    }
    fun apply(original: FinanceProposalEntity, rules: List<FinanceRuleSet>): FinanceRuleResult {
        var proposal = original
        val assigned = mutableSetOf<FinanceActionKind>()
        val tags = linkedSetOf<String>()
        val matched = mutableListOf<String>()
        rules.filter { it.rule.enabled && it.rule.deletedAt == null }.sortedWith(compareBy({ it.rule.priority }, { it.rule.id })).forEach { set ->
            validate(set)
            val matches = set.conditions.all { c ->
                when (c.kind) {
                    FinanceConditionKind.SOURCE -> original.source.name == c.value
                    FinanceConditionKind.TEXT_CONTAINS -> original.evidenceText.contains(c.value, ignoreCase = true)
                    FinanceConditionKind.DIRECTION -> original.kind.name == c.value
                    FinanceConditionKind.AMOUNT_MIN -> original.amountMinor?.let { it >= c.value.toLong() } == true
                    FinanceConditionKind.AMOUNT_MAX -> original.amountMinor?.let { it <= c.value.toLong() } == true
                    FinanceConditionKind.ACCOUNT -> original.accountId == c.value
                }
            }
            if (matches) {
                matched += set.rule.name
                set.actions.sortedBy { it.position }.forEach { a ->
                    if (a.kind == FinanceActionKind.ADD_TAG) tags += a.value.trim()
                    else if (assigned.add(a.kind)) proposal = when (a.kind) {
                        FinanceActionKind.SUGGEST_ACCOUNT -> proposal.copy(accountId = a.value)
                        FinanceActionKind.SUGGEST_CATEGORY -> proposal.copy(category = a.value.substringBefore('/').trim(),
                            subcategory = a.value.substringAfter('/', "").trim())
                        FinanceActionKind.NORMALIZE_DESCRIPTION -> proposal.copy(description = a.value)
                        FinanceActionKind.SUGGEST_BUSINESS_NATURE -> proposal.copy(nature = FinanceNature.valueOf(a.value))
                        FinanceActionKind.ADD_TAG -> proposal
                    }
                }
            }
        }
        return FinanceRuleResult(proposal, tags.toList(), matched)
    }
}

object FinanceNotificationParser {
    val packages = mapOf("com.tencent.mm" to FinanceSource.WECHAT, "com.eg.android.AlipayGphone" to FinanceSource.ALIPAY)
    private val money = Regex("(?<![0-9.,+\\-])(?:[¥￥]\\s*([0-9]+(?:\\.[0-9]{1,2})?)(?![0-9.])|([0-9]+(?:\\.[0-9]{1,2})?)\\s*元)")
    fun parse(packageName: String, sourceKey: String, title: String, body: String, occurredAt: Long): FinanceProposalEntity? {
        val source = packages[packageName] ?: return null
        val text = (title + "\n" + body).take(1800)
        if (listOf("失败", "未支付", "待付款", "待支付", "验证码", "优惠券", "营销", "请付款").any(text::contains)) return null
        if (listOf("支付", "收款", "到账", "退款").none(title::contains) && !(source == FinanceSource.ALIPAY && title == "支付宝")) return null
        val outflow = listOf("支付成功", "付款成功", "成功付款", "扣款成功", "已支付", "已付款").any(text::contains)
        val inflow = listOf("收款", "到账", "退款成功", "已退款").any(text::contains)
        if (outflow == inflow) return null
        val amounts = money.findAll(text).mapNotNull { match ->
            runCatching { java.math.BigDecimal(match.groupValues[1].ifBlank { match.groupValues[2] }).movePointRight(2).longValueExact() }
                .getOrNull()?.takeIf { it > 0 }
        }.toSet()
        if (amounts.isEmpty()) return null
        val now = System.currentTimeMillis()
        return FinanceProposalEntity(java.util.UUID.randomUUID().toString(), source, sourceKey, text, now, occurredAt,
            if (inflow) FinanceProposalKind.INCOME else FinanceProposalKind.EXPENSE,
            amounts.singleOrNull(), title.take(120),
            nature = if (text.contains("退款")) FinanceNature.OTHER else FinanceNature.PERSONAL)
    }
}
