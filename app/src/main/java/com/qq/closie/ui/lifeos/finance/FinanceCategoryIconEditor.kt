package com.qq.closie.ui.lifeos.finance

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.qq.closie.life.finance.FinanceBurden
import com.qq.closie.life.ui.theme.BundledText as Text

@Composable
internal fun FinanceCategoryIconEditor(state: FinanceUiState, vm: FinanceViewModel, categoryId: String) {
    var open by rememberSaveable(categoryId) { mutableStateOf(false) }
    val icon = state.snapshot.v16?.categoryIconRows.orEmpty().singleOrNull { it.categoryId == categoryId && it.voidedAt == null }
    var selected by rememberSaveable(categoryId, icon?.revision) { mutableStateOf(icon?.iconKey ?: "other") }
    TextButton({ open = !open }, enabled = !state.busy) { Text("选择分类图标") }
    if (open) {
        FinanceCategoryIconPicker(selected, !state.busy, state.snapshot.v16?.categoryIconRows.orEmpty()
            .filter { it.voidedAt == null }.sortedByDescending { it.updatedAt }.map { it.iconKey }) { selected = it }
        TextButton({
            val v = state.snapshot.v16
            val policy = v?.categoryPolicyRows.orEmpty().singleOrNull { it.categoryId == categoryId && it.voidedAt == null }
            val epoch = v?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L
            vm.workspaceAction(epoch, done = { open = false }) { repo, token ->
                repo.setCategoryPolicy(token, categoryId, policy?.burden ?: FinanceBurden.FLEXIBLE, selected, policy?.revision)
            }
        }, enabled = !state.busy) { Text("保存分类图标") }
    }
}
