package com.qq.closie.life.permissions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

enum class ReadModule { FINANCE, HEALTH, KNOWLEDGE, CLOSET, HOBBIES }
enum class ModuleReadMode { ASK, ALLOW, DENY }
enum class ModuleGrantDuration { ONCE, CONVERSATION, LONG_TERM }
data class ModuleReadRule(val mode: ModuleReadMode = ModuleReadMode.ASK, val epoch: Long = -1, val revision: Long = 0)
data class ModuleReadRequest(val module: ReadModule, val actorId: String, val requestId: String,
    val restoreEpoch: Long, val conversationId: String? = null, val restoreGeneration: Long = 0)
class ModuleReadPermit internal constructor(internal val id: String)
class ModuleConsentRequired : IllegalStateException("此模块读取需要你确认")

/** Control-plane permission is additional to record/field/purpose grants. It never owns facts.
 * Session grants contain only opaque request identities, live in memory, and cannot survive restart.
 * A one-shot is consumed before disclosure; its permit is rechecked after the provider returns. */
class ModuleAccessOwner(
    private val readRule: suspend (ReadModule) -> ModuleReadRule,
    private val writeRule: suspend (ReadModule, ModuleReadRule) -> Unit,
    private val readEpoch: suspend () -> Long,
    private val readGeneration: () -> Long = { 0 },
    private val ready: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Grant(val request: ModuleReadRequest, val duration: ModuleGrantDuration, val mode: ModuleReadMode, val baseline: ModuleReadRule)
    private data class Lease(val permit: ModuleReadPermit, val request: ModuleReadRequest, val rule: ModuleReadRule, val expiresAt: Long)
    private val mutex = Mutex()
    private val grants = mutableListOf<Grant>()
    private val leases = mutableMapOf<String, Lease>()
    private val deniedUntilSaved = mutableSetOf<ReadModule>()
    private val mutablePending = MutableStateFlow<Map<ReadModule, ModuleReadRequest>>(emptyMap())
    val pending = mutablePending.asStateFlow()

    suspend fun authorize(module: ReadModule, mode: ModuleReadMode, duration: ModuleGrantDuration,
        expectedEpoch: Long, request: ModuleReadRequest? = null, expectedRevision: Long? = null,
        expectedGeneration: Long? = null) = mutex.withLock {
        require(readEpoch() == expectedEpoch) { "恢复后请重新打开授权" }
        val generation = readGeneration()
        require(expectedGeneration == null || expectedGeneration == generation) { "恢复后请重新打开授权" }
        val previous = readRule(module)
        require(expectedRevision == null || previous.revision == expectedRevision) { "模块策略已改变，请重新打开" }
        if (duration != ModuleGrantDuration.LONG_TERM) {
            require(request != null && request.module == module && request.restoreEpoch == expectedEpoch && request.restoreGeneration == generation &&
                mutablePending.value[module] == request) { "请先发起一次真实读取请求" }
            require(duration != ModuleGrantDuration.CONVERSATION || !request.conversationId.isNullOrBlank()) { "本次请求没有对话身份" }
        }
        // Updating or revoking permissions immediately invalidates in-flight disclosures.
        grants.removeAll { it.request.module == module }
        leases.entries.removeAll { it.value.request.module == module }
        // Revoke in-memory grants before a suspending disk write. A cancelled/failed DENY
        // remains denied in this process, even if storage cannot acknowledge its commit.
        if (duration == ModuleGrantDuration.LONG_TERM && mode == ModuleReadMode.DENY) deniedUntilSaved += module
        if (duration == ModuleGrantDuration.LONG_TERM) {
            writeRule(module, ModuleReadRule(mode, expectedEpoch, Math.addExact(previous.revision, 1)))
        } else grants += Grant(requireNotNull(request), duration, mode, previous)
        mutablePending.value = mutablePending.value - module
        require(readEpoch() == expectedEpoch && readGeneration() == generation && ready()) { "恢复后此授权已失效" }
        if (duration == ModuleGrantDuration.LONG_TERM && mode != ModuleReadMode.DENY) deniedUntilSaved -= module
    }

    suspend fun reserve(request: ModuleReadRequest): ModuleReadPermit = mutex.withLock {
        require(request.actorId.isNotBlank() && request.actorId.length <= 128 && request.requestId.isNotBlank() && request.requestId.length <= 128 &&
            (request.conversationId == null || (request.conversationId.isNotBlank() && request.conversationId.length <= 128))) { "读取请求缺少有效身份" }
        val epoch = readEpoch()
        val generation = readGeneration()
        require(epoch == request.restoreEpoch && generation == request.restoreGeneration) { "恢复后旧读取请求已失效" }
        grants.removeAll { it.request.restoreEpoch != epoch || it.request.restoreGeneration != generation }
        leases.entries.removeAll { it.value.expiresAt <= clock() || it.value.request.restoreEpoch != epoch || it.value.request.restoreGeneration != generation }
        val stored = readRule(request.module)
        val rule = stored.takeIf { it.epoch == epoch } ?: ModuleReadRule(revision = stored.revision)
        grants.removeAll { it.request.module == request.module && it.baseline != stored }
        val grant = grants.lastOrNull { it.request.module == request.module && it.request.actorId == request.actorId &&
            if (it.duration == ModuleGrantDuration.ONCE) it.request.requestId == request.requestId
            else it.request.conversationId == request.conversationId && request.conversationId != null }
        when (grant?.mode ?: if (request.module in deniedUntilSaved) ModuleReadMode.DENY else rule.mode) {
            ModuleReadMode.ASK -> {
                mutablePending.value = mutablePending.value + (request.module to request)
                throw ModuleConsentRequired()
            }
            ModuleReadMode.DENY -> error("此模块已禁止 AI 读取")
            ModuleReadMode.ALLOW -> Unit
        }
        require(leases.size < 32) { "读取请求过多，请稍后重试" }
        if (grant?.duration == ModuleGrantDuration.ONCE) grants.remove(grant)
        val permit = ModuleReadPermit(UUID.randomUUID().toString())
        leases[permit.id] = Lease(permit, request, stored, Math.addExact(clock(), 300_000))
        require(readEpoch() == epoch && readGeneration() == generation && ready()) { "恢复后读取许可已失效" }
        permit
    }

    suspend fun validate(permit: ModuleReadPermit) = mutex.withLock {
        val lease = requireNotNull(leases[permit.id]) { "模块授权已撤销" }
        require(readRule(lease.request.module) == lease.rule) { "模块授权已改变" }
        // Read persistence first. The final epoch read can suspend; generation/readiness are
        // checked synchronously after it, before handing the permit to the caller.
        require(lease.permit === permit && lease.expiresAt > clock() && readEpoch() == lease.request.restoreEpoch &&
            readGeneration() == lease.request.restoreGeneration && ready()) { "模块授权已失效" }
    }

    suspend fun finish(permit: ModuleReadPermit) = mutex.withLock { leases.remove(permit.id); Unit }

    suspend fun endConversation(id: String) = mutex.withLock {
        grants.removeAll { it.request.conversationId == id }
        leases.entries.removeAll { it.value.request.conversationId == id }
        mutablePending.value = mutablePending.value.filterValues { it.conversationId != id }
    }
}
