package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.*

@Composable
internal fun FinanceEntrySheet(state: FinanceUiState, row: FinanceLedgerRow?, vm: FinanceViewModel,
    initialRelationship: FinanceRelationshipDraft? = null, initialKind: FinanceEditorKind = FinanceEditorKind.EXPENSE, onSaved: (() -> Unit)? = null,
    onTerminalClose:(()->Unit)?=null,initialAccountId:String?=null,initialTargetAccountId:String?=null,
    onCommitted: (String)->Unit = {},
    printDestination: (String)->com.qq.closie.ui.lifeos.components.LifeSurfaceOrigin? = {null},
    onClose: () -> Unit) {
    if(!state.loaded) {FinanceReceipt("正在打开账本",error=state.error,onClose=onClose) {Text("账本快照读取完成后可继续编辑。")};return}
    val colors = LocalLifeOsColors.current
    val active = state.snapshot.accounts.filter { it.archivedAt == null }
    val candidateDraft=remember(row?.id) { if(row==null && initialRelationship==null && initialKind==FinanceEditorKind.EXPENSE)
        state.snapshot.v16?.draftRows.orEmpty().filter { it.voidedAt==null }.maxByOrNull { it.updatedAt } else null }
    val editorId=rememberSaveable(row?.id) { java.util.UUID.randomUUID().toString() }
    val persistentDraftId=rememberSaveable(editorId) { candidateDraft?.id ?: java.util.UUID.randomUUID().toString() }
    var printedId by remember(editorId) { mutableStateOf<String?>(null) }
    val reducedSaveMotion = com.qq.closie.ui.lifeos.components.reducedLifeMotion()
    val finish by rememberUpdatedState(onSaved ?: onClose)
    val savedDraft=remember(persistentDraftId) {state.snapshot.v16?.draftRows.orEmpty().singleOrNull {it.id==persistentDraftId && it.voidedAt==null}}
    val initialDraftRevision=remember(editorId) { savedDraft?.revision }
    val editorEpoch=rememberSaveable(editorId) { state.snapshot.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L }
    printedId?.let { id ->
        if(editorEpoch==(state.snapshot.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L)) {
            FinancePrintedReceipt(FinanceProjection.ledger(state.snapshot).singleOrNull {it.id==id},
                destination={printDestination(id)},onClose=finish)
        } else {
            FinanceReceipt("此编辑器已结束",onClose=onTerminalClose ?: onClose) {Text("恢复代际已改变，请重新打开账簿。")}
        }
        return
    }
    var editGeneration by rememberSaveable(editorId) {mutableLongStateOf(savedDraft?.editGeneration ?: 0L)}
    val restoredGenerationMismatch=remember(editorId) {savedDraft!=null && editGeneration!=savedDraft.editGeneration}
    if((row==null && state.snapshot.v16?.draftRows.orEmpty().any {it.id==persistentDraftId && it.voidedAt!=null}) ||
        editorEpoch!=(state.snapshot.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L) ||
        (row==null && restoredGenerationMismatch)) {
        FinanceReceipt("此编辑器已结束",onClose=onTerminalClose ?: onClose) {Text("上一笔已提交、草稿已完成或恢复代际已改变。收起后可重新打开，不会以旧内容再次记账。")};return
    }
    var savedFeedback by remember {mutableStateOf(false)}
    var feedbackGeneration by remember {mutableIntStateOf(0)}
    val resetPrint=remember {Animatable(1f)}
    LaunchedEffect(feedbackGeneration) {if(savedFeedback){
        resetPrint.snapTo(0f)
        resetPrint.animateTo(1f,tween(if(reducedSaveMotion) 150 else 280))
        savedFeedback=false
    }}
    val saveInteraction=remember {MutableInteractionSource()}
    val savePressed by saveInteraction.collectIsPressedAsState()
    val saveScale by animateFloatAsState(if(savePressed && !reducedSaveMotion)0.96f else 1f,LedgerMotion.SpringSnappy,label="receipt-save-press")
    var continuous by rememberSaveable(editorId) { mutableStateOf(savedDraft?.continuous ?: false) }
    var kindName by rememberSaveable(row?.id) { mutableStateOf(when {
        row?.transfer != null -> FinanceEditorKind.TRANSFER.name
        row?.entry?.direction == FinanceDirection.INFLOW -> FinanceEditorKind.INCOME.name
        else -> savedDraft?.kind?.name ?: initialKind.name
    }) }
    val kind = FinanceEditorKind.valueOf(kindName)
    var amount by rememberSaveable(row?.id) { mutableStateOf(row?.let { FinanceMoney.input(it.entry.amountMinor, it.currencyCode) } ?: savedDraft?.amountExpression.orEmpty()) }
    val latestAccount=state.snapshot.entries.filter { it.voidedAt==null && active.any { a -> a.id==it.accountId } }.maxByOrNull { it.updatedAt }?.accountId
    var accountId by rememberSaveable(row?.id) { mutableStateOf(row?.account?.id ?: savedDraft?.accountId ?: initialAccountId ?: latestAccount ?: active.firstOrNull()?.id.orEmpty()) }
    var targetId by rememberSaveable(row?.id) { mutableStateOf(row?.targetAccount?.id ?: savedDraft?.targetAccountId ?: initialTargetAccountId ?: active.firstOrNull { it.id != accountId }?.id.orEmpty()) }
    val preservedOccurredAt = rememberSaveable(row?.id) { row?.entry?.occurredAt ?: savedDraft?.occurredAt ?: System.currentTimeMillis() }
    var occurredAt by rememberSaveable(row?.id) { mutableStateOf(savedDraft?.occurredText ?: FinanceTime.input(preservedOccurredAt)) }
    var description by rememberSaveable(row?.id) { mutableStateOf(row?.entry?.description ?: savedDraft?.description.orEmpty()) }
    val leaf = state.snapshot.categories.find { it.id == (row?.entry?.categoryId ?: savedDraft?.categoryId) }
    val parent = state.snapshot.categories.find { it.id == leaf?.parentId }
    var selectedCategoryId by rememberSaveable(row?.id) { mutableStateOf(row?.entry?.categoryId ?: savedDraft?.categoryId) }
    var category by rememberSaveable(row?.id) { mutableStateOf(savedDraft?.categoryName ?: parent?.name ?: leaf?.name.orEmpty()) }
    var subcategory by rememberSaveable(row?.id) { mutableStateOf(savedDraft?.subcategoryName ?: if (parent == null) "" else leaf?.name.orEmpty()) }
    var relationship by rememberSaveable(row?.id, stateSaver = FinanceRelationshipDraft.Saver) { mutableStateOf(initialRelationship ?:
        savedDraft?.let { draft -> FinanceRelationshipDraft(draft.nature,draft.role,draft.eventId,draft.shareText,draft.excluded,
            draft.excludedBudget,state.snapshot.v16?.draftExpectedRows.orEmpty().filter { it.draftId==draft.id }.sortedBy { it.position }.map {
                FinanceExpectedFlowDraft(it.expectedId,it.role,it.amountText,it.note,it.cancelled)
            },draft.expectedEdited,draft.eventUpdatedAt) } ?: FinanceRelationshipDraft.from(state.snapshot, row)) }
    var tags by rememberSaveable(row?.id) { mutableStateOf(if(savedDraft!=null) state.snapshot.v16?.draftTagRows.orEmpty()
        .filter { it.draftId==savedDraft.id }.joinToString("，") { it.name } else row?.tags.orEmpty().joinToString("，")) }
    var voidPreview by remember { mutableStateOf<FinanceChangePreview?>(null) }
    var allocationOpen by remember { mutableStateOf(false) }
    var pickingCategory by remember { mutableStateOf(false) }
    var moreFields by rememberSaveable { mutableStateOf(false) }
    var businessOpen by rememberSaveable {mutableStateOf(row!=null || initialRelationship!=null)}
    var pickingDate by remember { mutableStateOf(false) }
    val multiple = state.snapshot.v4?.activeLinks.orEmpty().count { it.entryId == row?.entry?.id } > 1
    val recentAccounts = remember(state.snapshot.entries) { state.snapshot.entries.filter { it.voidedAt == null }.sortedByDescending { it.updatedAt }.map { it.accountId }.distinct().take(4) }
    val accounts = state.snapshot.accounts.filter { it.archivedAt == null || it.id == row?.account?.id || it.id == row?.targetAccount?.id }
    val currency = accounts.find { it.id == accountId }?.currencyCode ?: "CNY"
    fun currentDraft()=FinanceEntryDraft(kind,amount,accountId,targetId,occurredAt,description,category,tags,preservedOccurredAt,subcategory,relationship,selectedCategoryId,editGeneration=editGeneration)
    LaunchedEffect(editorId,editGeneration,kindName,amount,accountId,targetId,occurredAt,description,selectedCategoryId,category,subcategory,tags,relationship,continuous) {
        if(row==null) { kotlinx.coroutines.delay(250); vm.persistDraft(editorId,persistentDraftId,initialDraftRevision,currentDraft(),continuous,editorEpoch) }
    }
    fun leave() {
        if(row==null) vm.leaveDraft(editorId,persistentDraftId,initialDraftRevision,currentDraft(),continuous,editorEpoch,onClose) else onClose()
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        if(row==null) vm.flushDraftOnStop(editorId,persistentDraftId,initialDraftRevision,currentDraft(),continuous,editorEpoch)
    }
    FinanceEntryContainer(if (row == null) "记一笔" else "修改这笔记录", state.busy, state.error, ::leave, manualPaper = row == null, actions = {
        FinanceAmountKeypad(amount, currency, !state.busy, compact = row == null) { amount = it }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        if (row == null) {
            TextButton({continuous=!continuous},enabled=!state.busy,modifier=Modifier.weight(1f).height(46.dp).semantics {selected=continuous}) { Text("再记一笔",style=LedgerText.body) }
        }
        Button(onClick = {
            if(row==null) vm.saveDraftRecord(editorId,persistentDraftId,initialDraftRevision,currentDraft(),continuous,editorEpoch) { committedId ->
                if(continuous) {savedFeedback=true;feedbackGeneration++
                    editGeneration=Math.addExact(editGeneration,1L);amount="";description="";selectedCategoryId=null;category="";subcategory="";tags=""
                    relationship=FinanceRelationshipDraft(role=if(kind==FinanceEditorKind.INCOME) FinanceFlowRole.OTHER else FinanceFlowRole.PAYMENT)
                } else {onCommitted(committedId);printedId=committedId}
            } else vm.saveRecord(row,currentDraft(),onSaved ?: onClose,editorEpoch)
        },
            enabled = !state.busy, interactionSource=saveInteraction,
            modifier = Modifier.weight(1f).semantics {contentDescription="保存记录"}.height(46.dp).graphicsLayer {scaleX=saveScale;scaleY=saveScale},shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) { Text(if (state.busy) "正在保存……" else if(row==null) "完成" else "保存记录",style=LedgerText.body) }
        }
    }) {
        if(row==null) {
            if (savedFeedback || savedDraft != null) Text(if(savedFeedback) "已保存，可继续记录" else "已恢复草稿",style=LifeText.caption)
            if(continuous) Text("本轮锁定：${accounts.firstOrNull { it.id==accountId }?.name ?: "待选择账户"} · $occurredAt",style=LifeText.caption)
        }
        FinanceEntryTabs(kind, {tab -> !state.busy && (row==null || if(row.transfer!=null) tab==FinanceEditorKind.TRANSFER else tab!=FinanceEditorKind.TRANSFER)}) {tab->
            kindName=tab.name
            if(row==null) relationship=relationship.copy(role=if(tab==FinanceEditorKind.INCOME) FinanceFlowRole.OTHER else FinanceFlowRole.PAYMENT)
            vm.clearError()
        }
        if (kind != FinanceEditorKind.TRANSFER) {
            Box(Modifier.financeResetPrint(resetPrint.value,2,reducedSaveMotion)) {FinanceInlineCategories(state.snapshot,selectedCategoryId,state.busy,selectedName=category,selectedChild=subcategory,
                onTemplate={root,child->selectedCategoryId=null;category=root;subcategory=child.orEmpty()}) {selectedCategoryId=it;category="";subcategory=""}
            }
        }
        Row(Modifier.padding(top=20.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            if(row==null) {
                FinanceCompactEntryField(description,{description=it},"备注（可留空）",!state.busy,Modifier.weight(1f).financeResetPrint(resetPrint.value,4,reducedSaveMotion))
                FinanceCompactEntryField(amount,{amount=it},"金额 · "+currency,!state.busy,Modifier.weight(.7f).financeResetPrint(resetPrint.value,0,reducedSaveMotion),amount=true)
                Text(if(currency=="CNY") "¥" else currency,style=LedgerText.meta,color=colors.inkSecondary)
            } else {
                OutlinedTextField(description,{description=it},Modifier.weight(1f),label={Text("备注（可留空）")},enabled=!state.busy,singleLine=true)
                Box(Modifier.weight(.85f)){FinanceAmountInput(amount,currency,!state.busy,showKeypad=false){amount=it}}
            }
        }
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            item {Box(Modifier.financeResetPrint(resetPrint.value,1,reducedSaveMotion)) {AccountChoice(if(kind==FinanceEditorKind.TRANSFER) "转出账户" else "账户",accounts,accountId,!state.busy && !continuous,recentAccounts,compact=row==null){accountId=it;if(targetId==it)targetId=accounts.firstOrNull {a->a.id!=it && a.archivedAt==null}?.id.orEmpty()}}}
            item {LedgerFilterChip(pickingDate,{pickingDate=!pickingDate},modifier=Modifier.financeResetPrint(resetPrint.value,3,reducedSaveMotion)
                .semantics {contentDescription="实际发生时间 · $occurredAt"},enabled=!state.busy && !continuous) {Text(if(row==null) financeEntryTimeLabel(occurredAt) else occurredAt,style=LedgerText.caption)}}
            if(row==null && kind!=FinanceEditorKind.TRANSFER) {
                item {LedgerFilterChip(moreFields,{moreFields=!moreFields},modifier=Modifier.semantics {contentDescription="标签与新分类"},enabled=!state.busy) {Text("标签",style=LedgerText.caption)}}
                item {LedgerFilterChip(businessOpen,{businessOpen=!businessOpen},enabled=!state.busy) {Text("业务性质",style=LedgerText.caption)}}
            }
        }
        if (kind == FinanceEditorKind.TRANSFER) {
            AccountChoice("转入账户", accounts.filter { it.id != accountId }, targetId, !state.busy && !continuous, recentAccounts) { targetId = it }
            if (accounts.count { it.archivedAt == null } < 2 && row == null) Text("转账需要两个不同的真实账户。", style = LifeText.caption, color = colors.muted)
        }
        if (pickingDate) FinanceDateTimePanel(occurredAt, !state.busy && !continuous,
            onCancel={pickingDate=false},onApply={occurredAt=it;pickingDate=false})
        if (kind != FinanceEditorKind.TRANSFER && (row!=null || moreFields)) {
            TextButton({ pickingCategory = !pickingCategory }, enabled = !state.busy) {
                Text("分类 · " + (FinanceProjection.categoryPath(state.snapshot.categories, selectedCategoryId) ?: category.ifBlank { "选择分类" }))
            }
            if (pickingCategory) FinanceCategoryFields(state.snapshot, selectedCategoryId,
                { selectedCategoryId = it; pickingCategory = false }, state.busy, vm::createCategory)
            if(row!=null) TextButton({ moreFields = !moreFields }, enabled = !state.busy) { Text(if (moreFields) "收起标签与新分类" else "标签与新分类") }
            if (moreFields) {
                selectedCategoryId?.let { FinanceCategoryIconEditor(state, vm, it) }
                OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("标签 · 逗号分隔") }, enabled = !state.busy, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(tags))
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.snapshot.tags.size) { index ->
                        val tag = state.snapshot.tags[index]
                        SuggestionChip({ tags = (tags.split('，', ',').map(String::trim).filter(String::isNotBlank) + tag.name).distinct().joinToString("，") },
                            enabled = !state.busy, label = { Text(tag.name) })
                    }
                }
                OutlinedTextField(category, { category = it; selectedCategoryId = null }, Modifier.fillMaxWidth(), label = { Text("新主分类（可留空）") }, enabled = !state.busy, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(category))
                OutlinedTextField(subcategory, { subcategory = it; selectedCategoryId = null }, Modifier.fillMaxWidth(), label = { Text("新子分类（可留空）") }, enabled = !state.busy, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(subcategory))
            }
        }
        if (kind != FinanceEditorKind.TRANSFER && !multiple && businessOpen) FinanceRelationshipFields(state.snapshot, relationship, !state.busy) { relationship = it }
        if (row != null && row.transfer == null) TextButton({ allocationOpen = !allocationOpen }, enabled = !state.busy) {
            Text(if (multiple) "查看与修改多个事件分配" else "分配到多个事件")
        }
        if (allocationOpen && row != null) FinanceAllocationFields(state, row, vm) { allocationOpen = false }
        if (row != null) {
            state.snapshot.v4?.proposals.orEmpty().filter {it.canonicalId==row.id || it.canonicalId==row.entry.id}.forEach {proposal->
                key(proposal.id) {
                    Text("来源小票 · "+proposal.discoverySourceLabel(),style=LifeText.caption)
                    FinanceAttachments(state.snapshot,FinanceMediaTarget(proposalId=proposal.id),state.busy,state.error) {block->vm.mediaAction(editorEpoch,block)}
                }
            }
            Text("记录时间 ${FinanceTime.input(row.entry.recordedAt)}", style = LifeText.caption, color = colors.muted)
            FinancePostingEditor(state,row,vm,editorEpoch)
            TextButton({ vm.previewVoid(entryId=row.entry.id) {voidPreview=it} }, enabled = !state.busy) { Text("作废这笔记录") }
        }
    }
    if (voidPreview != null && row != null) AlertDialog(onDismissRequest = { if (!state.busy) voidPreview = null },
        title = { Text(if (row.transfer != null) "作废整笔转账？" else "作废这笔记录？") },
        text = { FinanceChangeImpact(requireNotNull(voidPreview)) },
        confirmButton = { TextButton(onClick = { vm.confirmVoid(requireNotNull(voidPreview)) { voidPreview = null; (onSaved ?: onClose)() } }, enabled = !state.busy) { Text("确认作废") } },
        dismissButton = { TextButton(onClick = { voidPreview = null }, enabled = !state.busy) { Text("取消") } })
}
