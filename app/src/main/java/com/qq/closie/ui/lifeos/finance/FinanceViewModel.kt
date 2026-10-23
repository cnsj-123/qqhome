package com.qq.closie.ui.lifeos.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.qq.closie.life.finance.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class FinancePeriod(val label: String) { ALL("全部"), MONTH("本月"), YEAR("今年"), CUSTOM("自定义") }
enum class FinanceEditorKind(val label: String) { EXPENSE("支出"), INCOME("收入"), TRANSFER("转账") }
data class FinanceFilter(val query: String = "", val period: FinancePeriod = FinancePeriod.MONTH,
    val range: FinanceDateRange = FinanceDateRange.month(), val customLabel: String = "",
    val accountId: String? = null, val categoryId: String? = null, val tagId: String? = null,
    val nature: FinanceNature? = null, val minimumMinor:Long?=null,val maximumMinor:Long?=null,
    val currencyCode:String?=null)
data class FinanceUiState(
    val snapshot: FinanceSnapshot = FinanceSnapshot(), val filter: FinanceFilter = FinanceFilter(),
    val ledger: List<FinanceLedgerRow> = emptyList(), val balances: Map<String, Long> = emptyMap(),
    val month: Map<String, FinanceTotals> = emptyMap(), val year: Map<String, FinanceTotals> = emptyMap(),
    val selected: Map<String, FinanceTotals> = emptyMap(),
    val categories: List<FinanceDimensionTotal> = emptyList(), val tags: List<FinanceDimensionTotal> = emptyList(),
    val loading: Boolean = true, val busy: Boolean = false, val error: String? = null,
    val personalRows: List<FinanceLedgerRow> = emptyList(),
    val unassignedAdjustments: List<FinanceUnassignedAdjustment> = emptyList(),
    val allRows: List<FinanceLedgerRow> = emptyList(),
    val previous: Map<String, FinanceTotals>? = null,
    val previousCategories: List<FinanceDimensionTotal> = emptyList(),
    val calendarRows: List<FinanceLedgerRow> = emptyList(),
    val costRows:List<PersonalCostRow> = emptyList(),val loaded:Boolean=false
)
data class FinanceEntryDraft(val kind: FinanceEditorKind, val amount: String, val accountId: String,
    val targetAccountId: String, val occurredAt: String, val description: String,
    val category: String = "", val tags: String = "", val preservedOccurredAt: Long? = null,
    val subcategory: String = "", val relationship: FinanceRelationshipDraft = FinanceRelationshipDraft(), val categoryId: String? = null,
    val costReductionText:String="",val editGeneration:Long=0)
enum class FinanceOpeningChoice { SAVE_TIME, CUSTOM, FROM_ZERO }
data class FinanceAccountDraft(val name: String, val kind: FinanceAccountKind, val openingBalance: String, val currencyCode: String? = null,
    val openingChoice:FinanceOpeningChoice=FinanceOpeningChoice.SAVE_TIME,val anchorText:String="",val expectedUpdatedAt:Long?=null,val epoch:Long?=null)

/** Presentation and manual commands only; screens never obtain a DAO or persist projections. */
class FinanceViewModel(private val repository: FinanceRepository,private val savedState:SavedStateHandle=SavedStateHandle()) : ViewModel() {
    private val snapshot = MutableStateFlow<FinanceSnapshot?>(null)
    private var activeSpace=runCatching {FinanceView.valueOf(savedState.get<String>("finance_space") ?: "TRANSACTIONS")}.getOrDefault(FinanceView.TRANSACTIONS)
    private val spaceFilters=FinanceView.entries.associateWith {FinanceFilterState.decode(savedState.get<android.os.Bundle>("finance_filter_"+it.name))}.toMutableMap()
    private val filter = MutableStateFlow(spaceFilters.getValue(activeSpace))
    internal val filterInput:StateFlow<FinanceFilter> = filter.asStateFlow()
    private val error = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)
    private val editorVersions=FinanceEditorVersions()
    private val draftMutex get()=editorVersions.mutex

    private val projection = combine(snapshot, filter) { data, selection ->
        val message:String?=null
        val saving=false
        if (data == null) FinanceUiState(filter = selection, loading = message == null, busy = saving, error = message)
        else {
            val allRows = FinanceProjection.ledger(data)
            val tagEntries = data.entryTags.filter { it.tagId == selection.tagId }.map { it.entryId }.toSet()
            val natureEvents = data.v4?.events.orEmpty().filter { it.nature == selection.nature }.map { it.id }.toSet()
            val natureEntries = data.v4?.activeLinks.orEmpty().filter { it.eventId in natureEvents }.map { it.entryId }.toSet()
            val linkedEntries = data.v4?.activeLinks.orEmpty().map { it.entryId }.toSet()
            fun baseFor(range: FinanceDateRange) = FinanceProjection.ledger(data, range, selection.query).filter { row ->
                (selection.accountId == null || row.account.id == selection.accountId || row.targetAccount?.id == selection.accountId) &&
                (selection.nature == null || row.entry.id in natureEntries ||
                    (selection.nature == FinanceNature.PERSONAL && row.transfer == null && row.entry.id !in linkedEntries))
            }
            val categoryIds = selection.categoryId?.let { FinanceCategoryTree(data.categories).descendants(it) }
            fun categoryMatches(row: FinanceLedgerRow) = categoryIds == null || row.entry.categoryId in categoryIds
            fun cashAmountMatches(row:FinanceLedgerRow)=(selection.currencyCode==null || row.currencyCode==selection.currencyCode) &&
                (selection.minimumMinor==null || row.entry.amountMinor>=selection.minimumMinor) &&
                (selection.maximumMinor==null || row.entry.amountMinor<=selection.maximumMinor)
            val baseRows = baseFor(selection.range)
            val rows = baseRows.filter { categoryMatches(it) && (selection.tagId == null || it.entry.id in tagEntries) && cashAmountMatches(it) }
            // Apply dimension filters after attribution, so a refund follows its payment category/tag.
            fun personalFor(range:FinanceDateRange)=FinancePersonalProjection.rows(data, baseFor(range)).filter {
                categoryMatches(it) && (selection.tagId == null || selection.tagId in it.tagIds) && cashAmountMatches(it)
            }
            val personal=personalFor(selection.range)
            fun costsFor(range:FinanceDateRange):List<PersonalCostRow> = FinanceQueryEngine.execute(data,FinanceQuerySpec(
                startInclusive=range.startInclusive,endExclusive=range.endExclusive,
                accountIds=setOfNotNull(selection.accountId),categoryIds=setOfNotNull(selection.categoryId),
                tagIds=setOfNotNull(selection.tagId),currencyCode=selection.currencyCode,
                minimumMinor=selection.minimumMinor,maximumMinor=selection.maximumMinor,text=selection.query)).costs.filter { row ->
                selection.nature==null || data.v4?.events.orEmpty().any { it.id==row.eventId && it.nature==selection.nature } ||
                    (row.eventId==null && selection.nature==FinanceNature.PERSONAL)
            }
            val costRows=costsFor(selection.range)
            fun totals(costs:List<PersonalCostRow>,cashRows:List<FinanceLedgerRow>):Map<String,FinanceTotals> {
                val income=FinanceProjection.totals(cashRows)
                val costsByCurrency=costs.groupBy { it.currencyCode }
                return (income.keys+costsByCurrency.keys).associateWith { currency ->
                    FinanceTotals(exactSum(costsByCurrency[currency].orEmpty().mapNotNull { it.amountMinor }),income[currency]?.incomeMinor ?: 0L)
                }
            }
            val categoryTree=FinanceCategoryTree(data.categories)
            fun categoriesFor(costs:List<PersonalCostRow>)=costs.groupBy { (categoryTree.root(it.categoryId)?.name ?: "未分类") to it.currencyCode }.map { (key,rows) ->
                FinanceDimensionTotal(key.first,key.second,FinanceTotals(exactSum(rows.mapNotNull { it.amountMinor }),0))
            }
            val previousRange=previousFinanceRange(selection.range)
            FinanceUiState(data, selection, rows,
                data.accounts.associate { it.id to FinanceProjection.balance(it, data.entries) },
                totals(costsFor(FinanceDateRange.month()),personalFor(FinanceDateRange.month())),
                totals(costsFor(FinanceDateRange.year()),personalFor(FinanceDateRange.year())),
                totals(costRows,personal),
                categoriesFor(costRows), FinanceProjection.tagTotals(personal),
                loading = false, loaded=true, busy = saving, error = message, personalRows = personal,
                unassignedAdjustments = FinancePersonalProjection.unassignedAdjustments(data, allRows), allRows = allRows,
                previous = previousRange?.let { totals(costsFor(it),personalFor(it)) },
                previousCategories = previousRange?.let { categoriesFor(costsFor(it)) }.orEmpty(),
                calendarRows = baseFor(FinanceDateRange()).filter { categoryMatches(it) && (selection.tagId == null || it.entry.id in tagEntries) && cashAmountMatches(it) },
                costRows=costRows)
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
    // Busy/error are main-thread presentation state. A large financial projection must never delay
    // disabling command buttons or re-enable them from an old background calculation.
    val state:StateFlow<FinanceUiState> = combine(projection,error,busy) { projected,message,saving ->
        projected.copy(busy=saving,error=message,loading=!projected.loaded && message==null)
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),FinanceUiState())

    private var reader: kotlinx.coroutines.Job? = null
    init { reload() }
    fun reload() {
        reader?.cancel()
        error.value = null
        reader = viewModelScope.launch {
            repository.observeSnapshot().catch { failure -> error.value = "账目读取未完成，原记录仍保留：${failure.message ?: "请重试"}" }
                .collect { snapshot.value = it }
        }
    }
    fun clearError() { error.value = null }
    fun exportFinance(context:android.content.Context,uri:android.net.Uri,done:()->Unit)=mutate(done) {
        com.qq.closie.data.backup.FinanceExportManager.export(context,repository,uri).getOrThrow()
    }
    fun shareFinance(context:android.content.Context,preview:FinanceSharePreview,done:()->Unit)=mutate(done) {
        val text=repository.confirmedShare(preview)
        context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type="text/plain";putExtra(android.content.Intent.EXTRA_TEXT,text)
        },"分享已核对的财务片段"))
    }
    fun workspaceAction(epoch:Long,done:()->Unit={},action:suspend (FinanceWorkspaceRepository,FinanceCommandToken)->Unit) =
        mutate(done) { action(repository.workspace,token(epoch)) }
    fun economicAction(epoch:Long,done:()->Unit={},action:suspend (FinanceEconomicRepository,FinanceCommandToken)->Unit) =
        mutate(done) { action(repository.economics,token(epoch)) }
    fun shoppingAction(epoch:Long,done:()->Unit={},action:suspend (com.qq.closie.life.shopping.ShoppingRepository,FinanceCommandToken)->Unit) =
        mutate(done) { action(repository.shopping,token(epoch)) }
    fun provenanceAction(epoch:Long,done:()->Unit={},action:suspend (FinanceProvenanceRepository,FinanceCommandToken)->Unit)=
        mutate(done) {action(repository.provenance,token(epoch))}
    fun mediaAction(epoch:Long,action:suspend (FinanceMediaRepository,FinanceCommandToken)->Unit)=
        mutate({}) {action(repository.media,token(epoch))}
    fun expectedAction(epoch:Long,done:()->Unit={},action:suspend (FinanceExpectedTaskRepository,FinanceCommandToken)->Unit)=
        mutate(done) {action(repository.expectations,token(epoch))}
    private suspend fun persistEconomicLocked(editor:String,initial:Long?,draft:FinanceEconomicDraftEntity,
        funding:List<FinanceEconomicDraftFundingEntity>,shares:List<FinanceEconomicDraftShareEntity>):Long=
        editorVersions.persistLocked(editor,initial) {prior->repository.economicDrafts.save(token(draft.epoch),draft,funding,shares,prior)}
    suspend fun persistEconomic(editor:String,initial:Long?,draft:FinanceEconomicDraftEntity,
        funding:List<FinanceEconomicDraftFundingEntity>,shares:List<FinanceEconomicDraftShareEntity>) {
        try {draftMutex.withLock {if(!editorVersions.isFinished(editor)) persistEconomicLocked(editor,initial,draft,funding,shares)}}
        catch(e:CancellationException){throw e}
        catch(e:Exception){error.value=e.message ?: "经济草稿保存未完成"}
    }
    fun leaveEconomic(editor:String,initial:Long?,draft:FinanceEconomicDraftEntity,funding:List<FinanceEconomicDraftFundingEntity>,
        shares:List<FinanceEconomicDraftShareEntity>,done:()->Unit)=mutate(done) {
        draftMutex.withLock {persistEconomicLocked(editor,initial,draft,funding,shares)}
    }
    private fun queueFinalFlush(block:suspend ()->Unit) {
        viewModelScope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {withContext(NonCancellable) {draftMutex.withLock {block()}}}
            catch(e:CancellationException){throw e}
            catch(e:Exception){error.value=e.message ?: "离开前草稿保存未完成"}
        }
    }
    fun flushEconomicOnStop(editor:String,initial:Long?,draft:FinanceEconomicDraftEntity,funding:List<FinanceEconomicDraftFundingEntity>,
        shares:List<FinanceEconomicDraftShareEntity>)=queueFinalFlush {
        val current=repository.snapshot().v16
        if((current?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L)==draft.epoch &&
            current?.economicDraftRows.orEmpty().none {it.id==draft.id && it.voidedAt!=null}) {
            persistEconomicLocked(editor,initial,draft,funding,shares)
        }
    }
    fun confirmEconomicDraft(editor:String,initial:Long?,draft:FinanceEconomicDraftEntity,funding:List<FinanceEconomicDraftFundingEntity>,
        shares:List<FinanceEconomicDraftShareEntity>,discard:Boolean=false,done:()->Unit)=mutate(done) {
        draftMutex.withLock {
            val revision=persistEconomicLocked(editor,initial,draft,funding,shares)
            editorVersions.confirmLocked(editor,revision,finish=true) {
                if(discard) repository.economicDrafts.discard(token(draft.epoch),draft.id,revision)
                else repository.economicDrafts.commit(token(draft.epoch),draft.id,revision)
            }
        }
    }
    private var previewRequest:kotlinx.coroutines.Job?=null
    fun previewVoid(entryId:String?=null,eventId:String?=null,done:(FinanceChangePreview)->Unit) {
        previewRequest?.cancel()
        previewRequest=viewModelScope.launch {
            // An enabled frame can lag the synchronous busy owner. Do not silently lose a read-only preview.
            busy.first {!it}
            var result:FinanceChangePreview?=null
            mutate({result?.let(done)}) {result=repository.recovery.previewVoid(entryId,eventId)}
        }
    }
    fun confirmVoid(preview:FinanceChangePreview,done:()->Unit)=mutate(done) { repository.recovery.voidConfirmed(preview) }
    fun previewBatch(ids:Set<String>,edit:FinanceBatchCorrection,done:(FinanceChangePreview)->Unit)=
        mutate({}) {done(repository.recovery.previewBatch(ids,edit))}
    fun confirmBatch(preview:FinanceChangePreview,edit:FinanceBatchCorrection,done:()->Unit)=
        mutate(done) {repository.recovery.correctBatch(preview,edit)}
    fun recover(groupId:String,epoch:Long,done:()->Unit)=mutate(done) { repository.recovery.recover(groupId,token(epoch)) }
    fun previewAnchor(accountId:String,opening:Long,anchor:Long,fromZero:Boolean,done:(FinanceAnchorPreview)->Unit)=
        mutate({}) { done(repository.workspace.previewAnchor(accountId,opening,anchor,fromZero)) }
    fun currentEpoch()=snapshot.value?.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L
    private fun token(epoch:Long)=FinanceCommandToken(java.util.UUID.randomUUID().toString(),epoch)
    private suspend fun persistDraftLocked(editorId:String,draftId:String,initialRevision:Long?,draft:FinanceEntryDraft,
        continuous:Boolean,epoch:Long):Long {
        val currency=snapshot.value?.accounts?.firstOrNull { it.id==draft.accountId }?.currencyCode ?: "CNY"
        val input=FinanceDraftEntity(id=draftId,accountId=draft.accountId.ifBlank { null },
            kind=FinanceDraftKind.valueOf(draft.kind.name),targetAccountId=draft.targetAccountId.ifBlank { null },
            amountExpression=draft.amount,description=draft.description,occurredAt=draft.preservedOccurredAt ?: System.currentTimeMillis(),
            occurredText=draft.occurredAt,categoryId=draft.categoryId,categoryName=draft.category,subcategoryName=draft.subcategory,
            currencyCode=currency,continuous=continuous,epoch=epoch,editGeneration=draft.editGeneration,
            nature=draft.relationship.nature,role=draft.relationship.role,eventId=draft.relationship.eventId,shareText=draft.relationship.share,
            excluded=draft.relationship.excluded,excludedBudget=draft.relationship.excludedBudget,
            expectedEdited=draft.relationship.expectedEdited,eventUpdatedAt=draft.relationship.eventUpdatedAt,createdAt=0,updatedAt=0)
        val expected=draft.relationship.expectedFlows.mapIndexed { position,flow -> FinanceDraftExpectedEntity(draftId,position,
            flow.id,flow.role,flow.amount,flow.note,flow.cancelled) }
        return editorVersions.persistLocked(editorId,initialRevision) {prior->
            repository.workspace.saveDraft(token(epoch),input,draft.tags.split('，',',','\n').map(String::trim).filter(String::isNotBlank).toSet(),expected,prior)
        }
    }
    suspend fun persistDraft(editorId:String,draftId:String,initialRevision:Long?,draft:FinanceEntryDraft,continuous:Boolean,epoch:Long) {
        try { draftMutex.withLock { if(!editorVersions.isFinished(editorId)) persistDraftLocked(editorId,draftId,initialRevision,draft,continuous,epoch) } }
        catch(cancelled:CancellationException) { throw cancelled }
        catch(_:FinanceDraftGenerationChanged) { /* A queued save from before a continuous commit is obsolete. */ }
        catch(failure:Exception) { error.value=failure.message ?: "草稿保存未完成" }
    }
    fun leaveDraft(editorId:String,draftId:String,initialRevision:Long?,draft:FinanceEntryDraft,continuous:Boolean,epoch:Long,done:()->Unit) =
        mutate(done) { draftMutex.withLock { persistDraftLocked(editorId,draftId,initialRevision,draft,continuous,epoch) } }
    fun flushDraftOnStop(editorId:String,draftId:String,initialRevision:Long?,draft:FinanceEntryDraft,continuous:Boolean,epoch:Long)=queueFinalFlush {
        val current=repository.snapshot().v16
        if((current?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L)==epoch &&
            current?.draftRows.orEmpty().none {it.id==draftId && (it.voidedAt!=null || it.editGeneration!=draft.editGeneration)}) {
            persistDraftLocked(editorId,draftId,initialRevision,draft,continuous,epoch)
        }
    }
    fun saveDraftRecord(editorId:String,draftId:String,initialRevision:Long?,draft:FinanceEntryDraft,
        continuous:Boolean,epoch:Long,done:()->Unit) = mutate(done) {
        draftMutex.withLock {
            val revision=persistDraftLocked(editorId,draftId,initialRevision,draft,continuous,epoch)
            editorVersions.confirmLocked(editorId,revision,finish=!continuous) {
                repository.workspace.commitDraft(token(epoch),draftId,revision,FinanceTime.resolve(draft.occurredAt,draft.preservedOccurredAt))
            }
        }
    }
    fun activateSpace(space:FinanceView) {
        if(space==activeSpace) return
        spaceFilters[activeSpace]=filter.value
        activeSpace=space;savedState["finance_space"]=space.name
        filter.value=spaceFilters[space] ?: FinanceFilter()
    }
    private fun updateFilter(value:FinanceFilter) {
        val safe=FinanceFilterState.validated(value)
        filter.value=safe;spaceFilters[activeSpace]=safe
        savedState["finance_filter_"+activeSpace.name]=FinanceFilterState.encode(safe)
    }
    fun amountRange(minimum:Long?,maximum:Long?,currency:String?) {
        require(minimum==null || maximum==null || minimum<=maximum)
        updateFilter(filter.value.copy(minimumMinor=minimum,maximumMinor=maximum,currencyCode=currency))
    }
    fun applyQuery(spec:FinanceQuerySpec) {
        spec.validate()
        require(spec.accountIds.size<=1 && spec.categoryIds.size<=1 && spec.tagIds.size<=1 &&
            spec.eventIds.isEmpty() && spec.mode==FinanceQueryMode.PERSONAL_COST) { "此保存视图请在查询工作页完整打开" }
        updateFilter(FinanceFilter(spec.text,FinancePeriod.CUSTOM,FinanceDateRange(spec.startInclusive,spec.endExclusive),
            "保存视图",spec.accountIds.singleOrNull(),spec.categoryIds.singleOrNull(),spec.tagIds.singleOrNull(),
            minimumMinor=spec.minimumMinor,maximumMinor=spec.maximumMinor,currencyCode=spec.currencyCode))
    }
    fun filterAccount(id: String?) { updateFilter(filter.value.copy(accountId = id)) }
    fun filterCategory(id: String?) { updateFilter(filter.value.copy(categoryId = id)) }
    fun filterTag(id: String?) { updateFilter(filter.value.copy(tagId = id)) }
    fun filterNature(nature: FinanceNature?) { updateFilter(filter.value.copy(nature = nature)) }
    fun search(query: String) { updateFilter(filter.value.copy(query = query.take(FinanceFilterState.MAX_QUERY))) }
    /** A restored calendar tab must not accidentally show an unbounded ledger after VM recreation. */
    fun ensureCalendarMonth(viewedMonth: java.time.YearMonth? = null) {
        val current = filter.value
        val month = viewedMonth ?: current.range.startInclusive?.let {
            java.time.YearMonth.from(java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
        } ?: java.time.YearMonth.now()
        val range = FinanceDateRange.month(month.atDay(1))
        if (current.range != range) customRange(month.atDay(1).toString(), month.atEndOfMonth().toString())
    }
    fun selectPeriod(period: FinancePeriod) {
        if (period == FinancePeriod.CUSTOM) return
        updateFilter(filter.value.copy(period = period, customLabel = "", range = when (period) {
            FinancePeriod.MONTH -> FinanceDateRange.month()
            FinancePeriod.YEAR -> FinanceDateRange.year()
            else -> FinanceDateRange()
        }))
    }
    fun customRange(start: String, end: String): Boolean = try {
        val first = LocalDate.parse(start.trim())
        val last = LocalDate.parse(end.trim())
        require(!last.isBefore(first)) { "结束日期不能早于开始日期" }
        updateFilter(filter.value.copy(period = FinancePeriod.CUSTOM,
            range = FinanceDateRange.days(first, last), customLabel = "$first — $last"))
        clearError()
        true
    } catch (failure: Exception) {
        error.value = if (failure is java.time.DateTimeException) "日期请填写为 年-月-日" else failure.message
        false
    }

    fun saveAccount(id: String?, draft: FinanceAccountDraft, onSaved: () -> Unit) = mutate(onSaved) {
        val currency = draft.currencyCode ?: snapshot.value?.accounts?.find { it.id == id }?.currencyCode ?: "CNY"
        val opening=if(id==null && draft.openingChoice==FinanceOpeningChoice.FROM_ZERO) 0L else FinanceMoney.parse(draft.openingBalance.ifBlank { "0" }, currency, allowNegative = true)
        require(id==null || snapshot.value?.accounts?.single {it.id==id}?.openingBalanceMinor==opening) {"请在账户起点页先预览余额影响，再修改已有起点金额"}
        val anchor=if(id!=null) null else when(draft.openingChoice) {
            FinanceOpeningChoice.SAVE_TIME->null;FinanceOpeningChoice.CUSTOM->FinanceTime.parse(draft.anchorText);FinanceOpeningChoice.FROM_ZERO->0L
        }
        repository.saveAccount(id, draft.name, draft.kind, currency,
            opening,balanceAnchorAt=anchor,expectedUpdatedAt=draft.expectedUpdatedAt,expectedRestoreEpoch=draft.epoch)
    }
    fun unarchiveAccount(id: String, onSaved: () -> Unit) = mutate(onSaved) { repository.unarchiveAccount(id) }
    fun archiveAccount(id: String, onSaved: () -> Unit) = mutate(onSaved) { repository.archiveAccount(id) }
    fun saveRecord(row: FinanceLedgerRow?, draft: FinanceEntryDraft, onSaved: () -> Unit,expectedEpoch:Long?=null) = mutate(onSaved) {
        val account = requireNotNull(snapshot.value?.accounts?.find { it.id == draft.accountId }) { "请先建立并选择账户" }
        val amount = FinanceCalculator.evaluate(draft.amount, account.currencyCode)
        val occurred = FinanceTime.resolve(draft.occurredAt, draft.preservedOccurredAt)
        if (draft.kind == FinanceEditorKind.TRANSFER) {
            require(row == null || row.transfer != null) { "普通流水不能变更为转账，请先作废后另记" }
            repository.saveTransfer(row?.transfer?.id, draft.accountId, draft.targetAccountId, amount, draft.description, occurred,
                expectedUpdatedAt = row?.transfer?.updatedAt,expectedRestoreEpoch=expectedEpoch)
        } else {
            require(row?.transfer == null) { "转账需要整体编辑" }
            val multiple = snapshot.value?.v4?.activeLinks.orEmpty().count { it.entryId == row?.entry?.id } > 1
            require(!multiple || (amount == row?.entry?.amountMinor &&
                draft.kind.name == if (row?.entry?.direction == FinanceDirection.INFLOW) "INCOME" else "EXPENSE")) {
                "此流水分配给多个事件，请先调整事件分配再改变整笔金额或方向"
            }
            repository.saveEntry(row?.entry?.id, draft.accountId,
                if (draft.kind == FinanceEditorKind.INCOME) FinanceDirection.INFLOW else FinanceDirection.OUTFLOW,
                amount, draft.description, occurred, draft.category, draft.tags.split(Regex("[,，\\n]")), draft.subcategory,
                if (multiple) null else draft.relationship.resolve(account.currencyCode), row?.entry?.revision, categoryId = draft.categoryId,
                expectedRestoreEpoch=expectedEpoch)
        }
    }
    fun voidRecord(row: FinanceLedgerRow, onSaved: () -> Unit) = mutate(onSaved) {
        if (row.transfer != null) repository.voidTransfer(row.transfer.id) else repository.voidEntry(row.entry.id)
    }
    fun allocate(row: FinanceLedgerRow, parts: List<FinanceAllocation>, done: () -> Unit) = mutate(done) {
        repository.allocateEntry(row.entry.id, parts, row.entry.revision)
    }
    fun createCategory(parentId: String, name: String, onCreated: (String) -> Unit) {
        var created: String? = null
        mutate({ created?.let(onCreated) }) { created = repository.createCategory(name, parentId).id }
    }
    private fun mutate(onSaved: () -> Unit, operation: suspend () -> Unit) {
        if (busy.value || snapshot.value == null) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try {
                operation()
                snapshot.value = repository.snapshot()
                onSaved()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error.value = failure.message ?: "保存未完成，请重试" }
            finally { busy.value = false }
        }
    }
}
