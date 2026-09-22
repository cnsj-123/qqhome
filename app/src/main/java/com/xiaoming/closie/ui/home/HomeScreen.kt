package com.xiaoming.closie.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiaoming.closie.data.model.ItemStatus
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.navigation.Destination
import com.xiaoming.closie.ui.Rose
import java.time.LocalDate

@Composable fun HomeScreen(repo:WardrobeRepository,go:(String)->Unit){val items=repo.listItems();val owned=items.filter{it.status==ItemStatus.OWNED};val today=LocalDate.now().toString();LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){item{Text("Closie",style=MaterialTheme.typography.displaySmall);Text("你的衣橱朋友今天也在。",color=Rose)};item{Card(colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color(0xFFF3E5E7))){Column(Modifier.padding(18.dp)){Text("小柿的今日观察",style=MaterialTheme.typography.titleMedium);Text(observation(repo,owned.size))}}};item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Button(onClick={go(Destination.Owned.route)},modifier=Modifier.weight(1f)){Text("我的衣橱\n${owned.size} 件")};Button(onClick={go(Destination.Returned.route)},modifier=Modifier.weight(1f)){Text("试过 / 退货\n${items.count{it.status==ItemStatus.RETURNED}} 件")}};item{Text(if(repo.listOotds().any{it.date==today})"今天已记录 OOTD" else "今天还没有 OOTD",color=Rose)}}}
private fun observation(repo:WardrobeRepository,count:Int)=when{count==0->"我们先从第一件衣服开始吧，我会记得它的故事。";repo.listItems().any{repo.wearCount(it.id)==0}->"衣橱里有 ${repo.listItems().count{repo.wearCount(it.id)==0}} 件还没有穿过，它们正等一个合适的日子。";else->"你已经认真记录了 $count 件衣服。我们慢慢找出你真正最常穿、最自在的样子。"}
