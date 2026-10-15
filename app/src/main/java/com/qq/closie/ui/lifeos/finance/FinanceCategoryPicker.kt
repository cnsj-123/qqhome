package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.LifeText

@Composable
internal fun FinanceCategoryPicker(snapshot: FinanceSnapshot, selected: String?, onSelect: (String) -> Unit, onClose: () -> Unit,
    busy: Boolean, error: String?, onCreateChild: (String, String, (String) -> Unit) -> Unit) {
    FinanceReceipt("分类", busy, error, onClose) {
        FinanceCategoryFields(snapshot, selected, { onSelect(it); onClose() }, busy, onCreateChild)
    }
}
