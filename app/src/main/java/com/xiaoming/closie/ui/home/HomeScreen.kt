package com.xiaoming.closie.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xiaoming.closie.data.model.ItemStatus
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.navigation.Destination
import com.xiaoming.closie.ui.Rose
import java.time.LocalDate

@Composable
fun HomeScreen(repo: WardrobeRepository, go: (String) -> Unit) {
    val items by repo.items.collectAsState()
    val ootds by repo.ootds.collectAsState()
    val outfits by repo.outfits.collectAsState()
    val wears by repo.wearEvents.collectAsState()
    val owned = items.filter { it.status == ItemStatus.OWNED }
    val today = LocalDate.now().toString()
    val thisMonth = today.substring(0, 7)

    val ownedIds = owned.map { it.id }.toSet()
    val ownedWears = wears.filter { it.itemId in ownedIds }
    val neverWornCount = owned.count { it.id !in ownedWears.map { w -> w.itemId }.toSet() }
    val monthWears = ownedWears.filter { it.date.startsWith(thisMonth) }
    val monthWearCount = monthWears.size
    val monthDistinctCount = monthWears.map { it.itemId }.distinct().size
    val topWorn = ownedWears.groupingBy { it.itemId }.eachCount().entries.sortedByDescending { it.value }.take(3)
    val nameById = owned.associateBy { it.id }

    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Closie", style = MaterialTheme.typography.displaySmall)
            Text("你的衣橱朋友今天也在。", color = Rose)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E5E7))) {
                Column(Modifier.padding(18.dp)) {
                    Text("小柿的今日观察", style = MaterialTheme.typography.titleMedium)
                    Text(observation(owned.size, neverWornCount))
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("衣橱概览", style = MaterialTheme.typography.titleMedium)
                    Text("拥有 ${owned.size} 件 · 从未穿过 $neverWornCount 件")
                    Text("本月穿过 $monthWearCount 次 · 涉及 $monthDistinctCount 件")
                    if (topWorn.isNotEmpty()) {
                        Text("最近最常穿：", style = MaterialTheme.typography.bodySmall, color = Rose)
                        topWorn.forEach { (id, count) ->
                            Text("${nameById[id]?.name ?: "?"} · $count 次", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { go(Destination.Owned.route) }, modifier = Modifier.weight(1f)) {
                    Text("我的衣橱\n${owned.size} 件")
                }
                Button(onClick = { go(Destination.Returned.route) }, modifier = Modifier.weight(1f)) {
                    Text("试过 / 退货\n${items.count { it.status == ItemStatus.RETURNED }} 件")
                }
            }
        }
        item {
            Button(onClick = { go(Destination.Ootd.route) }, modifier = Modifier.fillMaxWidth()) {
                Text("OOTD 日历")
            }
        }
        item {
            Button(onClick = { go(Destination.Outfits.route) }, modifier = Modifier.fillMaxWidth()) {
                Text("搭配室\n${outfits.size} 套搭配")
            }
        }
        item {
            Text(if (ootds.any { it.date == today }) "今天已记录 OOTD" else "今天还没有 OOTD", color = Rose)
        }
        item {
            OutlinedButton(onClick = { go(Destination.Settings.route) }, modifier = Modifier.fillMaxWidth()) {
                Text("数据与备份")
            }
        }
    }
}

private fun observation(ownedCount: Int, neverWornCount: Int) = when {
    ownedCount == 0 -> "我们先从第一件衣服开始吧，我会记得它的故事。"
    neverWornCount > 0 -> "衣橱里有 $neverWornCount 件还没有穿过，它们正等一个合适的日子。"
    else -> "你已经认真记录了 $ownedCount 件衣服。我们慢慢找出你真正最常穿、最自在的样子。"
}
