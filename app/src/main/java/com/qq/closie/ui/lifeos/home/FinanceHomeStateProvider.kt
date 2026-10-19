package com.qq.closie.ui.lifeos.home

import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.finance.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

/** Only explicitly scoped Canonical fields enter Home; evidence and extraction are never read. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FinanceHomeStateProvider(private val finance:FinanceRepository,private val clock:()->Long=System::currentTimeMillis):HomeStateProvider {
    override fun stateFor(date:LocalDate):Flow<HomeUiState> = RestoreStartupGate.state.map {it.status}.distinctUntilChanged().flatMapLatest {status->
        if(status!=RestoreStartupGate.Status.READY) flowOf(HomeUiState(date))
        else combine(finance.observeSnapshot(),flow {while(true){emit(clock());delay(1_000)}}) {s,now->
            val facts=FinancePurposeRead.facts(s,FinanceReadPurpose.HOME,"home-memory",now)
            fun title(f:FinanceGrantedFact)=listOfNotNull(f.description?.takeIf(String::isNotBlank),
                f.amountMinor?.let {"个人成本 "+FinanceMoney.display(it,f.currencyCode)+(if(f.incomplete) "，另有未知承担" else "")},
                "个人承担未知".takeIf {f.incomplete && f.amountMinor==null})
                .joinToString(" · ").ifBlank {"已授权的财务事项"}
            val dated=facts.mapNotNull {fact->fact.occurredAt?.let {Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() to fact}}
            HomeUiState(date,todayFacts=dated.filter {it.first==date}.map {HomeFactUi(it.second.eventId,title(it.second),"财务")},
                historyItems=dated.filter {it.first.isBefore(date)}.sortedByDescending {it.first}.take(20).map {HistoryItemUi(it.second.eventId,it.first,title(it.second))})
        }.catch {emit(HomeUiState(date))}
    }.distinctUntilChanged()
}
