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
            if (action.kind == FinanceActionKind.SUGGEST_CATEGORY) FinanceCategoryTree.proposalFields(action.value)
        }
    }
    fun apply(original: FinanceProposalEntity, rules: List<FinanceRuleSet>, transientText: String = original.evidenceText): FinanceRuleResult {
        var proposal = original
        val assigned = mutableSetOf<FinanceActionKind>()
        val tags = linkedSetOf<String>()
        val matched = mutableListOf<String>()
        rules.filter { it.rule.enabled && it.rule.deletedAt == null }.sortedWith(compareBy({ it.rule.priority }, { it.rule.id })).forEach { set ->
            validate(set)
            val matches = set.conditions.all { c ->
                when (c.kind) {
                    FinanceConditionKind.SOURCE -> original.source.name == c.value
                    FinanceConditionKind.TEXT_CONTAINS -> transientText.contains(c.value, ignoreCase = true)
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
                        FinanceActionKind.SUGGEST_CATEGORY -> {
                            val (category, subcategory) = FinanceCategoryTree.proposalFields(a.value)
                            proposal.copy(category = category, subcategory = subcategory)
                        }
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
