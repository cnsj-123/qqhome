package com.qq.closie.ui.lifeos.drawer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.qq.closie.ui.lifeos.components.lifeHorizontalSwipe
import com.qq.closie.ui.lifeos.theme.*

@Composable
fun LifeOsDrawer(onOpenModule: (LifeModule) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalLifeOsColors.current
    LazyColumn(modifier.fillMaxHeight().lifeHorizontalSwipe(onRight = onClose),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("生活的抽屉", Modifier.weight(1f), style = LifeText.title, color = colors.ink)
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "收起生活的抽屉") }
            }
            Text("把日子放回自己的位置", style = LifeText.caption, color = colors.muted)
            Spacer(Modifier.height(20.dp))
        }
        LifeModules.groups.forEach { group ->
            item(key = group.label) {
                Text(group.label, Modifier.padding(top = 16.dp, bottom = 8.dp), style = LifeText.caption, color = colors.muted)
                HorizontalDivider(color = colors.line)
            }
            group.modules.forEach { module ->
                item(key = module.id) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClick = { onOpenModule(module) })
                        .padding(vertical = 10.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(module.label, Modifier.weight(1f), style = LifeText.body, color = colors.ink)
                        Text("›", style = LifeText.title, color = colors.accentDeep)
                    }
                }
            }
        }
        item { Text("右滑或点击露出的首页，收起抽屉。", Modifier.padding(top = 26.dp), style = LifeText.caption, color = colors.muted) }
    }
}

@Preview(name = "Life OS · Drawer", showBackground = true, widthDp = 310, heightDp = 820)
@Composable
private fun LifeOsDrawerPreview() {
    LifeOsTheme { Surface(color = LocalLifeOsColors.current.paperSecondary) { LifeOsDrawer({}, {}) } }
}
