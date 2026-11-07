package com.qq.closie.ui.lifeos.finance

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.qq.closie.life.finance.*
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.components.LifeSurfaceOrigin
import com.qq.closie.ui.lifeos.components.interpolateLifeSurface
import com.qq.closie.ui.lifeos.components.reducedLifeMotion
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors
import kotlin.math.sin

/** This ticket is read from the committed ledger row, never from an optimistic editor copy. */
internal fun financePrintedFields(row: FinanceLedgerRow): List<Pair<String, String>> = listOf(
    "金额" to ((if(row.transfer!=null) "" else if(row.entry.direction==FinanceDirection.INFLOW) "+" else "-") +
        FinanceMoney.display(row.entry.amountMinor,row.currencyCode)),
    "账户" to (row.account.name + (row.targetAccount?.let {" → "+it.name} ?: "")),
    "分类" to (if(row.transfer!=null) "账户转账" else row.category ?: "未分类"),
    "日期" to FinanceTime.input(row.entry.occurredAt),
    "明细" to row.entry.description.ifBlank {"—"}
)

/** A recycled, offscreen, rotated, or absent destination must not manufacture a landing point. */
internal fun financePrintDestination(row:FinanceLedgerRow?, origin:LifeSurfaceOrigin?, orientation:Int, viewport:Rect):Rect? =
    origin?.takeIf {row!=null && row.transfer==null && it.usable(orientation,viewport)}?.rect

/** Ledger and printed paper live in separate Android windows. Compare their positions on screen. */
internal fun View.financeWindowToScreenOffset():Offset {
    val location=IntArray(2)
    getLocationOnScreen(location)
    val screen=Offset(location[0].toFloat(),location[1].toFloat())
    getLocationInWindow(location)
    return screen-Offset(location[0].toFloat(),location[1].toFloat())
}

internal fun Modifier.financeResetPrint(progress:Float,index:Int,reduced:Boolean):Modifier = graphicsLayer {
    val phase=if(reduced) progress else ((progress*280f-index*LedgerMotion.STAGGER)/160f).coerceIn(0f,1f)
    alpha=phase
    translationY=if(reduced) 0f else (1f-phase)*6.dp.toPx()
}

/** 0–150 press/line, 150–390 field print, 400–600 real-row return (or an honest fade). */
@Composable
internal fun FinancePrintedReceipt(row:FinanceLedgerRow?, destination:()->LifeSurfaceOrigin?, reducedMotion:Boolean=reducedLifeMotion(),onPaperMotion:((Float)->Unit)?=null,onClose:()->Unit) {
    val colors=LocalLifeOsColors.current
    val reduced=reducedMotion
    val configuration=LocalConfiguration.current
    val close by rememberUpdatedState(onClose)
    val currentDestination by rememberUpdatedState(destination)
    val progress=remember(row?.id) {Animatable(0f)}
    ReportFinancePaperMotion(onPaperMotion) {
        if(reduced) 1f-progress.value else 1f-((progress.value*600f-400f)/200f).coerceIn(0f,1f)
    }
    LaunchedEffect(row?.id,reduced) {
        if(row==null) return@LaunchedEffect
        progress.animateTo(1f,tween(if(reduced) 150 else 600,easing=LinearEasing))
        close()
    }
    Dialog(onDismissRequest={close()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val dialogView=LocalView.current
        val window=(dialogView.parent as? DialogWindowProvider)?.window
        var paperHost by remember {mutableStateOf<LayoutCoordinates?>(null)}
        SideEffect {window?.setDimAmount(0f)}
        BackHandler {close()}
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned {paperHost=it}) {
            val width=constraints.maxWidth.toFloat();val height=constraints.maxHeight.toFloat()
            val source=Rect(0f,height*.134f,width,height)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=if(onPaperMotion!=null) 0f else .08f*(1f-progress.value)))
                .clickable {close()})
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(maxHeight*.866f)
                .graphicsLayer {
                    val t=progress.value
                    if(reduced) {alpha=1f-t}
                    else {
                        val returnProgress=((t*600f-400f)/200f).coerceIn(0f,1f)
                        val host=paperHost?.takeIf {it.isAttached}
                        val target=if(returnProgress>0f && host!=null) {
                            val hostOrigin=host.localToWindow(Offset.Zero)+dialogView.financeWindowToScreenOffset()
                            val viewport=Rect(hostOrigin,hostOrigin+Offset(width,height))
                            financePrintDestination(row,currentDestination(),configuration.orientation,viewport)?.translate(-hostOrigin)
                        } else null
                        if(target!=null) {
                            val bounds=interpolateLifeSurface(source,target,LedgerMotion.EasePaper.transform(returnProgress))
                            translationX=bounds.center.x-source.center.x
                            translationY=bounds.center.y-source.center.y-sin(returnProgress*Math.PI).toFloat()*20.dp.toPx()
                            scaleX=bounds.width/source.width
                            scaleY=bounds.height/source.height
                            alpha=1f-((returnProgress-.8f)/.2f).coerceIn(0f,1f)
                        } else alpha=1f-returnProgress
                    }
                }.testTag("finance-printed-receipt").semantics {paneTitle="记账完成"},shape=ManualReceiptShape,color=colors.paper) {
                Column(Modifier.fillMaxSize().padding(horizontal=32.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets(bottom=24.dp))).padding(top=52.dp)) {
                    Text("已记入账簿",style=LedgerText.title,color=colors.ink)
                    Spacer(Modifier.height(24.dp))
                    row?.let {actual -> financePrintedFields(actual).forEachIndexed {index,field->
                        val fieldProgress=if(reduced) 1f else ((progress.value*600f-150f-index*LedgerMotion.STAGGER)/120f).coerceIn(0f,1f)
                        Row(Modifier.fillMaxWidth().padding(vertical=9.dp).graphicsLayer {
                            alpha=fieldProgress
                            translationY=if(reduced) 0f else (1f-fieldProgress)*6.dp.toPx()
                        },verticalAlignment=Alignment.CenterVertically) {
                            Text(field.first,Modifier.width(56.dp),style=LedgerText.caption,color=colors.inkSecondary)
                            Text(field.second,Modifier.weight(1f),style=if(index==0) LedgerText.amount else LedgerText.body,color=colors.ink)
                        }
                    }}
                    Spacer(Modifier.weight(1f))
                    val line=if(reduced) 1f else (progress.value*600f/150f).coerceIn(0f,1f)
                    Box(Modifier.fillMaxWidth().height(46.dp),contentAlignment=Alignment.Center) {
                        Box(Modifier.fillMaxWidth().height(2.dp).graphicsLayer {scaleX=line;alpha=1f-progress.value}.background(colors.accent))
                    }
                }
            }
        }
    }
}
