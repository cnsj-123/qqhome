package com.xiaoming.closie

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.LocalDate
import java.util.UUID

enum class ItemStatus { OWNED, RETURNED }
enum class ImageKind { FLAT, ME, MODEL, PRODUCT }

data class ImageRef(val kind: ImageKind, val uri: String)
data class Measurement(val name: String, val value: String, val unit: String = "cm")
data class MaterialPart(val name: String, val percentage: String)
data class ClothingItem(
    val id: String = UUID.randomUUID().toString(),
    val status: ItemStatus = ItemStatus.OWNED,
    val name: String,
    val category: String = "未分类",
    val store: String = "",
    val brand: String = "",
    val price: Double? = null,
    val purchaseDate: String = LocalDate.now().toString(),
    val sizeLabel: String = "",
    val safetyCategory: String = "",
    val comment: String = "",
    val rating: Int = 0,
    val returnReason: String = "",
    val images: List<ImageRef> = emptyList(),
    val materials: List<MaterialPart> = emptyList(),
    val measurements: List<Measurement> = emptyList(),
    val wearDates: List<String> = emptyList(),
    val washDates: List<String> = emptyList()
)

data class Ootd(val id: String = UUID.randomUUID().toString(), val date: String, val itemIds: List<String>, val note: String = "")

class WardrobeStore(context: Context) {
    private val prefs = context.getSharedPreferences("closie_store", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val itemType = object : TypeToken<List<ClothingItem>>() {}.type
    private val ootdType = object : TypeToken<List<Ootd>>() {}.type
    fun items(): List<ClothingItem> = gson.fromJson(prefs.getString("items", "[]"), itemType) ?: emptyList()
    fun ootds(): List<Ootd> = gson.fromJson(prefs.getString("ootds", "[]"), ootdType) ?: emptyList()
    fun saveItems(items: List<ClothingItem>) { prefs.edit().putString("items", gson.toJson(items)).apply() }
    fun saveOotds(ootds: List<Ootd>) { prefs.edit().putString("ootds", gson.toJson(ootds)).apply() }
}
