package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.qq.closie.R
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors

/** The measured 32dp paper gutter and shared back/title line of Finance's secondary pages. */
@Composable
internal fun FinanceAuxTopBar(title: String, onBack: () -> Unit, enabled: Boolean = true, actions: @Composable RowScope.() -> Unit = {}) {
    val colors = LocalLifeOsColors.current
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 32.dp, top = 8.dp)
        .heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, Modifier.size(48.dp), enabled = enabled) {
            Icon(painterResource(R.drawable.lifeos_finance_account_back), "返回", Modifier.size(24.dp), tint = colors.inkSecondary)
        }
        Text(title, Modifier.weight(1f).padding(start = 4.dp), style = LedgerText.title, color = colors.ink)
        actions()
    }
}
