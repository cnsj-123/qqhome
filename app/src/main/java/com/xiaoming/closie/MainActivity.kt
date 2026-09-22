package com.xiaoming.closie

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val Rose = Color(0xFF765661)
private val Blush = Color(0xFFFFF7F6)
private val Ink = Color(0xFF33282B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { ClosieApp() } }
}

@Composable private fun DetailScreen(item: ClothingItem, update:(ClothingItem)->Unit, back:()->Unit) {
    var addingPhoto by remember { mutableStateOf<ImageKind?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> val kind=addingPhoto; if(uri!=null && kind!=null) update(item.copy(images=item.images.filterNot{it.kind==kind}+ImageRef(kind,uri.toString()))); addingPhoto=null }
    Scaffold(topBar={ TopAppBar(title={Text(item.name)},navigationIcon={Back(back)}) }) { pad -> LazyColumn(Modifier.padding(pad).padding(20.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { val photo=item.images.firstOrNull{it.kind==ImageKind.FLAT}?:item.images.firstOrNull(); if(photo!=null) AsyncImage(photo.uri,item.name,Modifier.fillMaxWidth().height(310.dp).clip(RoundedCornerShape(18.dp)),contentScale=ContentScale.Crop) else Box(Modifier.fillMaxWidth().height(240.dp).background(Color(0xFFF0E8E8)),Alignment.Center){Text("还没有平铺图")} }
        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { ImageKind.entries.forEach{kind->OutlinedButton(onClick={addingPhoto=kind;picker.launch("image/*")},modifier=Modifier.weight(1f)){Text(when(kind){ImageKind.FLAT->"平铺图";ImageKind.ME->"我";ImageKind.MODEL->"模特";ImageKind.PRODUCT->"商品"},maxLines=1)}}} }
        item { Column { Text(item.category,color=Rose);Text(item.store.ifBlank{"未填写店铺"});item.price?.let{Text("¥${"%.2f".format(it)} · ${item.purchaseDate}")} } }
        item { InfoBlock("我的评价",listOf(if(item.comment.isBlank()) "还没有记录。" else item.comment) + if(item.status==ItemStatus.RETURNED&&item.returnReason.isNotBlank()) listOf("退货原因：${item.returnReason}") else emptyList()) }
        item { InfoBlock("面料与尺码",listOf("尺码：${item.sizeLabel.ifBlank{"未填写"}}","安全类别：${item.safetyCategory.ifBlank{"未填写"}}")+item.materials.map{"${it.name} ${it.percentage}"}+item.measurements.map{"${it.name}：${it.value}${it.unit}"}) }
        if(item.status==ItemStatus.OWNED) item { WearPanel(item,update) }
    } }
}
@Composable private fun WearPanel(item:ClothingItem, update:(ClothingItem)->Unit) { val today=LocalDate.now().toString(); val cost=item.price?.let{if(item.wearDates.isEmpty())"尚未穿着" else "¥${"%.2f".format(it/item.wearDates.size)} / 次"}?:"未填写价格";Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFF3E5E7))){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("使用记录",fontWeight=FontWeight.Bold);Text("穿着 ${item.wearDates.size} 次 · 洗涤 ${item.washDates.size} 次 · $cost");Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Button(onClick={if(!item.wearDates.contains(today))update(item.copy(wearDates=item.wearDates+today))},colors=ButtonDefaults.buttonColors(containerColor=Rose)){Text(if(item.wearDates.contains(today))"今天已记录"else"今天穿了 +1")};OutlinedButton(onClick={update(item.copy(washDates=item.washDates+today))}){Text("洗过 +1")}}}}}
@Composable private fun InfoBlock(title:String,lines:List<String>) { Card(colors=CardDefaults.cardColors(containerColor=Color.White)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(title,fontWeight=FontWeight.Bold);lines.forEach{Text(it)}}} }
@Composable private fun CalendarScreen(items:List<ClothingItem>,ootds:List<Ootd>,save:(List<ClothingItem>,List<Ootd>)->Unit,back:()->Unit){var date by remember{mutableStateOf(LocalDate.now().toString())};var selected by remember{mutableStateOf(emptySet<String>())};var note by remember{mutableStateOf("")};val existing=ootds.firstOrNull{it.date==date};LaunchedEffect(date){selected=existing?.itemIds?.toSet()?:emptySet();note=existing?.note?:""};Scaffold(topBar={TopAppBar(title={Text("OOTD 日历")},navigationIcon={Back(back)})}){pad->LazyColumn(Modifier.padding(pad).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Text("记录穿着会自动增加对应单品的次数。",color=Rose)};item{OutlinedTextField(date,{date=it},label={Text("日期（YYYY-MM-DD）")},modifier=Modifier.fillMaxWidth())};item{Text("选择今天穿的单品",fontWeight=FontWeight.Bold)};items.filter{it.status==ItemStatus.OWNED}.forEach{clothing->item{val checked=clothing.id in selected;Card(modifier=Modifier.fillMaxWidth().clickable{selected=if(checked)selected-clothing.id else selected+clothing.id},colors=CardDefaults.cardColors(containerColor=if(checked)Color(0xFFF3E5E7)else Color.White)){Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(checked,{selected=if(checked)selected-clothing.id else selected+clothing.id});Text(clothing.name)}}}};item{OutlinedTextField(note,{note=it},label={Text("今天的心情 / 场合")},modifier=Modifier.fillMaxWidth())};item{Button(modifier=Modifier.fillMaxWidth(),enabled=selected.isNotEmpty(),onClick={val old=existing?.itemIds?.toSet()?:emptySet();val add=selected-old;val remove=old-selected;val updated=items.map{c->when{c.id in add->c.copy(wearDates=c.wearDates+date);c.id in remove->c.copy(wearDates=c.wearDates.filterNot{it==date});else->c}};val rec=Ootd(id=existing?.id?:java.util.UUID.randomUUID().toString(),date=date,itemIds=selected.toList(),note=note);save(updated,ootds.filterNot{it.date==date}+rec)},colors=ButtonDefaults.buttonColors(containerColor=Rose)){Text(if(existing==null)"保存 OOTD"else"更新 OOTD")}}}}}
@Composable private fun FriendScreen(items:List<ClothingItem>,ootds:List<Ootd>,back:()->Unit){var input by remember{mutableStateOf("")};var messages by remember{mutableStateOf(listOf("小柿" to friendObservation(items.filter{it.status==ItemStatus.OWNED},ootds)))};Scaffold(topBar={TopAppBar(title={Text("小柿 · 你的衣橱朋友")},navigationIcon={Back(back)})},bottomBar={Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(input,{input=it},placeholder={Text("和小柿说点什么…")},modifier=Modifier.weight(1f));IconButton(onClick={if(input.isNotBlank()){messages=messages+("你"to input)+("小柿"to answer(input,items,ootds));input=""}}){Icon(Icons.Default.Send,"发送",tint=Rose)}}}){pad->LazyColumn(Modifier.padding(pad).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){messages.forEach{(who,text)->item{Card(colors=CardDefaults.cardColors(containerColor=if(who=="小柿")Color(0xFFF3E5E7)else Color.White),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text(who,fontWeight=FontWeight.Bold,color=Rose);Text(text)}}}}}}}
private fun friendObservation(items:List<ClothingItem>,ootds:List<Ootd>):String{if(items.isEmpty())return "我们先从第一件衣服开始吧。你慢慢把衣橱交给我，我会记得每件衣服的故事。";val unworn=items.count{it.wearDates.isEmpty()};val latest=ootds.maxByOrNull{it.date};return when{unworn>0->"我看到衣橱里有 $unworn 件还没有穿过。它们不是被遗忘，只是在等一个合适的日子。";latest==null->"衣橱已经有 ${items.size} 件单品了。要不要从今天的 OOTD 开始，让我认识你的穿衣节奏？";else->"你上次记录穿搭是 ${latest.date}。我会慢慢学会：什么才是你真正穿得自在的样子。"}}
private fun answer(input:String,items:List<ClothingItem>,ootds:List<Ootd>):String{val q=input.lowercase();return when{q.contains("闲置")||q.contains("没穿")->"目前有 ${items.count{it.status==ItemStatus.OWNED&&it.wearDates.isEmpty()}} 件从未穿过。我们可以下次从它们里挑一件，给它一次机会。";q.contains("退")->"你记录了 ${items.count{it.status==ItemStatus.RETURNED}} 件退货单品。以后我会从这些经验里，帮你识别重复踩雷的版型和面料。";q.contains("穿")||q.contains("搭配")->"我愿意陪你一起搭。先告诉我今天的天气、场合，或者你特别想穿的一件衣服；搭配室也会在下一版加入可自由摆放的透明底单品。";else->"我在听。现在我已经记得 ${items.count{it.status==ItemStatus.OWNED}} 件你留下的衣服，也会认真记住你对每一件的感受。"}}
@Composable private fun Back(action:()->Unit){IconButton(onClick=action){Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回")}}
@Composable private fun Empty(text:String){Text(text,color=Rose)}

@Composable
fun ClosieApp() {
    val context = LocalContext.current
    val store = remember { WardrobeStore(context) }
    var items by remember { mutableStateOf(store.items()) }
    var ootds by remember { mutableStateOf(store.ootds()) }
    var page by remember { mutableStateOf("home") }
    var selected by remember { mutableStateOf<ClothingItem?>(null) }
    fun saveItems(next: List<ClothingItem>) { items = next; store.saveItems(next) }
    fun saveOotds(next: List<Ootd>) { ootds = next; store.saveOotds(next) }
    MaterialTheme(colorScheme = lightColorScheme(primary = Rose, surface = Blush, background = Blush, onBackground = Ink)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (page) {
                "home" -> HomeScreen(items, ootds, { page = it }, { selected = it; page = "detail" })
                "closet" -> ClosetScreen(items, ItemStatus.OWNED, { selected = it; page = "detail" }, { page = "add" }, { page = "home" })
                "returned" -> ClosetScreen(items, ItemStatus.RETURNED, { selected = it; page = "detail" }, { page = "add_returned" }, { page = "home" })
                "add", "add_returned" -> EditItemScreen(page == "add_returned", { new -> saveItems(items + new); page = if (new.status == ItemStatus.OWNED) "closet" else "returned" }, { page = "home" })
                "detail" -> selected?.let { item -> DetailScreen(item, { changed -> saveItems(items.map { if (it.id == changed.id) changed else it }); selected = changed }, { page = if (item.status == ItemStatus.OWNED) "closet" else "returned" }) }
                "calendar" -> CalendarScreen(items, ootds, { updatedItems, updatedOotds -> saveItems(updatedItems); saveOotds(updatedOotds) }, { page = "home" })
                "friend" -> FriendScreen(items, ootds, { page = "home" })
            }
        }
    }
}

@Composable private fun HomeScreen(items: List<ClothingItem>, ootds: List<Ootd>, go: (String)->Unit, open: (ClothingItem)->Unit) {
    val owned = items.filter { it.status == ItemStatus.OWNED }
    val today = LocalDate.now().toString()
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Closie", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold); Text("你的衣橱朋友今天也在。", color = Rose) }
        item { Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E5E7))) { Column(Modifier.padding(18.dp)) { Text("小柿的今日观察", fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text(friendObservation(owned, ootds)); TextButton(onClick = { go("friend") }) { Text("和小柿聊聊 →") } } } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { HomeTile("我的衣橱", "${owned.size} 件", Icons.Default.Checkroom, Modifier.weight(1f)) { go("closet") }; HomeTile("试过退货", "${items.count { it.status == ItemStatus.RETURNED }} 件", Icons.Default.Favorite, Modifier.weight(1f)) { go("returned") } } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { HomeTile("OOTD 日历", if (ootds.any { it.date == today }) "今天已记录" else "记录今天", Icons.Default.CalendarMonth, Modifier.weight(1f)) { go("calendar") }; HomeTile("搭配室", "即将开放", Icons.Default.Home, Modifier.weight(1f)) { go("friend") } } }
        item { Text("最近加入", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        if (owned.isEmpty()) item { Empty("先添加第一件衣服吧。") } else item { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(owned.takeLast(8)) { ItemCard(it, Modifier.width(148.dp)) { open(it) } } } }
    }
}

@Composable private fun HomeTile(title:String, sub:String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier:Modifier, action:()->Unit) { Card(modifier.clickable { action() }, colors=CardDefaults.cardColors(containerColor=Color.White)) { Column(Modifier.padding(14.dp)) { Icon(icon, null, tint=Rose); Spacer(Modifier.height(12.dp)); Text(title, fontWeight=FontWeight.Bold); Text(sub, style=MaterialTheme.typography.bodySmall, color=Rose) } } }

@Composable private fun ClosetScreen(items:List<ClothingItem>, status:ItemStatus, open:(ClothingItem)->Unit, add:()->Unit, back:()->Unit) {
    val title = if(status == ItemStatus.OWNED) "我的衣橱" else "试过 / 退货"
    Scaffold(topBar={ TopAppBar(title={Text(title)}, navigationIcon={Back(back)}) }, floatingActionButton={ FloatingActionButton(onClick=add, containerColor=Rose) { Icon(Icons.Default.Add, "添加", tint=Color.White) } }) { pad ->
        val filtered = items.filter { it.status==status }
        if(filtered.isEmpty()) Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { Empty(if(status==ItemStatus.OWNED) "还没有衣服，点右下角添加。" else "把试过但退掉的衣服也记下来吧。") }
        else LazyVerticalGrid(GridCells.Fixed(2), Modifier.padding(pad).padding(12.dp), contentPadding=PaddingValues(bottom=76.dp), horizontalArrangement=Arrangement.spacedBy(12.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) { items(filtered) { ItemCard(it, Modifier) { open(it) } } }
    }
}

@Composable private fun ItemCard(item:ClothingItem, modifier:Modifier, action:()->Unit) { Card(modifier.clickable { action() }, colors=CardDefaults.cardColors(containerColor=Color.White)) { Column { val photo=item.images.firstOrNull { it.kind==ImageKind.FLAT } ?: item.images.firstOrNull(); if(photo != null) AsyncImage(photo.uri, item.name, Modifier.fillMaxWidth().height(150.dp), contentScale=ContentScale.Crop) else Box(Modifier.fillMaxWidth().height(150.dp).background(Color(0xFFF0E8E8)), Alignment.Center) { Text("未添加图片", color=Rose) }; Column(Modifier.padding(10.dp)) { Text(item.name, maxLines=1, overflow=TextOverflow.Ellipsis, fontWeight=FontWeight.SemiBold); Text(item.category, style=MaterialTheme.typography.bodySmall, color=Rose); item.price?.let { Text("¥${"%.0f".format(it)}", style=MaterialTheme.typography.bodySmall) } } } }

@Composable private fun EditItemScreen(returned:Boolean, save:(ClothingItem)->Unit, back:()->Unit) {
    var name by remember { mutableStateOf("") }; var category by remember { mutableStateOf("") }; var store by remember { mutableStateOf("") }; var price by remember { mutableStateOf("") }; var size by remember { mutableStateOf("") }; var comment by remember { mutableStateOf("") }; var safety by remember { mutableStateOf("") }; var imageUri by remember { mutableStateOf<String?>(null) }; var material by remember { mutableStateOf("") }; var measureName by remember { mutableStateOf("") }; var measureValue by remember { mutableStateOf("") }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri:Uri? -> uri?.let { imageUri=it.toString() } }
    Scaffold(topBar={ TopAppBar(title={Text(if(returned) "添加退货衣服" else "添加衣服")},navigationIcon={Back(back)}) }) { pad -> LazyColumn(Modifier.padding(pad).padding(20.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { OutlinedTextField(name,{name=it},label={Text("名称 *")},modifier=Modifier.fillMaxWidth()) }; item { OutlinedTextField(category,{category=it},label={Text("分类（上衣/裤子/鞋等）")},modifier=Modifier.fillMaxWidth()) }; item { Button(onClick={picker.launch("image/*")}, colors=ButtonDefaults.buttonColors(containerColor=Rose)) { Text(if(imageUri==null) "从相册添加平铺/商品图" else "已选择图片，点此更换") }; imageUri?.let { AsyncImage(it,"预览",Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp)), contentScale=ContentScale.Crop) } }
        item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { OutlinedTextField(price,{price=it},label={Text("购买价格")},modifier=Modifier.weight(1f)); OutlinedTextField(size,{size=it},label={Text("尺码标签")},modifier=Modifier.weight(1f)) } }; item { OutlinedTextField(store,{store=it},label={Text("店铺/品牌")},modifier=Modifier.fillMaxWidth()) }; item { OutlinedTextField(material,{material=it},label={Text("面料，如：羊毛 80%")},modifier=Modifier.fillMaxWidth()) }; item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { OutlinedTextField(measureName,{measureName=it},label={Text("尺寸名，如胸围")},modifier=Modifier.weight(1f)); OutlinedTextField(measureValue,{measureValue=it},label={Text("数值")},modifier=Modifier.weight(1f)) } }; item { OutlinedTextField(safety,{safety=it},label={Text("安全类别")},modifier=Modifier.fillMaxWidth()) }; item { OutlinedTextField(comment,{comment=it},label={Text(if(returned) "退货原因 / 感受" else "我的评价")},modifier=Modifier.fillMaxWidth(), minLines=3) }
        item { Button(enabled=name.isNotBlank(), modifier=Modifier.fillMaxWidth(), onClick={ save(ClothingItem(status=if(returned) ItemStatus.RETURNED else ItemStatus.OWNED,name=name,category=category.ifBlank{"未分类"},store=store,brand=store,price=price.toDoubleOrNull(),sizeLabel=size,safetyCategory=safety,comment=comment,returnReason=if(returned) comment else "",images=imageUri?.let{listOf(ImageRef(ImageKind.FLAT,it))}?:emptyList(),materials=material.takeIf{it.isNotBlank()}?.let{listOf(MaterialPart(it,""))}?:emptyList(),measurements=if(measureName.isNotBlank()) listOf(Measurement(measureName,measureValue)) else emptyList())) }, colors=ButtonDefaults.buttonColors(containerColor=Rose)) { Text("保存到衣橱") } }
    } }
}
