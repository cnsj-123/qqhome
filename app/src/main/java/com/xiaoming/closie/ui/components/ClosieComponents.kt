package com.xiaoming.closie.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.xiaoming.closie.ui.theme.ClosieColor
import java.io.File

// ------------------------------------------------------------------------------------------------
// Buttons
// ------------------------------------------------------------------------------------------------

@Composable
fun ClosieBackButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Text(
            text = "‹",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ------------------------------------------------------------------------------------------------
// Search
// ------------------------------------------------------------------------------------------------

@Composable
fun ClosieSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(999.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(ClosieColor.Surface)
            .border(1.dp, ClosieColor.Hairline, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = ClosieColor.Ink),
        singleLine = true,
        decorationBox = { innerTextField ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Search, contentDescription = null, tint = ClosieColor.InkTertiary)
                Box(Modifier.weight(1f)) {
                    if (value.isBlank()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = ClosieColor.InkTertiary)
                    }
                    innerTextField()
                }
                if (value.isNotBlank()) {
                    IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "清除搜索", tint = ClosieColor.InkTertiary)
                    }
                }
            }
        }
    )
}

// ------------------------------------------------------------------------------------------------
// Chips
// ------------------------------------------------------------------------------------------------

@Composable
fun ClosieFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) ClosieColor.Rose else ClosieColor.Surface
    val content = if (selected) ClosieColor.Surface else ClosieColor.Ink
    val borderColor = if (selected) ClosieColor.Rose else ClosieColor.Hairline
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics {
                role = Role.Checkbox
                this.selected = selected
                contentDescription = "$label ${if (selected) "已选中" else "未选中"}"
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

// ------------------------------------------------------------------------------------------------
// Section header
// ------------------------------------------------------------------------------------------------

@Composable
fun ClosieSectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = ClosieColor.Ink)
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel, color = ClosieColor.Rose) }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Collapsible section
// ------------------------------------------------------------------------------------------------

@Composable
fun ClosieCollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ClosieColor.Surface)
            .border(1.dp, ClosieColor.Hairline, RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Rose)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Picker field (visual primitive)
// ------------------------------------------------------------------------------------------------

@Composable
fun ClosiePickerField(
    label: String,
    value: String,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkSecondary)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(shape)
                .background(ClosieColor.Surface)
                .border(1.dp, ClosieColor.Hairline, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = value.ifBlank { placeholder },
                style = MaterialTheme.typography.bodyLarge,
                color = if (value.isBlank()) ClosieColor.InkTertiary else ClosieColor.Ink
            )
            Text("›", style = MaterialTheme.typography.bodyLarge, color = ClosieColor.InkTertiary)
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Empty state
// ------------------------------------------------------------------------------------------------

@Composable
fun ClosieEmptyState(
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = ClosieColor.Ink)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkSecondary)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = ClosieColor.Rose, contentColor = ClosieColor.Surface)
            ) { Text(actionLabel) }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Image tile
// ------------------------------------------------------------------------------------------------

@Composable
fun ClosieImageTile(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(ClosieColor.SurfaceSoft)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (selected) Modifier.border(2.dp, ClosieColor.Rose, shape) else Modifier)
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        } else if (placeholder != null) {
            placeholder()
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(ClosieColor.Rose),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", color = ClosieColor.Surface, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Info row
// ------------------------------------------------------------------------------------------------

@Composable
fun ClosieInfoRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier
) {
    if (value.isNullOrBlank()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Ink)
    }
}

// ------------------------------------------------------------------------------------------------
// Bottom action bar
// ------------------------------------------------------------------------------------------------

@Composable
fun ClosieBottomActionBar(
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = ClosieColor.Surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (secondaryLabel != null && onSecondary != null) {
                OutlinedButton(
                    onClick = onSecondary,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(secondaryLabel) }
            }
            Button(
                onClick = onPrimary,
                enabled = primaryEnabled,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClosieColor.Rose, contentColor = ClosieColor.Surface)
            ) { Text(primaryLabel) }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Helpers
// ------------------------------------------------------------------------------------------------

fun priceText(price: Double): String =
    "¥" + if (price == price.toLong().toDouble()) price.toLong().toString() else price.toString()
