package com.qq.closie.ui.lifeos.finance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.components.reducedLifeMotion
import com.qq.closie.ui.lifeos.theme.*

/** The chosen branch inserts below its real root row; it never replaces the root grid. */
@Composable
internal fun FinanceInlineCategories(snapshot: FinanceSnapshot, selectedId: String?, busy: Boolean,
    onSelect: (String) -> Unit) {
    val tree = remember(snapshot.categories) { FinanceCategoryTree(snapshot.categories) }
    var expanded by rememberSaveable { mutableStateOf(tree.root(selectedId)?.id) }
    val selectedRoot = tree.root(selectedId)?.id
    LaunchedEffect(selectedRoot) { expanded = selectedRoot }
    val colors = LocalLifeOsColors.current
    val reduced = reducedLifeMotion()
    val roots = tree.roots()
    if (roots.isEmpty()) Text("尚无分类，可在下方创建。", style = LifeText.caption, color = colors.muted)
    roots.chunked(5).forEach { rootRow ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            rootRow.forEach { category ->
                FinanceCategoryTile(snapshot, category, tree.root(selectedId)?.id == category.id, busy,
                    Modifier.weight(1f)) {
                    onSelect(category.id)
                    expanded = if (expanded == category.id) null else category.id
                }
            }
            repeat(5 - rootRow.size) { Spacer(Modifier.weight(1f)) }
        }
        val branch = expanded?.takeIf { id -> rootRow.any { it.id == id } }
        val children = branch?.let(tree::children).orEmpty()
        AnimatedVisibility(children.isNotEmpty(), enter = expandVertically(tween(if (reduced) 0 else 190)) + fadeIn(tween(if (reduced) 0 else 150)),
            exit = shrinkVertically(tween(if (reduced) 0 else 150)) + fadeOut(tween(if (reduced) 0 else 150))) {
            Column(Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(14.dp)).padding(5.dp)) {
                children.chunked(5).forEachIndexed { rowIndex, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEachIndexed { index, category ->
                            var shown by remember(branch, category.id) { mutableStateOf(false) }
                            LaunchedEffect(branch, category.id) { shown = true }
                            val progress by animateFloatAsState(if (shown) 1f else 0f,
                                tween(if (reduced) 0 else 150, if (reduced) 0 else ((rowIndex * 5 + index) * 25).coerceAtMost(100)), label = "category-unfold")
                            FinanceCategoryTile(snapshot, category, selectedId == category.id, busy,
                                Modifier.weight(1f).graphicsLayer { alpha = progress; translationY = (1f - progress) * (-5).dp.toPx() }) {
                                onSelect(category.id)
                            }
                        }
                        repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FinanceCategoryTile(snapshot: FinanceSnapshot, category: FinanceCategoryEntity, selected: Boolean,
    busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalLifeOsColors.current
    Column(modifier.testTag("entry-category-${category.id}").semantics { this.selected = selected }
        .clickable(enabled = !busy, role = Role.Button, onClick = onClick).padding(vertical = 5.dp)
        .heightIn(min = 58.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(38.dp).background(if (selected) colors.accent else colors.paperTertiary, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(FinanceCategoryGlyph.forCategory(snapshot, category.id, category.name), null, Modifier.size(21.dp),
                tint = if (selected) colors.onAccent else colors.accentDeep)
        }
        Text(category.name, maxLines = 2, style = LifeText.caption, color = colors.inkSecondary)
    }
}
