package com.qq.closie.life.data.database.dao

import androidx.room.*
import com.qq.closie.life.finance.*
import kotlinx.coroutines.flow.Flow

/** Internal storage for typed Finance semantics and non-canonical intake. */
@Dao
interface FinanceIntakeDao {
    @Query("SELECT * FROM finance_events WHERE id = :id") suspend fun event(id: String): FinanceEventEntity?
    @Query("SELECT * FROM finance_event_entries WHERE entryId = :id AND voidedAt IS NULL") suspend fun linksForEntry(id: String): List<FinanceEventEntryLinkEntity>
    @Query("SELECT * FROM finance_events") suspend fun events(): List<FinanceEventEntity>
    @Query("SELECT * FROM finance_event_entries") suspend fun links(): List<FinanceEventEntryLinkEntity>
    @Query("SELECT * FROM finance_event_entries WHERE eventId = :id AND voidedAt IS NULL")
    suspend fun activeLinksForEvent(id: String): List<FinanceEventEntryLinkEntity>
    @Query("SELECT * FROM finance_expected_flows WHERE eventId = :id AND cancelledAt IS NULL")
    suspend fun activeExpectedForEvent(id: String): List<FinanceExpectedFlowEntity>
    @Query("SELECT * FROM finance_expected_flows") suspend fun expected(): List<FinanceExpectedFlowEntity>
    @Query("SELECT * FROM finance_import_batches ORDER BY createdAt DESC") suspend fun batches(): List<FinanceImportBatchEntity>
    @Query("SELECT * FROM finance_import_rows") suspend fun staging(): List<FinanceImportRowEntity>
    @Query("SELECT * FROM finance_import_rows WHERE batchId = :id ORDER BY rowNumber") suspend fun rows(id: String): List<FinanceImportRowEntity>
    @Query("SELECT * FROM finance_proposals ORDER BY createdAt DESC") suspend fun proposals(): List<FinanceProposalEntity>
    @Query("SELECT * FROM finance_proposals WHERE id = :id") suspend fun proposal(id: String): FinanceProposalEntity?
    @Query("SELECT * FROM finance_proposals WHERE sourceKey = :key") suspend fun proposalByKey(key: String): FinanceProposalEntity?
    @Query("SELECT * FROM finance_proposal_tags") suspend fun proposalTags(): List<FinanceProposalTagEntity>
    @Query("SELECT * FROM finance_rules ORDER BY priority, id") suspend fun rules(): List<FinanceRuleEntity>
    @Query("SELECT * FROM finance_rule_conditions ORDER BY ruleId, position") suspend fun conditions(): List<FinanceRuleConditionEntity>
    @Query("SELECT * FROM finance_rule_actions ORDER BY ruleId, position") suspend fun actions(): List<FinanceRuleActionEntity>
    @Query("SELECT * FROM finance_changes") suspend fun changes(): List<FinanceChangeEntity>
    @Query("SELECT (SELECT COUNT(*) FROM finance_proposals) + (SELECT COUNT(*) FROM finance_proposal_tags) + " +
        "(SELECT COUNT(*) FROM finance_import_batches) + (SELECT COUNT(*) FROM finance_import_rows) + " +
        "(SELECT COUNT(*) FROM finance_rules) + (SELECT COUNT(*) FROM finance_rule_conditions) + (SELECT COUNT(*) FROM finance_rule_actions)")
    fun observeChanges(): Flow<Long>
    @Insert suspend fun insertEvent(row: FinanceEventEntity)
    @Update suspend fun updateEvent(row: FinanceEventEntity)
    @Insert suspend fun insertLinks(rows: List<FinanceEventEntryLinkEntity>)
    @Update suspend fun updateLink(row: FinanceEventEntryLinkEntity)
    @Query("SELECT * FROM finance_event_entries WHERE voidedAt IS NULL") suspend fun activeLinks(): List<FinanceEventEntryLinkEntity>
    @Insert suspend fun insertExpected(row: FinanceExpectedFlowEntity)
    @Update suspend fun updateExpected(row: FinanceExpectedFlowEntity)
    @Insert suspend fun insertBatch(row: FinanceImportBatchEntity)
    @Update suspend fun updateBatch(row: FinanceImportBatchEntity)
    @Insert suspend fun insertRows(rows: List<FinanceImportRowEntity>)
    @Update suspend fun updateRow(row: FinanceImportRowEntity)
    @Insert suspend fun insertProposal(row: FinanceProposalEntity)
    @Update suspend fun updateProposal(row: FinanceProposalEntity)
    @Insert suspend fun insertProposalTags(rows: List<FinanceProposalTagEntity>)
    @Query("DELETE FROM finance_proposal_tags WHERE proposalId = :id") suspend fun clearProposalTags(id: String)
    @Insert suspend fun insertRule(row: FinanceRuleEntity)
    @Update suspend fun updateRule(row: FinanceRuleEntity)
    @Insert suspend fun insertConditions(rows: List<FinanceRuleConditionEntity>)
    @Insert suspend fun insertActions(rows: List<FinanceRuleActionEntity>)
    @Query("DELETE FROM finance_rule_conditions WHERE ruleId = :id") suspend fun clearConditions(id: String)
    @Query("DELETE FROM finance_rule_actions WHERE ruleId = :id") suspend fun clearActions(id: String)
    @Insert suspend fun insertChange(row: FinanceChangeEntity)
}
