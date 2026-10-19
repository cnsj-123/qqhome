// Build-time schema source only. Runtime business facts live in typed Room entities.
// Run after changing this file, then let KSP export the authoritative Room schema in CI.
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const tables = [];
const field = (name, type = 'String', value) => ({ name, type, value });
const id = () => field('id');
const time = () => [field('createdAt', 'Long'), field('updatedAt', 'Long'), field('revision', 'Long', '1'), field('voidedAt', 'Long?', 'null')];
const table = (name, cls, fields, refs = {}, primary = ['id'], indices = []) => {
  const t = { name, cls: (name.startsWith('shopping_') ? 'Shopping' : 'Finance') + cls + 'Entity', fields, refs, primary, indices };
  tables.push(t); return t;
};
const fk = (entity, column = 'id') => ({ entity, column });
const event = fk('FinanceEventEntity'), entry = fk('FinanceEntryEntity'), account = fk('FinanceAccountEntity');
const party = fk('FinancePartyEntity'), category = fk('FinanceCategoryEntity');
table('finance_parties', 'Party', [id(), field('name'), field('isSelf', 'Boolean', 'false'), ...time()]);
table('finance_economics', 'Economic', [field('eventId'), field('valueMinor', 'Long?', 'null'),
  field('valueBasis'), field('categoryId', 'String?', 'null'), field('allocationsComplete', 'Boolean', 'false'),
  field('statPolicy', 'FinanceStatPolicy', 'FinanceStatPolicy.INCLUDE'),
  field('budgetPolicy', 'FinanceBudgetPolicy', 'FinanceBudgetPolicy.INCLUDE'), ...time()], {eventId:event, categoryId:category}, ['eventId']);
table('finance_funding', 'Funding', [id(), field('eventId'), field('partyId'), field('amountMinor', 'Long'),
  field('entryLinkId', 'String?', 'null'), field('basis'), ...time()],
  {eventId:event, partyId:party, entryLinkId:fk('FinanceEventEntryLinkEntity')});
table('finance_shares', 'Share', [id(), field('eventId'), field('amountMinor', 'Long?', 'null'),
  field('beneficiaryId'), field('bearerId', 'String?', 'null'), field('fundingId', 'String?', 'null'),
  field('relation', 'FinanceShareRelation'), ...time()],
  {eventId:event, beneficiaryId:party, bearerId:party, fundingId:fk('FinanceFundingEntity')});
table('finance_obligations', 'Obligation', [id(), field('eventId'), field('shareId', 'String?', 'null'),
  field('creditorId'), field('debtorId'), field('amountMinor', 'Long'), field('currencyCode'), ...time()],
  {eventId:event, shareId:fk('FinanceShareEntity'), creditorId:party, debtorId:party});
table('finance_settlements', 'Settlement', [id(), field('obligationId'), field('entryId'), field('amountMinor', 'Long'), ...time()],
  {obligationId:fk('FinanceObligationEntity'), entryId:entry});
table('finance_resolutions', 'Resolution', [id(), field('obligationId'), field('kind', 'FinanceResolutionKind'),
  field('amountMinor', 'Long'), field('note'), ...time()], {obligationId:fk('FinanceObligationEntity')});
table('shopping_purchases', 'Purchase', [id(), field('title'), field('currencyCode'), field('occurredAt', 'Long'), ...time()]);
table('shopping_purchase_lines', 'PurchaseLine', [id(), field('purchaseId'), field('title'), field('quantity', 'Int'),
  field('valueMinor', 'Long?', 'null'), ...time()], {purchaseId:fk('ShoppingPurchaseEntity')});
table('finance_purchase_allocations', 'PurchaseAllocation', [id(), field('lineId'), field('eventId'),
  field('valueMinor', 'Long'), field('personalMinor', 'Long?', 'null'), ...time()],
  {lineId:fk('ShoppingPurchaseLineEntity'), eventId:event});
table('finance_cost_adjustments', 'CostAdjustment', [id(), field('eventId'), field('entryLinkId'),
  field('personalReductionMinor', 'Long'), field('lineId', 'String?', 'null'), ...time()],
  {eventId:event, entryLinkId:fk('FinanceEventEntryLinkEntity'), lineId:fk('ShoppingPurchaseLineEntity')});
table('finance_entry_timing', 'EntryTiming', [field('entryId'), field('postedAt', 'Long?', 'null'),
  field('cashPrecision', 'FinanceTimePrecision', 'FinanceTimePrecision.LEGACY'),
  field('postedPrecision', 'FinanceTimePrecision', 'FinanceTimePrecision.UNKNOWN'),
  field('postedSource'), ...time()], {entryId:entry}, ['entryId']);
table('finance_account_policies', 'AccountPolicy', [field('accountId'), field('includedInNet', 'Boolean', 'true'),
  field('liabilityConvention', 'FinanceLiabilityConvention', 'FinanceLiabilityConvention.SIGNED'),
  field('anchorBasis','FinanceBalanceBasis','FinanceBalanceBasis.LEGACY_CASH'), ...time()],
  {accountId:account}, ['accountId']);
table('finance_observations', 'Observation', [id(), field('accountId'), field('amountMinor', 'Long'),
  field('observedAt', 'Long'), field('basis', 'FinanceBalanceBasis'), field('precision', 'FinanceTimePrecision'),
  field('note'), ...time()], {accountId:account});
table('finance_reconciliations', 'Reconciliation', [id(), field('observationId'), field('bookMinor', 'Long?', 'null'),
  field('fingerprint'), field('algorithmVersion', 'Int', '1'), field('note'), field('confirmationOrder','Long','1'), ...time()],
  {observationId:fk('FinanceObservationEntity')});
table('finance_credit_cycles', 'CreditCycle', [field('accountId'), field('closingDay', 'Int'), field('dueDay', 'Int'),
  field('zoneId'), ...time()], {accountId:account}, ['accountId']);
table('finance_drafts', 'Draft', [id(), field('accountId', 'String?', 'null'),
  field('kind','FinanceDraftKind','FinanceDraftKind.EXPENSE'), field('targetAccountId','String?','null'),
  field('amountExpression'), field('description'), field('occurredAt', 'Long'),field('occurredText'), field('categoryId', 'String?', 'null'),
  field('categoryName','String','""'), field('subcategoryName','String','""'),
  field('currencyCode'), field('continuous', 'Boolean', 'false'), field('epoch', 'Long'),field('editGeneration','Long','0'),
  field('nature','FinanceNature','FinanceNature.PERSONAL'), field('role','FinanceFlowRole','FinanceFlowRole.PAYMENT'),
  field('eventId','String?','null'), field('shareText','String','""'), field('excluded','Boolean','false'),
  field('excludedBudget','Boolean','false'), field('expectedEdited','Boolean','false'),
  field('eventUpdatedAt','Long?','null'), ...time()]);
table('finance_draft_tags', 'DraftTag', [field('draftId'), field('name')],
  {draftId:fk('FinanceDraftEntity')}, ['draftId','name']);
table('finance_draft_expected', 'DraftExpected', [field('draftId'), field('position','Int'),field('expectedId','String?','null'),
  field('role','FinanceFlowRole'),field('amountText'),field('note'),field('cancelled','Boolean')],
  {draftId:fk('FinanceDraftEntity')}, ['draftId','position']);

table('finance_economic_drafts','EconomicDraft',[id(),field('eventId','String?','null'),field('expectedRevision','Long?','null'),
  field('eventVersion','Long?','null'),field('epoch','Long'),field('description'),field('currencyCode'),field('occurredText'),
  field('preservedOccurredAt','Long?','null'),field('valueText'),field('valueBasis'),field('categoryId','String?','null'),
  field('allocationsComplete','Boolean'),field('statExcluded','Boolean'),field('budgetExcluded','Boolean'),...time()],{eventId:event,categoryId:category});
table('finance_economic_draft_funding','EconomicDraftFunding',[field('draftId'),field('localId'),field('position','Int'),
  field('partySelection'),field('amountText'),field('entryLinkSelection','String?','null'),field('basis')],
  {draftId:fk('FinanceEconomicDraftEntity')},['draftId','localId']);
table('finance_economic_draft_shares','EconomicDraftShare',[field('draftId'),field('localId'),field('position','Int'),
  field('amountText'),field('beneficiarySelection'),field('bearerSelection'),field('fundingLocalId','String?','null'),field('relation','FinanceShareRelation')],
  {draftId:fk('FinanceEconomicDraftEntity')},['draftId','localId']);
table('finance_category_icons', 'CategoryIcon', [field('categoryId'),field('iconKey'),...time()],{categoryId:category},['categoryId']);
table('finance_saved_queries', 'SavedQuery', [id(), field('name'), field('specVersion', 'Int', '1'),
  field('specJson'), ...time()]);
table('finance_budgets', 'Budget', [id(), field('name'), field('currencyCode'), field('limitMinor', 'Long'),
  field('categoryId', 'String?', 'null'), field('startAt', 'Long'), field('endExclusive', 'Long'), ...time()],
  {categoryId:category});
table('finance_category_policies', 'CategoryPolicy', [field('categoryId'), field('burden', 'FinanceBurden'), ...time()],
  {categoryId:category}, ['categoryId']);
table('finance_external_identities', 'ExternalIdentity', [id(), field('sourceFamily'), field('label'),
  field('digestNamespace'), field('localDigest', 'String?', 'null'), field('matchUsable', 'Boolean', 'true'), ...time()]);
table('finance_instruments', 'Instrument', [id(), field('identityId'), field('label'), field('kind'), ...time()],
  {identityId:fk('FinanceExternalIdentityEntity')});
table('finance_account_bindings', 'AccountBinding', [id(), field('instrumentId'), field('accountId'),
  field('validFrom', 'Long'), field('validUntil', 'Long?', 'null'), ...time()],
  {instrumentId:fk('FinanceInstrumentEntity'), accountId:account});
table('finance_evidence', 'Evidence', [id(), field('provider'), field('sourceFamily'), field('identityId', 'String?', 'null'),
  field('instrumentId', 'String?', 'null'), field('referenceKind', 'FinanceReferenceKind'),
  field('referenceDigest', 'String?', 'null'), field('digestNamespace'), field('capturedAt', 'Long'), field('summary'),
  field('identityRevision','Long?','null'),field('bindingId','String?','null'),field('bindingRevision','Long?','null'),
  field('relatedOrderDigest','String?','null'),...time()],
  {identityId:fk('FinanceExternalIdentityEntity'), instrumentId:fk('FinanceInstrumentEntity'),bindingId:fk('FinanceAccountBindingEntity')});
table('finance_extractions', 'Extraction', [id(), field('evidenceId'), field('extractorVersion', 'Int'),
  field('amountMinor', 'Long?', 'null'), field('currencyCode', 'String?', 'null'),
  field('occurredAt', 'Long?', 'null'), field('direction', 'FinanceDirection?', 'null'),
  field('issue'), ...time()], {evidenceId:fk('FinanceEvidenceEntity')});
table('finance_evidence_proposals', 'EvidenceProposal', [field('evidenceId'), field('proposalId')],
  {evidenceId:fk('FinanceEvidenceEntity'), proposalId:fk('FinanceProposalEntity')}, ['evidenceId','proposalId']);
// Media identity is a soft cross-domain reference: an older restore may remove the asset.
// The typed financial target survives and reports missing source; no fake LifeEntity is created.
table('finance_media_links','MediaLink',[id(),field('mediaAssetId'),field('eventId','String?','null'),
  field('purchaseId','String?','null'),field('proposalId','String?','null'),field('sourceHash'),field('label'),...time()],
  {eventId:event,purchaseId:fk('ShoppingPurchaseEntity'),proposalId:fk('FinanceProposalEntity')},['id'],['mediaAssetId']);
table('finance_review_sessions', 'ReviewSession', [field('proposalId'), field('baseRevision', 'Long'),
  field('epoch', 'Long'), field('amountText'), field('accountId', 'String?', 'null'),
  field('category'), field('subcategory'), field('description'), field('state', 'FinanceReviewState'),
  field('kind','FinanceDraftKind','FinanceDraftKind.EXPENSE'),field('targetAccountId','String?','null'),
  field('occurredAt','Long','0'),field('occurredText','String','""'),field('categoryId','String?','null'),field('tagsText','String','""'),
  field('nature','FinanceNature','FinanceNature.PERSONAL'),field('role','FinanceFlowRole','FinanceFlowRole.PAYMENT'),
  field('eventId','String?','null'),field('eventUpdatedAt','Long?','null'),field('shareText','String','""'),
  field('excluded','Boolean','false'),field('excludedBudget','Boolean','false'),field('expectedEdited','Boolean','false'),
  field('costReductionText','String','""'), ...time()],
  {proposalId:fk('FinanceProposalEntity')}, ['proposalId']);
table('finance_review_expected','ReviewExpected',[field('proposalId'),field('position','Int'),field('expectedId','String?','null'),
  field('role','FinanceFlowRole'),field('amountText'),field('note'),field('cancelled','Boolean')],
  {proposalId:fk('FinanceReviewSessionEntity','proposalId')},['proposalId','position']);
table('finance_purpose_grants', 'PurposeGrant', [id(), field('purpose', 'FinanceReadPurpose'),
  field('providerId'), field('allowAmount', 'Boolean'), field('allowDate', 'Boolean'),
  field('allowDescription', 'Boolean'), field('expiresAt', 'Long'), ...time()]);
table('finance_grant_events', 'GrantEvent', [field('grantId'), field('eventId')],
  {grantId:fk('FinancePurposeGrantEntity'), eventId:event}, ['grantId','eventId']);
table('finance_grant_entries', 'GrantEntry', [field('grantId'), field('entryId')],
  {grantId:fk('FinancePurposeGrantEntity'), entryId:entry}, ['grantId','entryId']);
table('finance_command_receipts', 'CommandReceipt', [field('operationId'), field('command'), field('inputDigest'),
  field('resultId'), field('epoch', 'Long'), field('createdAt', 'Long')], {}, ['operationId']);
table('finance_control', 'Control', [field('id', 'Int'), field('restoreEpoch', 'Long')]);
// Recovery is a bounded typed preimage, never an editable ledger or a universal JSON history.
table('finance_recovery_groups', 'RecoveryGroup', [id(), field('kind'), field('epoch', 'Long'),
  field('createdAt', 'Long'), field('consumedAt', 'Long?', 'null'), field('afterFingerprint')]);
const recovery = fk('FinanceRecoveryGroupEntity');
const entryFields = [field('entryId'), field('accountId'), field('accountCurrencyCode'), field('direction', 'FinanceDirection'), field('amountMinor', 'Long'),
  field('description'), field('occurredAt', 'Long'), field('recordedAt', 'Long'), field('updatedAt', 'Long'),
  field('categoryId','String?','null'), field('voidedAt','Long?','null'), field('statPolicy','FinanceStatPolicy'),
  field('budgetPolicy','FinanceBudgetPolicy'), field('revision','Long'), field('importBatchId','String?','null')];
table('finance_recovery_entries', 'RecoveryEntry', [field('groupId'), ...entryFields], {groupId:recovery}, ['groupId','entryId']);
table('finance_recovery_links', 'RecoveryLink', [field('groupId'), field('linkId'), field('eventId'), field('entryId'),
  field('role','FinanceFlowRole'), field('allocatedMinor','Long'), ...time()], {groupId:recovery}, ['groupId','linkId']);
table('finance_recovery_events', 'RecoveryEvent', [field('groupId'), field('eventId'), field('updatedAt','Long'),
  field('voidedAt','Long?','null')], {groupId:recovery}, ['groupId','eventId']);
table('finance_recovery_expected', 'RecoveryExpected', [field('groupId'), field('expectedId'),
  field('cancelledAt','Long?','null')], {groupId:recovery}, ['groupId','expectedId']);
table('finance_recovery_transfers', 'RecoveryTransfer', [field('groupId'), field('transferId'),
  field('updatedAt','Long'), field('voidedAt','Long?','null')], {groupId:recovery}, ['groupId','transferId']);
table('finance_recovery_tags', 'RecoveryTag', [field('groupId'), field('entryId'), field('tagId')],
  {groupId:recovery}, ['groupId','entryId','tagId']);
table('finance_recovery_members', 'RecoveryMember', [field('groupId'), field('kind','FinanceRecoveryMemberKind'),
  field('memberId'), field('revision','Long'), field('updatedAt','Long'), field('voidedAt','Long?','null'),
  field('categoryId','String?','null')], {groupId:recovery}, ['groupId','kind','memberId']);
const enums = {
  FinanceShareRelation: ['PERSONAL_CONFIRMED','GIFT_CONFIRMED','REPAYABLE_CONFIRMED','UNRESOLVED'],
  FinanceResolutionKind: ['HISTORICAL_SETTLED','WAIVED'],
  FinanceTimePrecision: ['UNKNOWN','DATE','INSTANT','LEGACY'],
  FinanceLiabilityConvention: ['SIGNED','POSITIVE_DEBT'],
  FinanceBalanceBasis: ['LEGACY_CASH','KNOWN_POSTED'],
  FinanceBurden: ['FIXED','FLEXIBLE'],
  FinanceReferenceKind: ['NONE','ORDER','PAYMENT','REFUND'],
  FinanceReviewState: ['QUEUED','EDITING','LATER','COMPLETED'],
  FinanceDraftKind: ['EXPENSE','INCOME','TRANSFER'],
  FinanceReadPurpose: ['HOME','SHARE','LOCAL_QUERY','AI_READ','CLOUD_SEND'],
  FinanceRecoveryMemberKind: ['ECONOMIC','FUNDING','SHARE','OBLIGATION','SETTLEMENT','RESOLUTION','COST_ADJUSTMENT','PURCHASE_ALLOCATION']
};
const header = 'package com.qq.closie.life.finance\n\nimport androidx.room.*\nimport com.qq.closie.life.shopping.*\n\n';
let entities = header + Object.entries(enums).map(([k,v])=>'enum class '+k+' { '+v.join(', ')+' }').join('\n')+'\n\n';
let dao = 'package com.qq.closie.life.data.database.dao\n\nimport androidx.room.*\nimport com.qq.closie.life.finance.*\nimport com.qq.closie.life.shopping.*\nimport kotlinx.coroutines.flow.Flow\n\n@Dao\ninterface FinanceV16Dao {\n';
let snapshot = header + 'data class FinanceV16Snapshot(\n';
const sql = [];
const refType = t => t.replace('?','');
for (const t of tables) {
  const indices = [...new Set([...Object.keys(t.refs), ...t.indices])];
  entities += '@Entity(tableName = "'+t.name+'", primaryKeys = ['+t.primary.map(p=>'"'+p+'"').join(', ')+']';
  if(indices.length) entities += ', indices = ['+indices.map(i=>'Index("'+i+'")').join(', ')+']';
  if(Object.keys(t.refs).length) entities += ', foreignKeys = ['+Object.entries(t.refs).map(([c,r])=>
    'ForeignKey(entity = '+r.entity+'::class, parentColumns = ["'+r.column+'"], childColumns = ["'+c+'"])').join(', ')+']';
  entities += ')\ndata class '+t.cls+'(\n'+t.fields.map(f=>'    val '+f.name+': '+f.type+(f.value===undefined?'':' = '+f.value)).join(',\n')+'\n)\n\n';
  const prop = t.cls.replace(/^(Finance|Shopping)/,'').replace(/Entity$/,'');
  const plural = prop.charAt(0).toLowerCase()+prop.slice(1)+'Rows';
  t.property = plural;
  dao += '    @Query("SELECT * FROM '+t.name+'") suspend fun '+plural+'(): List<'+t.cls+'>\n'+
    '    @Upsert suspend fun put'+prop+'(rows: List<'+t.cls+'>)\n';
  snapshot += '    val '+plural+': List<'+t.cls+'> = emptyList(),\n';
  const columns = t.fields.map(f=>'\x60'+f.name+'\x60 '+(['Long','Int','Boolean'].includes(refType(f.type))?'INTEGER':'TEXT')+(f.type.endsWith('?')?'':' NOT NULL'));
  columns.push('PRIMARY KEY('+t.primary.map(p=>'\x60'+p+'\x60').join(', ')+')');
  columns.push(...Object.entries(t.refs).map(([c,r])=>'FOREIGN KEY(\x60'+c+'\x60) REFERENCES \x60'+
    (tables.find(other=>other.cls===r.entity)?.name || {'FinanceEventEntity':'finance_events','FinanceEntryEntity':'finance_entries',
      'FinanceAccountEntity':'finance_accounts','FinanceCategoryEntity':'finance_categories',
      'FinanceEventEntryLinkEntity':'finance_event_entries','FinanceProposalEntity':'finance_proposals'}[r.entity])+
    '\x60(\x60'+r.column+'\x60) ON UPDATE NO ACTION ON DELETE NO ACTION'));
  sql.push('CREATE TABLE IF NOT EXISTS \x60'+t.name+'\x60 ('+columns.join(', ')+')');
  for(const col of indices) sql.push('CREATE INDEX IF NOT EXISTS \x60index_'+t.name+'_'+col+'\x60 ON \x60'+t.name+'\x60 (\x60'+col+'\x60)');
}
dao += '    @Query("DELETE FROM finance_draft_tags") suspend fun clearDraftTagsForRestore()\n'+
 '    @Query("DELETE FROM finance_economic_draft_funding") suspend fun clearEconomicDraftFundingForRestore()\n'+
 '    @Query("DELETE FROM finance_economic_draft_shares") suspend fun clearEconomicDraftSharesForRestore()\n'+
 '    @Query("DELETE FROM finance_economic_draft_funding WHERE draftId = :id") suspend fun clearEconomicDraftFunding(id:String)\n'+
 '    @Query("DELETE FROM finance_economic_draft_shares WHERE draftId = :id") suspend fun clearEconomicDraftShares(id:String)\n'+
 '    @Query("DELETE FROM finance_review_expected") suspend fun clearReviewExpectedForRestore()\n'+
 '    @Query("DELETE FROM finance_review_expected WHERE proposalId = :id") suspend fun clearReviewExpected(id: String)\n'+
 '    @Query("DELETE FROM finance_draft_tags WHERE draftId = :id") suspend fun clearDraftTags(id: String)\n'+
 '    @Query("DELETE FROM finance_draft_expected") suspend fun clearDraftExpectedForRestore()\n'+
 '    @Query("DELETE FROM finance_draft_expected WHERE draftId = :id") suspend fun clearDraftExpected(id: String)\n'+
 '    @Query("DELETE FROM finance_evidence_proposals") suspend fun clearEvidenceProposalsForRestore()\n'+
 '    @Query("DELETE FROM finance_grant_events") suspend fun clearGrantEventsForRestore()\n'+
 '    @Query("DELETE FROM finance_grant_events WHERE grantId = :id") suspend fun clearGrantEvents(id: String)\n'+
 '    @Query("DELETE FROM finance_grant_entries") suspend fun clearGrantEntriesForRestore()\n'+
 '    @Query("DELETE FROM finance_grant_entries WHERE grantId = :id") suspend fun clearGrantEntries(id: String)\n';
dao += '    @Query("SELECT '+tables.map(t=>'(SELECT COUNT(*) FROM '+t.name+')').join(' + ')+'")\n    fun observeChanges(): Flow<Long>\n}\n';
snapshot = snapshot.replace(/,\n$/,'\n') + ') {\n    fun tables(): List<Pair<String, List<Any>>> = listOf(\n'+tables.map(t=>'        "'+t.name+'" to '+t.property).join(',\n')+'\n    )\n}\n';
const migration = 'package com.qq.closie.life.data.database\n\nimport androidx.room.migration.Migration\nimport androidx.sqlite.db.SupportSQLiteDatabase\n\n/** Additive v4 → v5. Never infers new economic, identity or posting facts from legacy data. */\nobject FinanceV16Migration : Migration(4, 5) {\n    override fun migrate(db: SupportSQLiteDatabase) {\n'+
  sql.map(s=>'        db.execSQL("""'+s+'""")').join('\n')+
  '\n        db.execSQL("INSERT INTO finance_control (id, restoreEpoch) VALUES (1, 0)")\n'+
  '        db.execSQL("INSERT INTO finance_parties (id, name, isSelf, createdAt, updatedAt, revision, voidedAt) VALUES (\'self\', \'本人\', 1, 0, 0, 1, NULL)")\n    }\n}\n';
function write(file, body) { const p=path.join(root,file);fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,body.trimEnd()+'\n'); }
const shopping = [];
entities = entities.replace(/@Entity\(tableName = "shopping_[\s\S]*?\ndata class Shopping[\s\S]*?\n\)\n\n/g, value => { shopping.push(value); return ''; });
write('app/src/main/java/com/qq/closie/life/finance/FinanceV16Entities.kt',entities);
write('app/src/main/java/com/qq/closie/life/shopping/ShoppingEntities.kt','package com.qq.closie.life.shopping\n\nimport androidx.room.*\n\n'+shopping.join(''));
write('app/src/main/java/com/qq/closie/life/finance/FinanceV16Snapshot.kt',snapshot);
write('app/src/main/java/com/qq/closie/life/data/database/dao/FinanceV16Dao.kt',dao);
write('app/src/main/java/com/qq/closie/life/data/database/FinanceV16Migration.kt',migration);
write('engineering/v16/schema-v5.json',JSON.stringify({tables,sql},null,2)+'\n');
const read = 'package com.qq.closie.life.finance\n\nimport com.qq.closie.life.data.database.LifeDatabase\n\n/** Caller holds the restore lease and the single Room transaction. */\ninternal suspend fun readFinanceV16Snapshot(db: LifeDatabase): FinanceV16Snapshot {\n    val dao = db.financeV16Dao()\n    return FinanceV16Snapshot(\n'+tables.map(t=>'        '+t.property+' = dao.'+t.property+'()').join(',\n')+'\n    )\n}\n';
write('app/src/main/java/com/qq/closie/life/finance/FinanceV16Storage.kt',read);
const merge = 'package com.qq.closie.data.backup\n\nimport com.qq.closie.life.data.database.LifeDatabase\nimport com.qq.closie.life.finance.*\n\n/** Only the restore coordinator may call this, inside its existing atomic DB commit. */\ninternal object FinanceV16BackupRecovery {\n    suspend fun restore(db: LifeDatabase, saved: FinanceV16Snapshot) {\n        val dao = db.financeV16Dao()\n        val current = readFinanceV16Snapshot(db)\n        val now = System.currentTimeMillis()\n'+tables.filter(t=>t.name!=='finance_control').map(t=>{
  const prop=t.cls.replace(/^(Finance|Shopping)/,'').replace(/Entity$/,'');
  const key=x=>t.primary.length===1?x+'.'+t.primary[0]:'listOf('+t.primary.map(k=>x+'.'+k).join(', ')+')';
  const retire=t.fields.some(f=>f.name==='voidedAt');
  const incoming=t.name==='finance_purpose_grants'?'saved.'+t.property+'.map { it.copy(voidedAt = now, revision = Math.addExact(it.revision, 1L)) }':
    t.name==='finance_external_identities'?'saved.'+t.property+'.map { it.copy(matchUsable = false) }':
    t.name==='finance_review_sessions'?'saved.'+t.property+'.map { it.copy(state = FinanceReviewState.LATER) }':
    t.name==='finance_reconciliations'?'saved.'+t.property+'.sortedBy { it.confirmationOrder }.mapIndexed { index, row -> row.copy(confirmationOrder = Math.addExact(current.reconciliationRows.maxOfOrNull { it.confirmationOrder } ?: 0L, index.toLong() + 1L)) }':'saved.'+t.property;
  const clear = {'finance_economic_draft_funding':'clearEconomicDraftFundingForRestore','finance_economic_draft_shares':'clearEconomicDraftSharesForRestore','finance_review_expected':'clearReviewExpectedForRestore','finance_draft_tags':'clearDraftTagsForRestore', 'finance_draft_expected':'clearDraftExpectedForRestore',
    'finance_evidence_proposals':'clearEvidenceProposalsForRestore', 'finance_grant_events':'clearGrantEventsForRestore',
    'finance_grant_entries':'clearGrantEntriesForRestore'}[t.name];
  return '        run {\n'+(clear?'            dao.'+clear+'()\n':'')+'            val incoming = '+incoming+'\n'+
    (retire?'            val ids = incoming.map { '+key('it')+' }.toSet()\n            dao.put'+prop+'(current.'+t.property+'.filter { '+key('it')+' !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })\n':'')+
    '            dao.put'+prop+'(incoming)\n        }';
}).join('\n')+'\n        val epoch = maxOf(current.controlRows.singleOrNull()?.restoreEpoch ?: 0L, saved.controlRows.singleOrNull()?.restoreEpoch ?: 0L)\n        dao.putControl(listOf(FinanceControlEntity(1, Math.addExact(epoch, 1L))))\n    }\n}\n';
write('app/src/main/java/com/qq/closie/data/backup/FinanceV16BackupRecovery.kt',merge);
const existing = {'FinanceEventEntity':'s.v4?.events.orEmpty()', 'FinanceEntryEntity':'s.entries',
  'FinanceAccountEntity':'s.accounts','FinanceCategoryEntity':'s.categories',
  'FinanceEventEntryLinkEntity':'s.v4?.links.orEmpty()','FinanceProposalEntity':'s.v4?.proposals.orEmpty()'};
const referenceChecks = tables.map(t=>
 '    private fun check'+t.cls+'(s: FinanceSnapshot, v: FinanceV16Snapshot) {\n'+
 '        require(v.'+t.property+'.map { listOf('+t.primary.map(k=>'it.'+k).join(', ')+') }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "'+t.name+': 身份重复或缺失" }\n'+
 Object.entries(t.refs).map(([c,r])=>{
   const target=tables.find(x=>x.cls===r.entity);
   const key=t.property+'_'+c;
   return '        val '+key+' = ('+(target?'v.'+target.property:existing[r.entity])+').map { it.'+r.column+' }.toSet()\n'+
   '        require(v.'+t.property+'.all { row -> row.'+c+' == null || row.'+c+' in '+key+' }) { "'+t.name+': '+c+' 引用缺失" }';
 }).join('\n')+'\n'+t.fields.filter(f=>enums[refType(f.type)] || refType(f.type)==='FinanceDirection').map(f=>
 '        require(v.'+t.property+'.all { '+(f.type.endsWith('?')?'it.'+f.name+' == null || ':'')+'it.'+f.name+' in '+refType(f.type)+'.entries })').join('\n')
 +'\n    }\n').join('\n');
const references = 'package com.qq.closie.life.finance\n\n/** Checks are split per table to keep JVM method bytecode bounded. */\ninternal object FinanceV16References {\n    fun validate(s: FinanceSnapshot, v: FinanceV16Snapshot) {\n'+
 tables.map(t=>'        check'+t.cls+'(s,v)').join('\n')+'\n    }\n'+referenceChecks+'}\n';
write('app/src/main/java/com/qq/closie/life/finance/FinanceV16References.kt',references);
console.log('Generated '+tables.length+' typed tables; authoritative Room JSON still requires KSP.');
