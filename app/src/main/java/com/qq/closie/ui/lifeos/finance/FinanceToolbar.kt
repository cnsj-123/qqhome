package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.ui.lifeos.theme.*

/** A single query field; its value stays owned by the module/Finance filter. */
@Composable
internal fun FinanceToolbar(monthLabel: String, query: String, searchHint: String,
    onBack: () -> Unit, onMonth: () -> Unit, onQuery: (String) -> Unit,
    actions: @Composable RowScope.() -> Unit) {
    val colors = LocalLifeOsColors.current
    val focus = LocalFocusManager.current
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End) {
        BasicTextField(query, onQuery,
            Modifier.widthIn(min = 94.dp, max = 110.dp).height(28.dp).testTag("finance-search")
                .semantics { contentDescription = searchHint }
                .border(1.dp, colors.line, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp),
            singleLine = true, textStyle = LedgerText.input.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.accentDeep),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            decorationBox = { field ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(13.dp), tint = colors.inkSecondary)
                    Box(Modifier.weight(1f).padding(start = 5.dp)) {
                        if (query.isEmpty()) Text("搜索", style = LedgerText.caption, color = colors.inkSecondary, maxLines = 1)
                        field()
                    }
                }
            })
        Spacer(Modifier.width(8.dp))
        actions()
    }
}

internal enum class FinanceFilterDimension(val label: String) { ACCOUNT("账户"), CATEGORY("分类"), TAG("标签"), NATURE("性质") }
