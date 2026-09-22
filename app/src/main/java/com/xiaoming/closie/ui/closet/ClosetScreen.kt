package com.xiaoming.closie.ui.closet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.xiaoming.closie.data.model.*
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.*
import java.io.File

@Composable fun ClosetScreen(repo:WardrobeRepository,status:ItemStatus,open:(String)->Unit,add:()->Unit,back:()->Unit){var query by remember{mutableStateOf("")};var category by remember{mutableStateOf("")};val items=repo.listItems().filter{it.status==status&&it.name.contains(query,true)&&(category.isBlank()||it.category==category)};Scaffold(topBar={TopAppBar(title={Text(if(status==ItemStatus.OWNED)"我的衣橱"else"试过 / 退货")},navigationIcon={BackButton(back)})},floatingActionButton={FloatingActionButton(onClick=add,containerColor=Rose){Text("+")}}){pad->Column(Modifier.padding(pad).padding(12.dp)){OutlinedTextField(query,{query=it},label={Text("搜索名称")},modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(8.dp));LazyVerticalGrid(GridCells.Fixed(2),verticalArrangement=Arrangement.spacedBy(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){items(items){item->Card(modifier=Modifier.clickable{open(item.id)}){Column{val img=item.images.firstOrNull{it.kind==ImageKind.FLAT}?:item.images.firstOrNull();if(img?.localPath!=null)AsyncImage(File(img.localPath),item.name,Modifier.fillMaxWidth().height(150.dp),contentScale=if(img.kind==ImageKind.FLAT)ContentScale.Fit else ContentScale.Crop);Text(item.name,Modifier.padding(10.dp));Text(item.category,Modifier.padding(horizontal=10.dp),style=MaterialTheme.typography.bodySmall,color=Rose);item.price?.let{Text("¥$it",Modifier.padding(10.dp),style=MaterialTheme.typography.bodySmall)}}}}}}}
