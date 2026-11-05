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
            Text("数据用途由你决定。授权可撤销，外部 AI 连接尚未接入。", style = LifeText.caption, color = colors.muted)
            SettingsSection("模块与用途")
            val now = System.currentTimeMillis()
            val activePurposes = state.snapshot.v16?.purposeGrantRows.orEmpty()
                .filter { it.voidedAt == null && it.expiresAt > now }.map { it.purpose }.distinct().size
            val status = when {
                !state.loaded -> "读取授权"
                activePurposes == 0 -> "按用途管理"
                else -> "$activePurposes 项已授权"
            }
            FinanceModulePermissionControl(state.snapshot.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L, state.loaded)
            SettingsLine("财务用途与字段", "首页展示、分享、本地查询与授权记录", Icons.Outlined.FactCheck, status) {
                financeOpen = true
            }
            SettingsLine("健康 Health", "AI 模块访问", Icons.Outlined.FavoriteBorder, "未接入")
            SettingsLine("资料库 Knowledge", "AI 模块访问", Icons.Outlined.MenuBook, "未接入")
            SettingsLine("衣橱与物品", "AI 模块访问", Icons.Outlined.Checkroom, "未接入")
            SettingsLine("兴趣与阅读", "AI 模块访问", Icons.Outlined.StarBorder, "未接入")
            SettingsSection("授权记录与隐私")
            SettingsLine("外部 AI", "外部读取及发送能力尚未接入", Icons.Outlined.CloudOff, "关闭")
            SettingsLine("授权历史与撤销", "查看财务用途、有效期和撤销记录", Icons.Outlined.History) { financeOpen = true }
            SettingsSection("独立加密区")
            Surface(color = colors.paperTertiary, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Creative Vault", style = LifeText.body, color = colors.ink)
                    Text("独立权限。财务授权不会扩展到 Vault，也不包含在财务明文携出中。", style = LifeText.caption, color = colors.inkSecondary)
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
