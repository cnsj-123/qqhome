package com.xiaoming.closie.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
val Rose=Color(0xFF765661);val CardWhite=Color.White
@Composable fun BackButton(back:()->Unit){IconButton(onClick=back){Text("‹",style=MaterialTheme.typography.headlineMedium)}}
@Composable fun Section(title:String,content:@Composable ColumnScope.()->Unit){Card(colors=CardDefaults.cardColors(containerColor=CardWhite)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(title,style=MaterialTheme.typography.titleMedium);content()}}}
