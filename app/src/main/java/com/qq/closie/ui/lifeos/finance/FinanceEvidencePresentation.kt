package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.finance.*
import com.qq.closie.life.finance.acquisition.ProviderRegistry

/** Provider display belongs to presentation; canonical account identity is independent. */
internal fun FinanceProposalEntity.discoverySourceLabel(): String =
    if (FinanceEvidenceOrigin.isVisible(this)) FinanceEvidenceOrigin.providerKey(this)
        ?.let { ProviderRegistry.find(it)?.displayName } ?: "交易详情来源"
    else when (source) {
        FinanceSource.WECHAT -> "微信"
        FinanceSource.ALIPAY -> "支付宝"
        FinanceSource.LEGACY_IMPORT -> "导入"
        FinanceSource.VISIBLE_DETAIL -> "交易详情来源"
    }
