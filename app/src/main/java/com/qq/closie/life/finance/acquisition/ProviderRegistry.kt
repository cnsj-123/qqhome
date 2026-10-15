package com.qq.closie.life.finance.acquisition

data class ProviderDefinition(val key: String, val displayName: String, val packageNames: Set<String>,
    val detailScope: String, val adapter: TransactionDetailAdapter, val defaultEnabled: Boolean = false)

object VisibleProviderKeys {
    const val WECHAT = "wechat"
    const val ALIPAY = "alipay"
    const val TAOBAO = "taobao"
    const val JD = "jd"
    const val MEITUAN = "meituan"
    const val PINDUODUO = "pinduoduo"
    const val UNIONPAY = "unionpay"
}

/** Only defensibly implemented scopes appear here. Research targets are not advertised as support. */
object ProviderRegistry {
    val providers = listOf(ProviderDefinition(VisibleProviderKeys.WECHAT, "微信", setOf("com.tencent.mm"),
        "转账、红包、扫码、商户及平台款项详情（字段齐全时）", WeChatDetailAdapter),
        ProviderDefinition(VisibleProviderKeys.ALIPAY, "支付宝", setOf("com.eg.android.AlipayGphone"),
            "账单支付、代付、自动扣款及独立退款详情", AlipayDetailAdapter),
        ProviderDefinition(VisibleProviderKeys.TAOBAO, "淘宝 / 天猫", setOf("com.taobao.taobao"),
            "淘宝宿主内电商与闪购订单（须展开时间与编号）", TaobaoDetailAdapter),
        ProviderDefinition(VisibleProviderKeys.JD, "京东", setOf("com.jingdong.app.mall"),
            "完成订单、账单及白条款项（须有时间与编号）", JdDetailAdapter),
        ProviderDefinition(VisibleProviderKeys.MEITUAN, "美团", setOf("com.sankuai.meituan"),
            "账单、当地服务及月付款项详情", MeituanDetailAdapter),
        ProviderDefinition(VisibleProviderKeys.PINDUODUO, "拼多多", setOf("com.xunmeng.pinduoduo"),
            "已完成订单的实付、时间与编号", PinduoduoDetailAdapter),
        ProviderDefinition(VisibleProviderKeys.UNIONPAY, "云闪付", setOf("com.unionpay"),
            "云闪付交易及独立退款详情", UnionPayDetailAdapter))
    init {
        require(providers.map { it.key }.distinct().size == providers.size)
        val packages = providers.flatMap { it.packageNames }
        require(packages.distinct().size == packages.size)
    }
    fun find(key: String) = providers.singleOrNull { it.key == key }
    fun enabled(keys: Set<String>) = providers.filter { it.key in keys }
}

/** Dispatch is package + user opt-in. There is no catch-all page parser. */
object VisibleTransactionEvidenceRouter {
    fun provider(packageName: String, enabled: Set<String>): ProviderDefinition? =
        ProviderRegistry.enabled(enabled).singleOrNull { packageName in it.packageNames }
}
