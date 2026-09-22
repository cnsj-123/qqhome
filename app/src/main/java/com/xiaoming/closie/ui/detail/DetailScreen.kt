package com.xiaoming.closie.ui.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import java.time.LocalDate

@Composable fun DetailScreen(repo:WardrobeRepository,id:String,edit:(String)->Unit,back:()->Unit){var item by remember{mutableStateOf(repo.getItem(id))};var deleting by remember{mutableStateOf(false)};if(item==null){back();return};val value=item!!;Scaffold(topBar={TopAppBar(title={Text(value.name)},navigationIcon={BackButton(back)},actions={TextButton(onClick={edit(id)}){Text("编辑")};TextButton(onClick={deleting=true}){Text("删除")}})}){pad->LazyColumn(Modifier.padding(pad).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{value.images.firstOrNull{it.kind==ImageKind.FLAT}?.localPath?.let{AsyncImage(File(it),value.name,Modifier.fillMaxWidth().height(300.dp),contentScale=ContentScale.Fit)}};item{Section("我的上身效果 | 模特上身效果"){Row{value.images.firstOrNull{it.kind==ImageKind.ME}?.localPath?.let{AsyncImage(File(it),"我",Modifier.weight(1f).height(190.dp),contentScale=ContentScale.Crop)};value.images.firstOrNull{it.kind==ImageKind.MODEL}?.localPath?.let{AsyncImage(File(it),"模特",Modifier.weight(1f).height(190.dp),contentScale=ContentScale.Crop)}}}};item{Section("购买信息"){Text("品牌：${value.brand}");Text("店铺：${value.store}");Text("平台：${value.purchasePlatform}");Text("价格：${value.price?:"未填写"}");Text("购买日：${value.purchaseDate}")}};item{Section("面料与尺寸"){value.materials.forEach{Text("${it.name} ${it.percentage}")};value.measurements.forEach{Text("${it.name} ${it.value}${it.unit}")}}};if(value.status==ItemStatus.OWNED)item{val wears=repo.wearCount(id);Section("使用记录"){Text("穿着 $wears 次 · 洗涤 ${repo.washCount(id)} 次");Text("单次穿着成本：${value.price?.let{if(wears==0)"尚未穿着"else "¥${"%.2f".format(it/wears)}"}?:"未填写价格"}");Button(onClick={repo.addWear(id,LocalDate.now().toString());item=repo.getItem(id)}){Text("今天穿了 +1")};OutlinedButton(onClick={repo.addWash(id,LocalDate.now().toString());item=repo.getItem(id)}){Text("洗过 +1")}}}}};if(deleting)AlertDialog(onDismissRequest={deleting=false},title={Text("删除这件衣服？")},text={Text("关联的本地穿着和洗涤记录也会删除。")},confirmButton={TextButton(onClick={repo.deleteItem(id);back()}){Text("删除")}},dismissButton={TextButton(onClick={deleting=false}){Text("取消")}})} }
