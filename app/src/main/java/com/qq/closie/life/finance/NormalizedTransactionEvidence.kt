package com.qq.closie.life.finance

/** Business source and explicit channel are hints, never canonical account identity. */
enum class TransactionSourceHint { DIDI, ELEME, RAILWAY_12306, JD, JD_BAITIAO, XIAOHONGSHU, GAODE, TAOBAO, TMALL, MEITUAN, PINDUODUO }
enum class PaymentChannelHint { WECHAT_PAY, ALIPAY, HUABEI, JD_BAITIAO, MEITUAN_MONTHLY, FAMILY_CARD,
    WECHAT_BALANCE, ACCOUNT_BALANCE, YUEBAO, DEBIT_CARD, CREDIT_CARD, BANK_CARD }

/** Transient minimized detail facts. Typed provenance stores only approved fields, never raw nodes/text. */
data class NormalizedTransactionEvidence(
    val providerKey: String, val amountMinor: Long, val currencyCode: String,
    val kind: FinanceProposalKind, val occurredAt: Long, val observedAt: Long,
    val referenceDigest: String, val extractionVersion: String,
    val transactionSource: TransactionSourceHint? = null, val paymentChannelHint: PaymentChannelHint? = null,
    val role: FinanceFlowRole = if (kind == FinanceProposalKind.INCOME) FinanceFlowRole.OTHER else FinanceFlowRole.PAYMENT,
    val relatedOrderDigest: String? = null,val referenceKind:FinanceReferenceKind=FinanceReferenceKind.PAYMENT
) {
    val hostProvider: String get() = providerKey
    fun toProposal(): FinanceProposalEntity {
        require(providerKey.matches(Regex("[a-z][a-z0-9-]{0,31}")))
        require(currencyCode == "CNY" && amountMinor > 0)
        require(referenceDigest.matches(Regex("[a-f0-9]{64}")))
        require(extractionVersion.matches(Regex("[a-z0-9-]{1,60}")))
        require(relatedOrderDigest == null || relatedOrderDigest.matches(Regex("[a-f0-9]{64}")))
        require(role != FinanceFlowRole.REFUND || kind == FinanceProposalKind.INCOME)
        require(occurredAt in 946684800000L..(observedAt + 300000L))
        // This compatibility shape also validates extractor output. Acquisition assigns a v3 capture ID.
        return FinanceProposalEntity(java.util.UUID.randomUUID().toString(), FinanceSource.VISIBLE_DETAIL,
            "visible:v2:$providerKey:$referenceDigest", "", observedAt, occurredAt,
            kind, amountMinor, "", role = role)
    }
}

object FinanceEvidenceOrigin {
    fun requiresManualAccount(proposal:FinanceProposalEntity)=isVisible(proposal) || proposal.source==FinanceSource.SELECTED_IMAGE
    fun isVisible(proposal: FinanceProposalEntity) = proposal.sourceKey.startsWith("visible:v1:") || proposal.sourceKey.startsWith("visible:v2:") || proposal.sourceKey.startsWith("visible:v3:")
    /** sourceKey is the existing typed evidence identity field, not user-visible prose. */
    fun strongReference(proposal: FinanceProposalEntity): String? {
        val parts = proposal.sourceKey.split(':')
        val provider: String
        val reference: String
        when {
            parts.size == 5 && parts.take(2) == listOf("visible", "v1") -> {
                provider = parts[2].lowercase(java.util.Locale.ROOT); reference = parts[4]
            }
            parts.size in setOf(4, 6) && parts.take(2) == listOf("visible", "v2") -> {
                provider = parts[2]; reference = parts[3]
                if (parts.size == 6 && (parts[4] != "conflict" || !parts[5].matches(Regex("[a-f0-9]{64}")))) return null
            }
            else -> return null
        }
        if (!provider.matches(Regex("[a-z][a-z0-9-]{0,31}")) || !reference.matches(Regex("[a-f0-9]{64}"))) return null
        return "visible:v2:$provider:$reference"
    }
    fun providerKey(proposal: FinanceProposalEntity) = if(proposal.sourceKey.startsWith("visible:v3:")) proposal.sourceKey.split(':').getOrNull(2) else strongReference(proposal)?.split(':')?.get(2)
    fun sameFacts(a: FinanceProposalEntity, b: FinanceProposalEntity) =
        a.amountMinor == b.amountMinor && a.kind == b.kind && a.occurredAt == b.occurredAt && a.role == b.role
    fun conflictKey(proposal: FinanceProposalEntity): String {
        val identity = requireNotNull(strongReference(proposal))
        val facts = "${proposal.kind.name}:${proposal.amountMinor}:${proposal.occurredAt}:${proposal.role.name}"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(facts.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$identity:conflict:$digest"
    }
    fun blockingPeers(proposal: FinanceProposalEntity, all: List<FinanceProposalEntity>): List<FinanceProposalEntity> {
        val identity = strongReference(proposal) ?: return emptyList()
        return all.filter { it.id != proposal.id && strongReference(it) == identity &&
            it.status in setOf(FinanceProposalStatus.ACTIVE, FinanceProposalStatus.SNOOZED, FinanceProposalStatus.CONFIRMED) }
    }
    fun label(proposal: FinanceProposalEntity) = if(proposal.source==FinanceSource.SELECTED_IMAGE) "用户所选图片 · 本地识别，字段需核对"
        else if (isVisible(proposal)) "当前可见交易详情 · 多字段一致"
        else "通知自动发现"
}
