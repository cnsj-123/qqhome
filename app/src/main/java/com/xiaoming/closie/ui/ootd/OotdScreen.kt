package com.xiaoming.closie.ui.ootd
import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xiaoming.closie.data.model.*
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.BackButton
import java.time.LocalDate
@Composable fun OotdScreen(repo:WardrobeRepository,back:()->Unit){val context=LocalContext.current;val all by repo.items.collectAsState();val ootds by repo.ootds.collectAsState();var date by remember{mutableStateOf(LocalDate.now().toString())};var editing by remember{mutableStateOf<Ootd?>(null)};var note by remember{mutableStateOf("")};var selected by remember{mutableStateOf(setOf<String>())};fun load(o:Ootd?){editing=o;note=o?.note.orEmpty();selected=o?.itemIds?.toSet()?:emptySet()};Scaffold(topBar={TopAppBar(title={Text("OOTD 日历")},navigationIcon={BackButton(back)})},floatingActionButton={FloatingActionButton(onClick={load(null)}){Text("+")}}){pad->LazyColumn(Modifier.padding(pad).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){item{OutlinedButton(onClick={val d=LocalDate.parse(date);DatePickerDialog(context,{_,y,m,day->date="%04d-%02d-%02d".format(y,m+1,day)},d.year,d.monthValue-1,d.dayOfMonth).show()}){Text("选择日期：$date")}};items(ootds.filter{it.date==date}){o->Card(Modifier.fillMaxWidth().clickable{load(o)}){Column(Modifier.padding(12.dp)){Text(o.note.ifBlank{"OOTD"});Text("${o.itemIds.size} 件单品")}}};if(editing!=null||selected.isNotEmpty())item{Text("编辑 OOTD",style=MaterialTheme.typography.titleMedium);OutlinedTextField(note,{note=it},label={Text("备注")},modifier=Modifier.fillMaxWidth());all.filter{it.status==ItemStatus.OWNED}.forEach{i->Row(Modifier.fillMaxWidth().clickable{selected=if(i.id in selected)selected-i.id else selected+i.id}.padding(6.dp)){Checkbox(i.id in selected,onCheckedChange={selected=if(it)selected+i.id else selected-i.id});Text(i.name,Modifier.padding(start=8.dp))}};Row{Button(enabled=selected.isNotEmpty(),onClick={repo.saveOotd((editing?:Ootd(date=date)).copy(date=date,itemIds=selected.toList(),note=note));editing=null;selected=emptySet();note=""}){Text("保存 OOTD")};if(editing!=null)TextButton(onClick={repo.deleteOotd(editing!!.id);editing=null;selected=emptySet()}){Text("删除")}}}}}}
