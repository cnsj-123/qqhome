package com.qq.closie.life.finance

enum class FinanceAssistantCapability { QUERY_CONDITIONS, EXPLANATION, ANALYSIS, CHANGE_SUGGESTION }
enum class FinanceProviderTransport { LOCAL_FAKE, REMOTE }
data class FinanceDisclosurePlan(val providerId: String, val eventIds: Set<String>,
    val fields: Set<String>, val aiGrantId: String, val egressGrantId: String)
data class DisclosedFinanceRow(val eventId: String, val currencyCode: String,
    val amountMinor: Long?, val occurredAt: Long?, val description: String?)
data class FinanceAssistanceRequest(val requestId: String, val capability: FinanceAssistantCapability,
    val question: String, val disclosure: FinanceDisclosurePlan, val rows: List<DisclosedFinanceRow>,
    val dataRevision: String, val policyRevision: Long, val restoreEpoch: Long)
sealed interface FinanceAssistanceResult {
    data class QuerySuggestion(val spec: FinanceQuerySpec) : FinanceAssistanceResult
    data class Explanation(val text: String) : FinanceAssistanceResult
    /** Text must enter explicit user review; it has no executable command or canonical capability. */
    data class ChangeSuggestion(val text: String) : FinanceAssistanceResult
}

/** Vendor neutral. The interface cannot receive a Repository, DAO, path, or write tool. */
interface FinanceAssistantProvider {
    val id: String
    val transport: FinanceProviderTransport
    val capabilities: Set<FinanceAssistantCapability>
    suspend fun assist(request: FinanceAssistanceRequest): FinanceAssistanceResult
}

data class FinanceAssistancePolicy(val enabled: Boolean = false, val restoreEpoch: Long,
    val dataRevision: String, val grants: List<FinancePurposeGrantEntity>, val scope: List<FinanceGrantEventEntity>)

/** The only V16 dispatch path permits a local fake. No real-cloud client is wired or called. */
class FinanceAssistantCoordinator(private val readPolicy: suspend () -> FinanceAssistancePolicy,
    private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun request(provider: FinanceAssistantProvider, request: FinanceAssistanceRequest): FinanceAssistanceResult {
        val before = readPolicy()
        validate(before, provider, request)
        val result = provider.assist(request)
        val after = readPolicy()
        validate(after, provider, request)
        require(after.dataRevision == before.dataRevision && after.grants == before.grants && after.scope == before.scope) { "数据或授权已改变，请重新复核" }
        when (result) {
            is FinanceAssistanceResult.QuerySuggestion -> {
                result.spec.validate()
                require(result.spec.eventIds.isNotEmpty() && request.disclosure.eventIds.containsAll(result.spec.eventIds)) { "建议扩大了授权范围" }
            }
            is FinanceAssistanceResult.Explanation -> require(result.text.length <= 2_000)
            is FinanceAssistanceResult.ChangeSuggestion -> require(result.text.length <= 2_000)
        }
        return result
    }
    private fun validate(policy: FinanceAssistancePolicy, provider: FinanceAssistantProvider, request: FinanceAssistanceRequest) {
        require(policy.enabled && provider.transport == FinanceProviderTransport.LOCAL_FAKE) { "云能力关闭；本版本仅离线验证 Provider 契约" }
        require(request.capability in provider.capabilities && request.question.length <= 500)
        require(policy.restoreEpoch == request.restoreEpoch && policy.dataRevision == request.dataRevision)
        require(request.disclosure.providerId == provider.id && request.disclosure.eventIds.isNotEmpty())
        require(request.rows.size <= 50 && request.rows.map { it.eventId }.distinct().size == request.rows.size) { "请缩小披露范围" }
        val purposes = listOf(request.disclosure.aiGrantId to FinanceReadPurpose.AI_READ,
            request.disclosure.egressGrantId to FinanceReadPurpose.CLOUD_SEND)
        purposes.forEach { (id,purpose) ->
            val grant = requireNotNull(policy.grants.singleOrNull { it.id == id && it.purpose == purpose &&
                it.providerId == provider.id && it.voidedAt == null && it.expiresAt > clock() }) { "用途未授权或已撤销" }
            require(grant.revision == request.policyRevision)
            val scope = policy.scope.filter { it.grantId == grant.id }.map { it.eventId }.toSet()
            require(scope.containsAll(request.disclosure.eventIds))
            require(request.disclosure.fields.all { it in setOf("amount", "date", "description") })
            require("amount" !in request.disclosure.fields || grant.allowAmount)
            require("date" !in request.disclosure.fields || grant.allowDate)
            require("description" !in request.disclosure.fields || grant.allowDescription)
        }
        require(request.rows.all { it.eventId in request.disclosure.eventIds &&
            (it.amountMinor == null || "amount" in request.disclosure.fields) &&
            (it.occurredAt == null || "date" in request.disclosure.fields) &&
            (it.description == null || "description" in request.disclosure.fields) })
    }
}
