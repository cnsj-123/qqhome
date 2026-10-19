package com.qq.closie.life.finance

data class FinanceBudgetProgress(val budget:FinanceBudgetEntity,val used:PersonalCostTotal,val remainingMinor:Long,
    val explanation:String)
data class FinanceDiagnostic(val id:String,val message:String,val entryIds:Set<String> =emptySet(),val eventIds:Set<String> =emptySet(),val accountIds:Set<String> =emptySet())
object FinanceInsightsProjection {
    fun budgets(s:FinanceSnapshot):List<FinanceBudgetProgress> {
        val rows=FinanceCostProjection.rows(s,forBudget=true);val tree=FinanceCategoryTree(s.categories)
        return s.v16?.budgetRows.orEmpty().filter {it.voidedAt==null}.map {budget->
            val categories=budget.categoryId?.let(tree::descendants)
            val total=FinanceCostProjection.total(rows.filter {it.currencyCode==budget.currencyCode && it.budgetIncluded &&
                it.occurredAt>=budget.startAt && it.occurredAt<budget.endExclusive && (categories==null || it.categoryId in categories)})
            FinanceBudgetProgress(budget,total,Math.subtractExact(budget.limitMinor,total.confirmedMinor),
                if(total.confirmedMinor<0) "本期实际退款或返利超过本期确认承担，已用为负数；没有回写旧月份。"
                else "个人承担与实际调整按各自发生期计入；内部转账和本金还款不重复消费。")
        }
    }
    fun burden(s:FinanceSnapshot,rows:List<PersonalCostRow>):Map<Pair<FinanceBurden?,String>,PersonalCostTotal> {
        val policies=s.v16?.categoryPolicyRows.orEmpty().filter {it.voidedAt==null}.associateBy {it.categoryId}
        val tree=FinanceCategoryTree(s.categories)
        return rows.groupBy {row->tree.path(row.categoryId).asReversed().firstNotNullOfOrNull {policies[it.id]?.burden} to row.currencyCode}
            .mapValues {FinanceCostProjection.total(it.value)}
    }
    fun diagnostics(s:FinanceSnapshot,now:Long):List<FinanceDiagnostic> {
        val active=s.entries.filter {it.voidedAt==null}
        val duplicates=active.groupBy {Triple(it.accountId,it.amountMinor,it.direction)}.values.flatMap {same->
            same.sortedBy {it.occurredAt}.zipWithNext().filter {(a,b)->b.occurredAt-a.occurredAt<=120_000}.map {(a,b)->
                FinanceDiagnostic("similar:${a.id}:${b.id}","两笔同账户同金额记录时间接近，可检查是否重复；不会自动作废。",setOf(a.id,b.id))
            }
        }
        val events=s.v4?.events.orEmpty().associateBy {it.id}
        val debts=FinanceObligationProjection.rows(s).filter {it.openMinor>0 && events[it.obligation.eventId]?.let {e->
            now>e.occurredAt && now-e.occurredAt>30L*86_400_000}==true}.map {it.obligation.eventId}.toSet()
        val pending=FinanceRelationsProjection.matters(s).filter {it.event.id !in debts && it.pendingMinor!=0L && now>it.event.occurredAt &&
            now-it.event.occurredAt>30L*86_400_000}.map {FinanceDiagnostic("pending:"+it.event.id,"这项往来或预计到账已超过30天，可复核或明确结束。",eventIds=setOf(it.event.id))}
        val balances=s.accounts.filter {it.archivedAt==null}.mapNotNull {account->
            val trust=FinanceAccountStatusProjection.status(s,account.id)
            if(trust.trust in setOf(FinanceTrust.DIFFERENT,FinanceTrust.INVALIDATED))
                FinanceDiagnostic("balance:"+account.id,account.name+"："+trust.explanation,accountIds=setOf(account.id)) else null
        }
        return (duplicates+debts.map {FinanceDiagnostic("obligation:"+it,"这项已确认往来尚有未结金额，距经济发生日已超过30天。",eventIds=setOf(it))}+pending+balances).take(100)
    }
}

/** A fully consumed, fixed local language. It returns visible conditions, never executable SQL. */
object FinanceLocalQueryParser {
    fun parse(text:String,s:FinanceSnapshot,today:java.time.LocalDate=java.time.LocalDate.now()):FinanceQuerySpec {
        require(text.length in 1..200)
        val tokens=text.trim().split(Regex("\\s+"));require(tokens.size>=2)
        val range=when(tokens.first()) {"本月"->FinanceDateRange.month(today);"今年"->FinanceDateRange.year(today);"全部"->FinanceDateRange();else->error("日期请使用：本月、今年或全部")}
        val mode=when(tokens.last()) {"消费","个人成本","花多少"->FinanceQueryMode.PERSONAL_COST;"真实流水"->FinanceQueryMode.CASH_MOVEMENT;else->error("末尾请使用：个人成本或真实流水")}
        var spec=FinanceQuerySpec(mode=mode,startInclusive=range.startInclusive,endExclusive=range.endExclusive)
        val used=mutableSetOf<String>()
        tokens.drop(1).dropLast(1).forEach {token->
            val parts=token.split('=',limit=2);require(parts.size==2 && parts[1].isNotBlank() && used.add(parts[0])) {"条件需完整且不能重复"}
            val value=parts[1]
            spec=when(parts[0]) {
                "账户"->spec.copy(accountIds=setOf(s.accounts.singleOrNull {it.name==value}?.id ?: error("账户不存在或同名有歧义")))
                "分类"->spec.copy(categoryIds=setOf(s.categories.singleOrNull {FinanceProjection.categoryPath(s.categories,it.id)==value || it.name==value}?.id ?: error("分类不存在或同名有歧义")))
                "标签"->spec.copy(tagIds=setOf(s.tags.singleOrNull {it.name==value}?.id ?: error("标签不存在或同名有歧义")))
                "币种"->spec.copy(currencyCode=value.also(FinanceMoney::fractionDigits))
                "备注"->spec.copy(text=value)
                else->error("无法完整解释此条件。支持账户、分类、标签、币种、备注；修改或删除账目请使用正式编辑入口。")
            }
        }
        spec.validate();return spec
    }
}
