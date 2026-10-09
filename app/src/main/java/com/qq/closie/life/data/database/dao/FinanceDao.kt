package com.qq.closie.life.data.database.dao

import androidx.room.*
import com.qq.closie.life.finance.*
import kotlinx.coroutines.flow.Flow

/** Storage only. No canonical physical delete or REPLACE (which implicitly deletes) API. */
@Dao
interface FinanceDao {
    // One invalidation signal for all owned tables; the repository reads one coherent transaction.
    @Query("SELECT (SELECT COUNT(*) FROM finance_accounts) + (SELECT COUNT(*) FROM finance_entries) + " +
        "(SELECT COUNT(*) FROM finance_transfers) + (SELECT COUNT(*) FROM finance_categories) + " +
        "(SELECT COUNT(*) FROM finance_tags) + (SELECT COUNT(*) FROM finance_entry_tags)")
    fun observeChanges(): Flow<Long>
    @Query("SELECT * FROM finance_accounts ORDER BY createdAt, id") fun observeAccounts(): Flow<List<FinanceAccountEntity>>
    @Query("SELECT * FROM finance_accounts ORDER BY createdAt, id") suspend fun accounts(): List<FinanceAccountEntity>
    @Query("SELECT * FROM finance_entries ORDER BY occurredAt DESC, recordedAt DESC, id") fun observeEntries(): Flow<List<FinanceEntryEntity>>
    @Query("SELECT * FROM finance_entries") suspend fun entries(): List<FinanceEntryEntity>
    @Query("SELECT * FROM finance_transfers") fun observeTransfers(): Flow<List<FinanceTransferEntity>>
    @Query("SELECT * FROM finance_transfers") suspend fun transfers(): List<FinanceTransferEntity>
    @Query("SELECT * FROM finance_categories ORDER BY name") fun observeCategories(): Flow<List<FinanceCategoryEntity>>
    @Query("SELECT * FROM finance_categories") suspend fun categories(): List<FinanceCategoryEntity>
    @Query("SELECT * FROM finance_tags ORDER BY name") fun observeTags(): Flow<List<FinanceTagEntity>>
    @Query("SELECT * FROM finance_tags") suspend fun tags(): List<FinanceTagEntity>
    @Query("SELECT * FROM finance_entry_tags") fun observeEntryTags(): Flow<List<FinanceEntryTagCrossRef>>
    @Query("SELECT * FROM finance_entry_tags") suspend fun entryTags(): List<FinanceEntryTagCrossRef>
    @Query("SELECT * FROM finance_accounts WHERE id = :id") suspend fun account(id: String): FinanceAccountEntity?
    @Query("SELECT * FROM finance_entries WHERE id = :id") suspend fun entry(id: String): FinanceEntryEntity?
    @Query("SELECT * FROM finance_transfers WHERE id = :id") suspend fun transfer(id: String): FinanceTransferEntity?
    @Query("SELECT * FROM finance_transfers WHERE outflowEntryId = :id OR inflowEntryId = :id") suspend fun transferForEntry(id: String): FinanceTransferEntity?
    @Query("SELECT * FROM finance_categories WHERE name = :name ORDER BY id LIMIT 1") suspend fun categoryNamed(name: String): FinanceCategoryEntity?
    @Query("SELECT * FROM finance_tags WHERE name = :name ORDER BY id LIMIT 1") suspend fun tagNamed(name: String): FinanceTagEntity?
    @Query("SELECT COUNT(*) FROM finance_entries WHERE accountId = :id") suspend fun accountEntryCount(id: String): Int
    @Insert suspend fun insertAccount(row: FinanceAccountEntity)
    @Update suspend fun updateAccount(row: FinanceAccountEntity)
    @Insert suspend fun insertEntry(row: FinanceEntryEntity)
    @Update suspend fun updateEntry(row: FinanceEntryEntity)
    @Insert suspend fun insertTransfer(row: FinanceTransferEntity)
    @Update suspend fun updateTransfer(row: FinanceTransferEntity)
    @Insert suspend fun insertCategory(row: FinanceCategoryEntity)
    @Update suspend fun updateCategory(row: FinanceCategoryEntity)
    @Insert suspend fun insertTag(row: FinanceTagEntity)
    @Update suspend fun updateTag(row: FinanceTagEntity)
    @Insert suspend fun insertEntryTags(rows: List<FinanceEntryTagCrossRef>)
    // Removing a descriptive association is not deleting a canonical entry/tag.
    @Query("DELETE FROM finance_entry_tags WHERE entryId = :id") suspend fun clearEntryTags(id: String)
}
