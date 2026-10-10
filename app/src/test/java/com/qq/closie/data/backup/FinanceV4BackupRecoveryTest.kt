package com.qq.closie.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.finance.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceV4BackupRecoveryTest : FinanceRoomTest() {
    @Test fun jsonArchiveRestoresActiveAndHistoricalLinksExpectedRulesProposalsAndStagingExactly() = runTest {
        val a = account()
        val paid = payment(a.id)
        finance.saveEntry(paid.id, a.id, paid.direction, 25_000, "合成纠正", paid.occurredAt,
            category = "衣服", tags = listOf("合成标签"), event = FinanceEventInput(), expectedRevision = paid.revision)
        val eventId = eventId(paid.id)
        finance.saveExpectedFlows(eventId, listOf(
            FinanceExpectedFlowInput(role = FinanceFlowRole.REFUND, amountMinor = 5_000, note = "原预计")),
            db.financeIntakeDao().event(eventId)!!.updatedAt)
        finance.saveExpectedFlows(eventId, listOf(
            FinanceExpectedFlowInput(role = FinanceFlowRole.REFUND, note = "未知金额"),
            FinanceExpectedFlowInput(role = FinanceFlowRole.SETTLEMENT, amountMinor = 1_000, note = "另一预计")),
            db.financeIntakeDao().event(eventId)!!.updatedAt)
        val automation = FinanceAutomationRepository(db, finance)
        automation.saveRule(FinanceRuleSet(FinanceRuleEntity("rule", "合成规则", 1, true, 1_000),
            listOf(FinanceRuleConditionEntity("rule", 0, FinanceConditionKind.SOURCE, FinanceSource.WECHAT.name)),
            listOf(FinanceRuleActionEntity("rule", 0, FinanceActionKind.ADD_TAG, "合成建议标签"))))
        val proposal = FinanceNotificationParser.parse("com.tencent.mm", "synthetic-backup-key", "微信支付",
            "支付成功 ￥1.00", System.currentTimeMillis())!!
        val found = automation.discover(proposal)!!
        automation.dismiss(found.id, found.revision, FinanceProposalStatus.SNOOZED)
        val intake = db.financeIntakeDao()
        intake.insertBatch(FinanceImportBatchEntity("batch", "synthetic-digest", "synthetic.csv", 1_000))
        intake.insertRows(LegacyLedgerReader.stage(LegacyLedgerTable("synthetic",
            listOf(LegacyLedgerReader.headers, SyntheticLegacyLedger.row())), "batch"))
        val saved = LifeBackupApplier.snapshot(db)
        val archived = BackupValidator.gson.fromJson(BackupValidator.gson.toJson(saved), LifeBackupPayload::class.java)
        assertEquals(saved, archived)
        val context: Context = ApplicationProvider.getApplicationContext()
        val target = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java).allowMainThreadQueries().build()
        try {
            LifeBackupApplier.restoreSnapshot(target, archived)
            val restored = readFinanceSnapshot(target, true)
            assertEquals(saved.finance, restored)
            assertEquals(2, restored.v4!!.links.size)
            assertEquals(1, restored.v4!!.activeLinks.size)
            assertEquals(1, restored.v4!!.links.count { it.voidedAt != null })
            assertEquals(2, restored.v4!!.expected.count { it.cancelledAt == null })
            assertEquals(1, restored.v4!!.expected.count { it.cancelledAt != null })
            assertEquals(FinanceProposalStatus.SNOOZED, restored.v4!!.proposals.single().status)
            assertEquals(1, restored.v4!!.rules.size)
            assertEquals(1, restored.v4!!.conditions.size)
            assertEquals(1, restored.v4!!.actions.size)
            assertEquals(1, restored.v4!!.proposalTags.size)
            assertEquals(1, restored.v4!!.staging.size)
            FinanceIntegrity.validate(restored)
            LifeBackupApplier.restoreSnapshot(target, archived)
            assertEquals(restored, readFinanceSnapshot(target, true))
        } finally { target.close() }
    }

    @Test fun restoringOlderSnapshotRetiresNewRelationsAndRetainsBothHistoricalIdentities() = runTest {
        val a = account()
        val paid = payment(a.id)
        val before = LifeBackupApplier.snapshot(db)
        finance.saveEntry(paid.id, a.id, paid.direction, paid.amountMinor, "后来编辑", paid.occurredAt,
            event = FinanceEventInput(), expectedRevision = paid.revision)
        val laterLink = db.financeIntakeDao().linksForEntry(paid.id).single()
        LifeBackupApplier.restoreSnapshot(db, before)
        val after = readFinanceSnapshot(db, true)
        assertEquals(before.finance!!.v4!!.links.single(), after.v4!!.activeLinks.single())
        assertNotNull(after.v4!!.links.single { it.id == laterLink.id }.voidedAt)
        assertEquals(2, after.v4!!.links.size)
        FinanceIntegrity.validate(after)
    }

    @Test fun preV4FinanceRestoreRetiresRelationsWithoutErasingTheirHistoryOrPendingIntake() = runTest {
        val a = account()
        payment(a.id)
        val backup = LifeBackupApplier.snapshot(db)
        val old = backup.copy(finance = backup.finance!!.copy(v4 = null))
        val proposal = FinanceNotificationParser.parse("com.tencent.mm", "legacy-key", "微信支付",
            "支付成功 1元", System.currentTimeMillis())!!
        FinanceAutomationRepository(db, finance).discover(proposal)
        LifeBackupApplier.restoreSnapshot(db, old)
        val after = readFinanceSnapshot(db, true)
        assertEquals(1, after.v4!!.links.size)
        assertTrue(after.v4!!.activeLinks.isEmpty())
        assertTrue(after.v4!!.events.all { it.voidedAt != null })
        assertEquals(1, after.v4!!.proposals.size)
        FinanceIntegrity.validate(after)
    }
}
