package com.qq.closie.ui.lifeos.finance

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.qq.closie.life.finance.*

/** Presentation state only, separate for each main space. No cached financial totals are saved. */
internal object FinanceFilterState {
    const val MAX_QUERY=200
    fun validated(filter:FinanceFilter):FinanceFilter {
        val next=filter.copy(query=filter.query.take(MAX_QUERY))
        FinanceQuerySpec(startInclusive=next.range.startInclusive,endExclusive=next.range.endExclusive,
            accountIds=setOfNotNull(next.accountId),categoryIds=setOfNotNull(next.categoryId),tagIds=setOfNotNull(next.tagId),
            currencyCode=next.currencyCode,minimumMinor=next.minimumMinor,maximumMinor=next.maximumMinor,text=next.query).validate()
        return next
    }
    fun encode(filter:FinanceFilter)=Bundle().apply {
        putString("query",filter.query);putString("period",filter.period.name);putString("label",filter.customLabel)
        filter.range.startInclusive?.let {putLong("start",it)};filter.range.endExclusive?.let {putLong("end",it)}
        putString("account",filter.accountId);putString("category",filter.categoryId);putString("tag",filter.tagId)
        putString("nature",filter.nature?.name);putString("currency",filter.currencyCode)
        filter.minimumMinor?.let {putLong("minimum",it)};filter.maximumMinor?.let {putLong("maximum",it)}
    }
    fun decode(bundle:Bundle?):FinanceFilter = if(bundle==null) FinanceFilter() else runCatching {
        fun number(key:String)=if(bundle.containsKey(key)) bundle.getLong(key) else null
        validated(FinanceFilter(bundle.getString("query").orEmpty(),FinancePeriod.valueOf(requireNotNull(bundle.getString("period"))),
            FinanceDateRange(number("start"),number("end")),bundle.getString("label").orEmpty(),bundle.getString("account"),
            bundle.getString("category"),bundle.getString("tag"),bundle.getString("nature")?.let(FinanceNature::valueOf),
            number("minimum"),number("maximum"),bundle.getString("currency")))
    }.getOrDefault(FinanceFilter())
}

internal class FinancePresentationFactory(private val repository:FinanceRepository):ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T:ViewModel> create(modelClass:Class<T>,extras:CreationExtras):T {
        require(modelClass.isAssignableFrom(FinanceViewModel::class.java))
        return FinanceViewModel(repository,extras.createSavedStateHandle()) as T
    }
}
