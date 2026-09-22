package com.xiaoming.closie.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xiaoming.closie.data.model.*
import java.io.File

/** Offline-first storage. Kept as a compatibility layer until the remote repository is configured. */
class LocalWardrobeRepository(private val context: Context) : WardrobeRepository {
    private val gson = Gson(); private val folder = File(context.filesDir, "closie").apply { mkdirs() }
    private fun <T> read(name:String,type:java.lang.reflect.Type):List<T> { val f=File(folder,name); return if(f.exists()) gson.fromJson(f.readText(),type)?:emptyList() else emptyList() }
    private fun write(name:String,value:Any) { File(folder,name).writeText(gson.toJson(value)) }
    private val itemType=object:TypeToken<List<ClothingItem>>(){}.type; private val wearType=object:TypeToken<List<WearEvent>>(){}.type; private val washType=object:TypeToken<List<WashEvent>>(){}.type; private val ootdType=object:TypeToken<List<Ootd>>(){}.type; private val outfitType=object:TypeToken<List<Outfit>>(){}.type
    init { LegacyMigration(context, this).runIfNeeded() }
    override fun listItems()=read<ClothingItem>("items.json",itemType).sortedByDescending{it.updatedAt}; override fun getItem(id:String)=listItems().find{it.id==id}
    override fun createItem(item:ClothingItem):ClothingItem { val saved=item.copy(updatedAt=System.currentTimeMillis());write("items.json",listItems()+saved);return saved }
    override fun updateItem(item:ClothingItem):ClothingItem { val saved=item.copy(updatedAt=System.currentTimeMillis());write("items.json",listItems().map{if(it.id==item.id)saved else it});return saved }
    override fun deleteItem(id:String){write("items.json",listItems().filterNot{it.id==id});write("wear.json",listWearEvents().filterNot{it.itemId==id});write("wash.json",read<WashEvent>("wash.json",washType).filterNot{it.itemId==id})}
    override fun listWearEvents()=read<WearEvent>("wear.json",wearType); override fun addWear(itemId:String,date:String,source:WearSource,ootdId:String?,note:String){write("wear.json",listWearEvents()+WearEvent(itemId=itemId,date=date,source=source,ootdId=ootdId,note=note))}
    override fun addWash(itemId:String,date:String,note:String){write("wash.json",read<WashEvent>("wash.json",washType)+WashEvent(itemId=itemId,date=date,note=note))}; override fun wearCount(itemId:String)=listWearEvents().count{it.itemId==itemId}; override fun washCount(itemId:String)=read<WashEvent>("wash.json",washType).count{it.itemId==itemId}
    override fun listOotds()=read<Ootd>("ootds.json",ootdType); override fun saveOotd(ootd:Ootd):Ootd{val all=listOotds();write("ootds.json",if(all.any{it.id==ootd.id})all.map{if(it.id==ootd.id)ootd.copy(updatedAt=System.currentTimeMillis())else it}else all+ootd);return ootd};override fun deleteOotd(id:String){write("ootds.json",listOotds().filterNot{it.id==id});write("wear.json",listWearEvents().filterNot{it.ootdId==id})}
    override fun listOutfits()=read<Outfit>("outfits.json",outfitType);override fun saveOutfit(outfit:Outfit):Outfit{val all=listOutfits();write("outfits.json",if(all.any{it.id==outfit.id})all.map{if(it.id==outfit.id)outfit.copy(updatedAt=System.currentTimeMillis())else it}else all+outfit);return outfit};override fun deleteOutfit(id:String){write("outfits.json",listOutfits().filterNot{it.id==id})}
}

/** Reads the v0 SharedPreferences payload once, so an APK upgrade does not erase existing records. */
private class LegacyMigration(private val context:Context,private val repo:WardrobeRepository){
    fun runIfNeeded(){val p=context.getSharedPreferences("closie_store",Context.MODE_PRIVATE);if(p.getBoolean("migrated_v1",false))return;val raw=p.getString("items",null);if(!raw.isNullOrBlank()&&repo.listItems().isEmpty()){runCatching{val legacy=Gson().fromJson<List<LegacyItem>>(raw,object:TypeToken<List<LegacyItem>>(){}.type)?:emptyList();legacy.forEach{repo.createItem(ClothingItem(id=it.id?:java.util.UUID.randomUUID().toString(),status=if(it.status=="RETURNED")ItemStatus.RETURNED else ItemStatus.OWNED,name=it.name.orEmpty(),category=it.category?:"未分类",brand=it.brand.orEmpty(),store=it.store.orEmpty(),price=it.price,purchaseDate=it.purchaseDate.orEmpty(),sizeLabel=it.sizeLabel.orEmpty(),safetyCategory=it.safetyCategory.orEmpty(),comment=it.comment.orEmpty(),rating=it.rating?:0,returnReason=it.returnReason.orEmpty(),images=it.images.orEmpty().mapNotNull{img->img.uri?.let{ClothingImage(kind=runCatching{ImageKind.valueOf(img.kind?:"FLAT")}.getOrDefault(ImageKind.FLAT),localPath=it)}}))}}};p.edit().putBoolean("migrated_v1",true).apply()}
    private data class LegacyItem(val id:String?,val status:String?,val name:String?,val category:String?,val brand:String?,val store:String?,val price:Double?,val purchaseDate:String?,val sizeLabel:String?,val safetyCategory:String?,val comment:String?,val rating:Int?,val returnReason:String?,val images:List<LegacyImage>?);private data class LegacyImage(val kind:String?,val uri:String?)
}
