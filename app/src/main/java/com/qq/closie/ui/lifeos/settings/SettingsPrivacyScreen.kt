package com.qq.closie.ui.lifeos.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qq.closie.life.finance.FinanceRepository
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.finance.FinancePresentationFactory
import com.qq.closie.ui.lifeos.finance.FinancePurposePermissions
import com.qq.closie.ui.lifeos.finance.FinanceViewModel
import com.qq.closie.ui.lifeos.theme.LifeText
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPrivacyScreen(finance: FinanceRepository, onBack: () -> Unit) {
    val vm: FinanceViewModel = viewModel(factory = remember(finance) { FinancePresentationFactory(finance) })
    val state by vm.state.collectAsStateWithLifecycle()
    var financeOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = !financeOpen, onBack = onBack)
    val colors = LocalLifeOsColors.current
    SettingsPaperPage("隐私与 AI 权限", onBack) {
            Text("你能看，不等于 AI 能读；能进首页，不等于能分享。", style = SettingsText.detail, color = colors.inkSecondary)
            Row(Modifier.fillMaxWidth().padding(top=30.dp,bottom=10.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("模块的 AI 访问权限",style=SettingsText.caption,color=colors.inkSecondary)
                Text("点模块查看授权",style=SettingsText.detail,color=colors.inkSecondary)
            }
            val now = System.currentTimeMillis()
            val activePurposes = state.snapshot.v16?.purposeGrantRows.orEmpty()
                .filter { it.voidedAt == null && it.expiresAt > now }.map { it.purpose }.distinct().size
            val status = when {
                !state.loaded -> "读取授权"
                activePurposes == 0 -> "按用途管理"
                else -> "$activePurposes 项已授权"
            }
            FinanceModulePermissionControl(state.snapshot.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L, state.loaded)
            SettingsLine("健康 Health", icon=Icons.Outlined.FavoriteBorder, trailing="未接入",modifier=Modifier.height(50.dp))
            SettingsLine("知识库 Knowledge", icon=Icons.Outlined.MenuBook, trailing="未接入",modifier=Modifier.height(50.dp))
            SettingsLine("衣橱 / 物品", icon=Icons.Outlined.Checkroom, trailing="未接入",modifier=Modifier.height(50.dp))
            SettingsLine("兴趣 / 阅读", icon=Icons.Outlined.StarBorder, trailing="未接入",modifier=Modifier.height(50.dp))
            Text("授权记录与隐私",Modifier.padding(top=13.dp,bottom=8.dp),style=SettingsText.body.copy(fontSize=13.sp,lineHeight=18.sp),color=colors.inkSecondary)
            SettingsLine("AI 辅助搜索", "独立于普通搜索；外部 AI 尚未接入", trailing="未接入")
            SettingsLine("普通资料后台识别", "Capture / Knowledge 的后台整理尚未接入", trailing="未接入")
            SettingsLine("单条记录隐私", "AI 可见 / Home 展示 / 分享导出分别控制", trailing=status) {financeOpen=true}
            SettingsLine("授权历史与撤销", "查看财务用途、有效期与撤销记录") {financeOpen=true}
            Text("独立加密区",Modifier.padding(top=24.dp,bottom=10.dp),style=SettingsText.body.copy(fontSize=13.sp,lineHeight=18.sp),color=colors.inkSecondary)
            Surface(color=colors.paperTertiary,shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.Icon(Icons.Outlined.Lock,null,Modifier.size(17.dp),tint=colors.accent)
                    Column {
                        Text("Creative Vault",style=SettingsText.body.copy(fontSize=16.sp,lineHeight=23.sp),color=colors.ink)
                        Text("AI 禁止访问 · 固定隔离",Modifier.padding(top=6.dp),style=SettingsText.caption,color=colors.accentDeep)
                        Text("独立权限。财务授权与明文携出不包含 Vault。",Modifier.padding(top=7.dp),style=SettingsText.caption,color=colors.inkSecondary)
                    }
                }
            }
    }
    if (financeOpen) ModalBottomSheet(onDismissRequest = { if (!state.busy) financeOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { !state.busy || it != androidx.compose.material3.SheetValue.Hidden }),
        containerColor = colors.paper) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .82f).dp)
            .verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text("财务授权", style = LifeText.title, color = colors.ink)
            Text("首页、分享和本地查询按用途、字段与时效分别授权。", style = LifeText.caption, color = colors.muted)
            state.error?.let { Text(it, style = LifeText.caption, color = MaterialTheme.colorScheme.error) }
            if (state.loading) Text("正在读取授权…", style = LifeText.caption)
            else FinancePurposePermissions(state, vm)
        }
    }
}
