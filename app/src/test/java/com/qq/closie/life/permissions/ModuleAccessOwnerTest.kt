package com.qq.closie.life.permissions

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ModuleAccessOwnerTest {
    private val rules = mutableMapOf<ReadModule, ModuleReadRule>()
    private var epoch = 7L
    private var now = 1_000L
    private var generation = 0L
    private fun owner() = ModuleAccessOwner({ rules[it] ?: ModuleReadRule() }, { module, rule -> rules[module] = rule }, { epoch }, { generation }) { now }
    private fun request(id: String = "request", actor: String = "local-provider", conversation: String? = null) =
        ModuleReadRequest(ReadModule.FINANCE, actor, id, epoch, conversation, generation)

    @Test fun aOneShotIsBoundToTheActualActorAndRequestAndConsumedBeforeDisclosure() = runTest {
        val access = owner(); val original = request()
        assertTrue(runCatching { access.reserve(original) }.exceptionOrNull() is ModuleConsentRequired)
        access.authorize(ReadModule.FINANCE, ModuleReadMode.ALLOW, ModuleGrantDuration.ONCE, epoch, original)
        assertTrue(runCatching { access.reserve(request("different")) }.isFailure)
        assertTrue(runCatching { access.reserve(request(actor = "different-provider")) }.isFailure)
        val permit = access.reserve(original)
        access.validate(permit)
        // A concurrent/repeated invocation cannot borrow the in-flight permit.
        assertTrue(runCatching { access.reserve(original) }.exceptionOrNull() is ModuleConsentRequired)
        access.validate(permit)
        access.finish(permit)
        assertTrue(runCatching { access.validate(permit) }.isFailure)
    }

    @Test fun aConversationGrantCannotEscapeItsConversationAndEndsWithItsSession() = runTest {
        val access = owner(); val first = request(conversation = "conversation-1")
        assertTrue(runCatching { access.reserve(first) }.isFailure)
        access.authorize(ReadModule.FINANCE, ModuleReadMode.ALLOW, ModuleGrantDuration.CONVERSATION, epoch, first)
        val permit = access.reserve(request("next", conversation = "conversation-1"))
        assertTrue(runCatching { access.reserve(request("other", conversation = "conversation-2")) }.isFailure)
        access.endConversation("conversation-1")
        assertTrue(runCatching { access.validate(permit) }.isFailure)
        assertTrue(runCatching { access.reserve(first) }.isFailure)
        assertTrue(runCatching { owner().reserve(first) }.isFailure)
    }

    @Test fun revocationRestoreAndAStaleSettingsEditorCannotRestoreAccess() = runTest {
        val access = owner()
        access.authorize(ReadModule.FINANCE, ModuleReadMode.ALLOW, ModuleGrantDuration.LONG_TERM, epoch, expectedRevision = 0)
        val permit = access.reserve(request())
        val allowedRevision = rules.getValue(ReadModule.FINANCE).revision
        access.authorize(ReadModule.FINANCE, ModuleReadMode.DENY, ModuleGrantDuration.LONG_TERM, epoch, expectedRevision = allowedRevision)
        assertTrue(runCatching { access.validate(permit) }.isFailure)
        assertTrue(runCatching { access.reserve(request()) }.isFailure)
        assertTrue(runCatching { access.authorize(ReadModule.FINANCE, ModuleReadMode.ALLOW,
            ModuleGrantDuration.LONG_TERM, epoch, expectedRevision = allowedRevision) }.isFailure)
        assertEquals(ModuleReadMode.DENY, rules.getValue(ReadModule.FINANCE).mode)
        access.authorize(ReadModule.FINANCE, ModuleReadMode.ALLOW, ModuleGrantDuration.LONG_TERM, epoch)
        val old = request(); epoch++
        assertTrue(runCatching { access.reserve(old) }.isFailure)
        assertTrue(runCatching { access.reserve(request()) }.exceptionOrNull() is ModuleConsentRequired)
    }

    @Test fun temporaryAuthorizationRequiresAPendingRequestAndPermitsExpire() = runTest {
        val access = owner(); val current = request()
        assertTrue(runCatching { access.authorize(ReadModule.FINANCE, ModuleReadMode.ALLOW, ModuleGrantDuration.ONCE, epoch, current) }.isFailure)
        assertTrue(runCatching { access.reserve(current) }.isFailure)
        assertTrue(runCatching { access.authorize(ReadModule.FINANCE, ModuleReadMode.ALLOW, ModuleGrantDuration.CONVERSATION, epoch, current) }.isFailure)
        access.authorize(ReadModule.FINANCE, ModuleReadMode.ALLOW, ModuleGrantDuration.ONCE, epoch, current)
        val permit = access.reserve(current)
        now += 300_001
        assertTrue(runCatching { access.validate(permit) }.isFailure)
        assertTrue(runCatching { access.reserve(current) }.isFailure)
    }
    @Test fun evenARestoreWithoutChangingFinanceFactsInvalidatesAQueuedOrInFlightRequest() = runTest {
        val access = owner()
        access.authorize(ReadModule.FINANCE, ModuleReadMode.ALLOW, ModuleGrantDuration.LONG_TERM, epoch)
        val old = request(); val permit = access.reserve(old)
        generation++
        assertTrue(runCatching { access.validate(permit) }.isFailure)
        assertTrue(runCatching { access.reserve(old) }.isFailure)
        assertTrue(runCatching { access.authorize(ReadModule.FINANCE, ModuleReadMode.ALLOW,
            ModuleGrantDuration.LONG_TERM, epoch, expectedGeneration = 0) }.isFailure)
    }
    @Test fun restoringWhileThePermissionRuleIsBeingReadCannotApproveAnOldPermit() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var suspendRead = false
        val rule = ModuleReadRule(ModuleReadMode.ALLOW, epoch, 1)
        val access = ModuleAccessOwner({
            if (suspendRead) { entered.complete(Unit); release.await() }
            rule
        }, { _, _ -> }, { epoch }, { generation }) { now }
        val permit = access.reserve(request())
        suspendRead = true
        val result = async { runCatching { access.validate(permit) } }
        entered.await(); epoch++; generation++; release.complete(Unit)
        assertTrue(result.await().isFailure)
    }
    @Test fun aCommittedButCancelledDenyCannotLeaveAnOldConversationGrantUsable() = runTest {
        var stored = ModuleReadRule()
        val access = ModuleAccessOwner({ stored }, { _, value -> stored = value; throw CancellationException("after commit") }, { epoch }) { now }
        val context = request(conversation = "conversation")
        assertTrue(runCatching { access.reserve(context) }.isFailure)
        access.authorize(ReadModule.FINANCE, ModuleReadMode.ALLOW, ModuleGrantDuration.CONVERSATION, epoch, context)
        val permit = access.reserve(context)
        assertTrue(runCatching { access.authorize(ReadModule.FINANCE, ModuleReadMode.DENY, ModuleGrantDuration.LONG_TERM, epoch) }.isFailure)
        assertEquals(ModuleReadMode.DENY, stored.mode)
        assertTrue(runCatching { access.validate(permit) }.isFailure)
        assertTrue(runCatching { access.reserve(context.copy(requestId = "next")) }.isFailure)
    }
    @Test fun aTemporaryGrantCannotCrossAnExternalPolicyRevision() = runTest {
        val access = owner(); val context = request(conversation = "conversation")
        assertTrue(runCatching { access.reserve(context) }.isFailure)
        access.authorize(ReadModule.FINANCE, ModuleReadMode.ALLOW, ModuleGrantDuration.CONVERSATION, epoch, context)
        rules[ReadModule.FINANCE] = ModuleReadRule(ModuleReadMode.DENY, epoch, 9)
        assertTrue(runCatching { access.reserve(context) }.isFailure)
    }
}
