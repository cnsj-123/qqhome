package com.xiaoming.closie.ui.lifeos.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiaoming.closie.ui.lifeos.theme.LifeText
import com.xiaoming.closie.ui.lifeos.theme.LocalLifeOsColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifePage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalLifeOsColors.current
    Scaffold(containerColor = colors.paper, topBar = {
        TopAppBar(title = { Text(title, style = LifeText.title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一页") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.paper))
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), content = content)
    }
}
