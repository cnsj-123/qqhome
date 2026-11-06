package com.qq.closie.ui.lifeos.finance

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.components.reducedLifeMotion
import com.qq.closie.ui.lifeos.theme.*

private val entryRootNames=listOf("餐饮","交通","购物","居家","咖啡","园艺","人情","医疗","学习")
private val entryFoodNames=listOf("早餐","午餐","晚餐","下午茶","宵夜")
private data class EntryCategoryOption(val key:String,val name:String,val canonical:FinanceCategoryEntity?)

/** Template choices are draft input strings, never invented canonical IDs or financial records. */
@Composable
internal fun FinanceInlineCategories(snapshot: FinanceSnapshot, selectedId: String?, busy: Boolean,
    selectedName:String="",selectedChild:String="",onTemplate:((String,String?)->Unit)?=null,
    onSelect: (String) -> Unit) {
    val tree=remember(snapshot.categories){FinanceCategoryTree(snapshot.categories)}
    val roots=remember(tree,onTemplate!=null) {
        val real=tree.roots()
        if(onTemplate==null) real.map {EntryCategoryOption(it.id,it.name,it)}
        else entryRootNames.flatMap {name->
            real.filter {it.name==name}.map {EntryCategoryOption(it.id,it.name,it)}
                .ifEmpty {listOf(EntryCategoryOption("template:"+name,name,null))}
        }+real.filter {it.name !in entryRootNames}.map {EntryCategoryOption(it.id,it.name,it)}
    }
    val selectedRoot=tree.root(selectedId)?.id ?: roots.singleOrNull {it.name==selectedName}?.key
    var expanded by rememberSaveable {mutableStateOf(selectedRoot)}
    LaunchedEffect(selectedRoot){expanded=selectedRoot}
    val colors=LocalLifeOsColors.current
    val reduced=reducedLifeMotion()
    Spacer(Modifier.height(22.dp))
    if(roots.isEmpty()) Text("尚无分类，可在下方创建。",style=LedgerText.caption,color=colors.inkSecondary)
    roots.chunked(5).forEach {rootRow->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            rootRow.forEach {option->
                FinanceCategoryTile(snapshot,option,selectedRoot==option.key,busy,Modifier.weight(1f)) {
                    option.canonical?.let {onSelect(it.id)} ?: onTemplate?.invoke(option.name,null)
                    expanded=if(expanded==option.key) null else option.key
                }
            }
            repeat(5-rootRow.size){Spacer(Modifier.weight(1f))}
        }
        val root=rootRow.firstOrNull {it.key==expanded}
        val children=remember(root,tree,onTemplate!=null) {
            val real=root?.canonical?.let {tree.children(it.id)}.orEmpty()
            val options=real.map {EntryCategoryOption(it.id,it.name,it)}
            if(root?.name=="餐饮" && onTemplate!=null && tree.roots().count {it.name==root.name}<=1)
                entryFoodNames.map {name->options.firstOrNull {it.name==name} ?: EntryCategoryOption("template:餐饮/"+name,name,null)}+
                    options.filter {it.name !in entryFoodNames}
            else options
        }
        AnimatedVisibility(children.isNotEmpty(),enter=if(reduced) fadeIn(tween(150)) else expandVertically(spring(stiffness=380f,dampingRatio=.75f))+fadeIn(tween(150)),
            exit=if(reduced) fadeOut(tween(150)) else shrinkVertically(spring(stiffness=380f,dampingRatio=.75f))+fadeOut(tween(150))) {
            Column(Modifier.fillMaxWidth().background(colors.card,RoundedCornerShape(14.dp)).padding(5.dp)) {
                children.chunked(5).forEachIndexed {rowIndex,row->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        row.forEachIndexed {index,option->
                            var shown by remember(root?.key,option.key){mutableStateOf(false)}
                            LaunchedEffect(root?.key,option.key){shown=true}
                            val progress by animateFloatAsState(if(shown) 1f else 0f,tween(if(reduced) 150 else 150,
                                if(reduced) 0 else ((rowIndex*5+index)*30).coerceAtMost(120)),label="category-unfold")
                            FinanceCategoryTile(snapshot,option,selectedId==option.canonical?.id && selectedId!=null ||
                                selectedId==null && selectedName==root?.name && selectedChild==option.name,busy,
                                Modifier.weight(1f).graphicsLayer {alpha=progress;translationY=if(reduced) 0f else (1-progress)*(-5).dp.toPx()},child=true) {
                                option.canonical?.let {onSelect(it.id)} ?: root?.let {onTemplate?.invoke(it.name,option.name)}
                            }
                        }
                        repeat(5-row.size){Spacer(Modifier.weight(1f))}
                    }
                }
            }
        }
    }
}

@Composable
private fun FinanceCategoryTile(snapshot:FinanceSnapshot,option:EntryCategoryOption,selected:Boolean,busy:Boolean,
    modifier:Modifier,child:Boolean=false,onClick:()->Unit) {
    val colors=LocalLifeOsColors.current
    val haptic=androidx.compose.ui.platform.LocalHapticFeedback.current
    val source=remember {androidx.compose.foundation.interaction.MutableInteractionSource()}
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if(pressed) .9f else 1f,LedgerMotion.SpringSnappy,label="category-press")
    Column(modifier.testTag("entry-category-"+option.key).semantics {this.selected=selected}
        .clickable(enabled=!busy,role=Role.Button,interactionSource=source,indication=null) {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove);onClick()
        }.padding(vertical=5.dp).heightIn(min=if(child) 56.dp else 68.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(if(child) 30.dp else 44.dp).graphicsLayer {scaleX=scale;scaleY=scale}
            .background(if(selected) androidx.compose.ui.graphics.lerp(colors.paper,colors.accent,.45f) else colors.paperTertiary,CircleShape),
            contentAlignment=Alignment.Center) {
            Icon(option.canonical?.let {FinanceCategoryGlyph.forCategory(snapshot,it.id,it.name)} ?: FinanceCategoryGlyph.forName(option.name),
                null,Modifier.size(21.dp),tint=if(selected) colors.onAccent else colors.accentDeep)
        }
        Text(option.name,maxLines=2,style=LedgerText.caption,color=colors.inkSecondary)
    }
}
