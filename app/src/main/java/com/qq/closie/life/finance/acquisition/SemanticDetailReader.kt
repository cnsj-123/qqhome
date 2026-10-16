package com.qq.closie.life.finance.acquisition

import com.qq.closie.life.finance.TransactionSourceHint

internal interface DetailNode : AutoCloseable {
    val visible: Boolean
    val sensitive: Boolean
    val text: CharSequence?
    val childCount: Int
    fun child(index: Int): DetailNode?
    fun parent(): DetailNode?
}

/** Two passes: allowlisted fingerprint first, then bounded label/value rows and header relationships.
 * No view IDs, coordinates, actions, contentDescription, raw tree copy or whole-page string. */
internal object SemanticDetailReader {
    private const val MAX_NODES = 400
    private const val MAX_DEPTH = 30
    private const val MAX_ROW_NODES = 18
    private val excluded = setOf("订单金额", "商品总价", "商品原价", "到手价", "原价", "运费", "进口税", "店铺优惠",
        "平台优惠", "优惠金额", "支付优惠", "支付立减", "支付宝随机立减", "红包", "优惠券", "淘金币", "共减")
    private val privateLabels = setOf("收货人", "收件人", "姓名", "手机号", "手机号码", "电话", "邮箱", "地址",
        "收货地址", "配送地址", "入住人", "住客", "申请人", "对方账户", "快递单号", "物流公司", "出发", "到达")
    private fun text(node: DetailNode) = if (!node.visible || node.sensitive) null
        else node.text?.takeIf { it.length <= 96 }?.toString()?.trim()?.takeIf(String::isNotEmpty)
    private fun label(value: String, labels: Set<String>): Pair<String, String?>? {
        val v = value.trim()
        return labels.sortedByDescending(String::length).firstNotNullOfOrNull { key ->
            when {
                DetailSemantics.compact(v).trimEnd(':', '：') == key -> key to null
                v.indexOfFirst { it == ':' || it == '：' }.takeIf { it >= 0 }?.let {
                    DetailSemantics.compact(v.substring(0, it)) == key
                } == true -> key to v.substring(v.indexOfFirst { it == ':' || it == '：' } + 1).trim()
                key in DetailSemantics.moneyLabels && v.startsWith(key) &&
                    DetailSemantics.money(v.substring(key.length)) != null -> key to v.substring(key.length)
                else -> null
            }
        }
    }
    fun read(root: DetailNode, adapter: TransactionDetailAdapter): VisibleDetailFields? {
        var visited = 0
        var overflow = false
        fun walk(node: DetailNode, depth: Int, action: (DetailNode) -> Unit) {
            if (++visited > MAX_NODES || depth > MAX_DEPTH || node.childCount > 80) { overflow = true; return }
            if (!node.visible || node.sensitive) return
            action(node)
            for (i in 0 until node.childCount) {
                val child = node.child(i) ?: continue
                child.use { walk(it, depth + 1, action) }
                if (overflow) return
            }
        }
        val labels = mutableSetOf<String>()
        val markers = mutableSetOf<String>()
        walk(root, 0) { node -> text(node)?.let { value ->
            label(value, adapter.fieldLabels)?.first?.let(labels::add)
            if (value in adapter.pageMarkers) markers += value
            if (DetailSemantics.refundSummary(value)) markers += "REFUND_SUMMARY"
        } }
        if (overflow || !adapter.hasFingerprint(labels, markers)) return null

        // A row cannot contain another allowlisted label; a flat/merged page therefore fails closed.
        fun local(node: DetailNode): List<String>? {
            var count = 0
            var bad = false
            val values = mutableListOf<String>()
            fun visit(n: DetailNode, depth: Int) {
                if (++count > MAX_ROW_NODES || depth > 3 || n.childCount > 12) { bad = true; return }
                if (!n.visible || n.sensitive) return
                text(n)?.let(values::add)
                for (i in 0 until n.childCount) n.child(i)?.use { visit(it, depth + 1) }
            }
            visit(node, 0)
            return values.takeUnless { bad }
        }
        val rows = mutableMapOf<String, MutableList<String>>()
        val headerAmounts = mutableListOf<String>()
        val headerSources = mutableSetOf<TransactionSourceHint>()
        visited = 0
        walk(root, 0) { node ->
            val value = text(node) ?: return@walk
            val match = label(value, adapter.fieldLabels)
            if (match != null) {
                val (key, inline) = match
                val values = if (inline != null) listOf(inline) else {
                    val row = if (key in DetailSemantics.channelLabels && node.childCount > 0) local(node)
                        else node.parent()?.use { parent -> local(parent) }
                    row?.takeIf { texts -> texts.mapNotNull { label(it, adapter.fieldLabels + privateLabels)?.first }.toSet() == setOf(key) }
                        ?.filter { label(it, adapter.fieldLabels) == null && it !in setOf("复制", ">", "收起") }
                }.orEmpty()
                val parsed = if (key in DetailSemantics.referenceLabels) {
                    // Join wraps only inside one labeled reference row, never across rows.
                    values.filter { it.matches(Regex("[A-Za-z0-9_\\-\\s]+")) }.takeIf { it.size in 1..3 }
                        ?.joinToString("")?.let { adapter.fieldValue(key, it) }
                } else values.mapNotNull { adapter.fieldValue(key, it) }.singleOrNull()
                if (parsed != null) rows.getOrPut(key) { mutableListOf() } += parsed
            }
            if (value in adapter.headlineStatuses) {
                node.parent()?.use { parent -> local(parent)?.takeIf { texts ->
                    texts.none { label(it, adapter.fieldLabels + excluded + privateLabels) != null }
                }?.let { texts ->
                    val amounts = texts.mapNotNull(DetailSemantics::money)
                    if (amounts.size == 1) {
                        headerAmounts += amounts.single()
                        texts.mapNotNull(DetailSemantics::source).forEach(headerSources::add)
                    }
                } }
            } else if (adapter.signedHeadline && value.matches(Regex("[+−-][¥￥]?[0-9,]+\\.[0-9]{2}"))) {
                node.parent()?.use { parent -> local(parent)?.takeIf { texts ->
                    texts.none { label(it, adapter.fieldLabels + excluded + privateLabels) != null }
                }?.let { texts ->
                    if (texts.mapNotNull(DetailSemantics::money).size == 1) {
                        headerAmounts += requireNotNull(DetailSemantics.money(value))
                        texts.mapNotNull(DetailSemantics::source).forEach(headerSources::add)
                    }
                } }
            }
        }
        if (overflow || rows.values.any { it.size != 1 }) return null
        return VisibleDetailFields(rows.mapValues { it.value.single() },
            headerAmounts.singleOrNull()?.let(::setOf).orEmpty(), markers, headerSources.singleOrNull())
    }
}
