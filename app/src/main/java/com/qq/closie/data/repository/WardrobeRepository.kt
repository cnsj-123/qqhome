package com.qq.closie.data.repository

import com.qq.closie.data.model.*
import kotlinx.coroutines.flow.StateFlow

/** Future All-in-One tool boundary: UI and later assistants never manipulate storage directly. */
interface WardrobeRepository {
    val items: StateFlow<List<ClothingItem>>
    val wearEvents: StateFlow<List<WearEvent>>
    val washEvents: StateFlow<List<WashEvent>>
    val ootds: StateFlow<List<Ootd>>
    val outfits: StateFlow<List<Outfit>>
    fun listItems(): List<ClothingItem>; fun getItem(id: String): ClothingItem?
    fun createItem(item: ClothingItem): ClothingItem; fun updateItem(item: ClothingItem): ClothingItem; fun deleteItem(id: String)
    fun listWearEvents(): List<WearEvent>; fun addWear(itemId: String, date: String, source: WearSource = WearSource.MANUAL, ootdId: String? = null, note: String = ""); fun addWash(itemId: String, date: String, note: String = "")
    fun deleteWearEvent(id: String); fun deleteWashEvent(id: String)
    fun wearCount(itemId: String): Int; fun washCount(itemId: String): Int
    fun listOotds(): List<Ootd>; fun saveOotd(ootd: Ootd): Ootd; fun deleteOotd(id: String)
    fun listOutfits(): List<Outfit>; fun saveOutfit(outfit: Outfit): Outfit; fun deleteOutfit(id: String)
    fun reloadFromDisk()
}
