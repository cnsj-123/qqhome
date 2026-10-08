package com.xiaoming.closie.ui.lifeos.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xiaoming.closie.ui.lifeos.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionShellScreen(onBack: () -> Unit) {
    val colors = LocalLifeOsColors.current
    Scaffold(containerColor = colors.paperSecondary, topBar = {
        TopAppBar(title = { Column { Text("伙伴", style = LifeText.title); Text("日常，慢慢聊", style = LifeText.caption, color = colors.muted) } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回 Life OS 首页") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.paper))
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = {}, enabled = false) { Text("历史 · 待接入") }
                TextButton(onClick = {}, enabled = false) { Text("新话题 · 待接入") }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                Text("伙伴连接将在后续工程接入", style = LifeText.body, color = colors.muted)
            }
            Row(Modifier.fillMaxWidth().imePadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = "", onValueChange = {}, enabled = false, modifier = Modifier.weight(1f),
                    placeholder = { Text("连接后，在这里聊天") }, label = { Text("消息输入 · 待连接") })
                TextButton(onClick = {}, enabled = false) { Text("发送") }
            }
        }
    }
}

@Preview(name = "Companion · Unconnected shell", showBackground = true)
@Composable
private fun CompanionShellPreview() { LifeOsTheme { CompanionShellScreen {} } }
