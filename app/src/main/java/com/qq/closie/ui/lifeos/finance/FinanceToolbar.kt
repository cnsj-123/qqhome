package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.background
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
    onAdd: () -> Unit, canAdd: Boolean, actions: @Composable RowScope.() -> Unit) {
    val colors = LocalLifeOsColors.current
    val focus = LocalFocusManager.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
        TextButton(onMonth, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(monthLabel, style = LifeText.caption, maxLines = 2)
        }
        BasicTextField(query, onQuery,
            Modifier.weight(1f).heightIn(min = 44.dp).testTag("finance-search")
                .semantics { contentDescription = searchHint }
                .background(colors.card, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp),
            singleLine = true, textStyle = LifeType.inputFor(query).copy(color = colors.ink),
            cursorBrush = SolidColor(colors.accentDeep),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            decorationBox = { field ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(16.dp), tint = colors.muted)
                    Box(Modifier.weight(1f).padding(start = 4.dp)) {
                        if (query.isEmpty()) Text(searchHint, style = LifeText.caption, color = colors.muted, maxLines = 1)
                        field()
                    }
                }
            })
        actions()
        IconButton(onAdd, enabled = canAdd, modifier = Modifier.testTag("finance-add")) {
            Icon(Icons.Outlined.Add, "记一笔")
        }
    }
}

internal enum class FinanceFilterDimension(val label: String) { ACCOUNT("账户"), CATEGORY("分类"), TAG("标签"), NATURE("性质") }
