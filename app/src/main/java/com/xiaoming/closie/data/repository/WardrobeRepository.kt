package com.xiaoming.closie.data.repository

import com.xiaoming.closie.data.model.*

/** Future All-in-One tool boundary: UI and later assistants never manipulate storage directly. */
interface WardrobeRepository {
    fun listItems(): List<ClothingItem>; fun getItem(id: String): ClothingItem?
    fun createItem(item: ClothingItem): ClothingItem; fun updateItem(item: ClothingItem): ClothingItem; fun deleteItem(id: String)
    fun listWearEvents(): List<WearEvent>; fun addWear(itemId: String, date: String, source: WearSource = WearSource.MANUAL, ootdId: String? = null, note: String = ""); fun addWash(itemId: String, date: String, note: String = "")
    fun wearCount(itemId: String): Int; fun washCount(itemId: String): Int
    fun listOotds(): List<Ootd>; fun saveOotd(ootd: Ootd): Ootd; fun deleteOotd(id: String)
    fun listOutfits(): List<Outfit>; fun saveOutfit(outfit: Outfit): Outfit; fun deleteOutfit(id: String)
}
