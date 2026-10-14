package com.qq.closie.ui.lifeos.drawer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.qq.closie.ui.lifeos.components.lifeHorizontalSwipe
import com.qq.closie.ui.lifeos.theme.*

@Composable
fun LifeOsDrawer(onOpenModule: (LifeModule) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalLifeOsColors.current
    Box(modifier.fillMaxHeight().lifeHorizontalSwipe(onRight = onClose)) {
        LazyColumn(Modifier.testTag("life-drawer"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("生活的抽屉", Modifier.weight(1f), style = LifeText.title, color = colors.ink)
                    Surface(shape = CircleShape, color = colors.paperSecondary, border = BorderStroke(1.dp, colors.line)) {
                        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "收起生活的抽屉", tint = colors.inkSecondary) }
                    }
                }
                Text("水母来信 · 把日子放回自己的位置", style = LifeText.caption, color = colors.muted)
                Spacer(Modifier.height(20.dp))
                Surface(onClick = { onOpenModule(LifeModules.find("search")) },
                    shape = RoundedCornerShape(22.dp), color = colors.card,
                    border = BorderStroke(1.dp, colors.line)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Search, null, tint = colors.muted)
                        Spacer(Modifier.width(10.dp))
                        Text("搜一件东西、一段经历……", style = LifeText.caption, color = colors.inkSecondary)
                    }
                }
            }
            LifeModules.groups.forEachIndexed { groupIndex, group ->
                item(key = group.label) {
                    Text(group.label, Modifier.padding(top = 26.dp, bottom = 12.dp), style = LifeText.caption, color = colors.muted)
                }
                group.modules.chunked(2).forEach { pair ->
                    item(key = pair.first().id) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { module ->
                                ModuleCell(module, groupIndex == 0, { onOpenModule(module) }, Modifier.weight(1f))
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            item { Text("右滑或点击露出的首页，收起抽屉。", Modifier.padding(top = 26.dp), style = LifeText.caption, color = colors.muted) }
        }
    }
}

@Composable
private fun ModuleCell(module: LifeModule, quick: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = LocalLifeOsColors.current
    Box(modifier.padding(bottom = if (quick) 10.dp else 0.dp), contentAlignment = Alignment.CenterStart) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (quick) 62.dp else 58.dp)
            .testTag("drawer-${module.id}").clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(module.icon(), null, Modifier.size(22.dp), tint = colors.accentDeep)
            Spacer(Modifier.width(10.dp))
            Text(module.label, style = LifeText.body, color = colors.ink)
        }
    }
}

@Preview(name = "Life OS · Drawer", showBackground = true, widthDp = 330, heightDp = 820)
@Composable
private fun LifeOsDrawerPreview() {
    LifeOsTheme { Surface(color = LocalLifeOsColors.current.paperSecondary) { LifeOsDrawer({}, {}) } }
}
