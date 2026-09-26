package com.qq.closie.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.qq.closie.ui.theme.ClosieColor
import com.qq.closie.ui.theme.ClosieTheme

/** A one-line, tap-to-choose field: label on the left, value (or placeholder) on the right. */
@Composable
fun SmartPickerField(
    label: String,
    value: String,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = ClosieColor.Ink)
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (value.isBlank()) placeholder else value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (value.isBlank()) ClosieColor.InkTertiary else ClosieColor.Ink,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(4.dp))
        Text("›", style = MaterialTheme.typography.bodyLarge, color = ClosieColor.InkTertiary)
    }
}

/** Lightweight progressive-disclosure section: title + optional summary + chevron. */
@Composable
fun EditorSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = ClosieColor.Ink)
                if (!expanded && summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = ClosieColor.InkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                if (expanded) "˅" else "›",
                style = MaterialTheme.typography.titleMedium,
                color = ClosieColor.InkTertiary
            )
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

@Preview(widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun SmartPickerFieldPreview() {
    ClosieTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SmartPickerField(label = "品牌", value = "AURALEE", placeholder = "选择品牌", onClick = {})
            SmartPickerField(label = "尺码", value = "", placeholder = "选择尺码", onClick = {})
            EditorSection(title = "购买信息", expanded = false, onToggle = {}, summary = "淘宝 · ¥599") {}
            EditorSection(title = "衣物详情", expanded = true, onToggle = {}) {}
        }
    }
}
