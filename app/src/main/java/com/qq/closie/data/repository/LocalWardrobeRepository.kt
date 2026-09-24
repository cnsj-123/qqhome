package com.qq.closie.data.repository

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.qq.closie.data.ImageStore
import com.qq.closie.data.backup.BackupManager
import com.qq.closie.data.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** JSON persistence with atomic replacement and observable in-memory snapshots. */
class LocalWardrobeRepository(private val context: Context) : WardrobeRepository {
    private val gson = Gson()
    private val folder = File(context.filesDir, "closie")
    private val itemType = object : TypeToken<List<ClothingItem>>() {}.type
    private val wearType = object : TypeToken<List<WearEvent>>() {}.type
    private val washType = object : TypeToken<List<WashEvent>>() {}.type
    private val ootdType = object : TypeToken<List<Ootd>>() {}.type
    private val outfitType = object : TypeToken<List<Outfit>>() {}.type

    init {
        BackupManager.recoverInterruptedRestore(context)
        folder.mkdirs()
    }

    private fun <T> read(name: String, type: java.lang.reflect.Type): List<T> =
        runCatching { File(folder, name).takeIf { it.exists() }?.let { gson.fromJson<List<T>>(it.readText(), type) } ?: emptyList() }
            .getOrDefault(emptyList())

    private fun write(name: String, value: Any) {
        val f = File(folder, name)
        val t = File(folder, ".$name.tmp")
        t.writeText(gson.toJson(value))
        if (!t.renameTo(f)) { f.delete(); check(t.renameTo(f)) }
    }

    private val _items = MutableStateFlow(read<ClothingItem>("items.json", itemType).sortedByDescending { it.updatedAt })
    override val items: StateFlow<List<ClothingItem>> = _items
    private val _wear = MutableStateFlow(read<WearEvent>("wear.json", wearType))
    override val wearEvents: StateFlow<List<WearEvent>> = _wear
    private val _wash = MutableStateFlow(read<WashEvent>("wash.json", washType))
    override val washEvents: StateFlow<List<WashEvent>> = _wash
    private val _ootds = MutableStateFlow(read<Ootd>("ootds.json", ootdType))
    override val ootds: StateFlow<List<Ootd>> = _ootds
    private val _outfits = MutableStateFlow(read<Outfit>("outfits.json", outfitType))
    override val outfits: StateFlow<List<Outfit>> = _outfits

    init { LegacyMigration(context, this).runIfNeeded() }

    private fun putItems(v: List<ClothingItem>) { _items.value = v.sortedByDescending { it.updatedAt }; write("items.json", _items.value) }
    private fun putWear(v: List<WearEvent>) { _wear.value = v; write("wear.json", v) }
    private fun putWash(v: List<WashEvent>) { _wash.value = v; write("wash.json", v) }
    private fun putOotds(v: List<Ootd>) { _ootds.value = v; write("ootds.json", v) }
    private fun putOutfits(v: List<Outfit>) { _outfits.value = v; write("outfits.json", v) }

    override fun listItems() = items.value
    override fun getItem(id: String) = items.value.find { it.id == id }

    override fun createItem(item: ClothingItem): ClothingItem {
        val s = item.copy(updatedAt = System.currentTimeMillis())
        putItems(items.value.filterNot { it.id == s.id } + s)
        return s
    }

    override fun updateItem(item: ClothingItem): ClothingItem {
        val old = getItem(item.id)
        val s = item.copy(updatedAt = System.currentTimeMillis())
        putItems(items.value.map { if (it.id == s.id) s else it })
        val removed = old?.images.orEmpty().mapNotNull { it.localPath }.toSet() - s.images.mapNotNull { it.localPath }.toSet()
        deleteUnreferenced(removed)
        return s
    }

    override fun deleteItem(id: String) {
        val old = getItem(id)
        putItems(items.value.filterNot { it.id == id })
        putWear(wearEvents.value.filterNot { it.itemId == id })
        putWash(washEvents.value.filterNot { it.itemId == id })
        putOotds(ootds.value.map { it.copy(itemIds = it.itemIds.filterNot { x -> x == id }) })
        putOutfits(outfits.value.map { it.copy(itemIds = it.itemIds.filterNot { x -> x == id }, placements = it.placements.filterNot { p -> p.itemId == id }) })
        old?.images.orEmpty().mapNotNull { it.localPath }.forEach { ImageStore.deletePrivatePath(context, it) }
    }

    private fun deleteUnreferenced(paths: Set<String>) {
        val used = items.value.flatMap { it.images }.mapNotNull { it.localPath }.toSet()
        paths.filterNot { it in used }.forEach { ImageStore.deletePrivatePath(context, it) }
    }

    override fun listWearEvents() = wearEvents.value

    override fun addWear(itemId: String, date: String, source: WearSource, ootdId: String?, note: String) {
        if (getItem(itemId) == null) return
        val exists = if (source == WearSource.MANUAL)
            wearEvents.value.any { it.itemId == itemId && it.date == date && it.source == source }
        else
            wearEvents.value.any { it.itemId == itemId && it.ootdId == ootdId && it.source == source }
        if (!exists) putWear(wearEvents.value + WearEvent(itemId = itemId, date = date, source = source, ootdId = ootdId, note = note))
    }

    override fun addWash(itemId: String, date: String, note: String) {
        if (getItem(itemId) != null && washEvents.value.none { it.itemId == itemId && it.date == date })
            putWash(washEvents.value + WashEvent(itemId = itemId, date = date, note = note))
    }

    override fun deleteWearEvent(id: String) = putWear(wearEvents.value.filterNot { it.id == id })
    override fun deleteWashEvent(id: String) = putWash(washEvents.value.filterNot { it.id == id })

    override fun wearCount(itemId: String) = wearEvents.value.count { it.itemId == itemId }
    override fun washCount(itemId: String) = washEvents.value.count { it.itemId == itemId }

    override fun listOotds() = ootds.value

    override fun saveOotd(ootd: Ootd): Ootd {
        val old = ootds.value.firstOrNull { it.id == ootd.id }
        val s = ootd.copy(
            itemIds = ootd.itemIds.distinct().filter { getItem(it)?.status == ItemStatus.OWNED },
            updatedAt = System.currentTimeMillis()
        )
        putOotds(if (ootds.value.any { it.id == s.id }) ootds.value.map { if (it.id == s.id) s else it } else ootds.value + s)
        val wanted = s.itemIds.toSet()
        putWear(wearEvents.value.filterNot { it.source == WearSource.OOTD && it.ootdId == s.id && it.itemId !in wanted }
            .map { if (it.source == WearSource.OOTD && it.ootdId == s.id) it.copy(date = s.date, note = s.note) else it })
        wanted.forEach { addWear(it, s.date, WearSource.OOTD, s.id, s.note) }
        val removed = old?.images.orEmpty().filterNot { it in s.images }
        removed.forEach { ImageStore.deletePrivatePath(context, it) }
        return s
    }

    override fun deleteOotd(id: String) {
        val old = ootds.value.firstOrNull { it.id == id }
        putOotds(ootds.value.filterNot { it.id == id })
        putWear(wearEvents.value.filterNot { it.source == WearSource.OOTD && it.ootdId == id })
        old?.images.orEmpty().forEach { ImageStore.deletePrivatePath(context, it) }
    }

    override fun listOutfits() = outfits.value

    override fun saveOutfit(outfit: Outfit): Outfit {
        val old = outfits.value.firstOrNull { it.id == outfit.id }
        val s = outfit.copy(updatedAt = System.currentTimeMillis())
        putOutfits(if (outfits.value.any { it.id == s.id }) outfits.value.map { if (it.id == s.id) s else it } else outfits.value + s)
        val removed = old?.tryOnImages.orEmpty().filterNot { it in s.tryOnImages }
        removed.forEach { ImageStore.deletePrivatePath(context, it) }
        return s
    }

    override fun deleteOutfit(id: String) {
        val old = outfits.value.firstOrNull { it.id == id }
        putOutfits(outfits.value.filterNot { it.id == id })
        old?.tryOnImages.orEmpty().forEach { ImageStore.deletePrivatePath(context, it) }
    }

    override fun reloadFromDisk() {
        _items.value = read<ClothingItem>("items.json", itemType).sortedByDescending { it.updatedAt }
        _wear.value = read<WearEvent>("wear.json", wearType)
        _wash.value = read<WashEvent>("wash.json", washType)
        _ootds.value = read<Ootd>("ootds.json", ootdType)
        _outfits.value = read<Outfit>("outfits.json", outfitType)
    }
}

private class LegacyMigration(private val context: Context, private val repo: WardrobeRepository) {
    fun runIfNeeded() {
        val p = context.getSharedPreferences("closie_store", Context.MODE_PRIVATE)
        if (p.getBoolean("migrated_v1", false)) return
        runCatching {
            val g = Gson()
            val olds = g.fromJson<List<LegacyItem>>(p.getString("items", null), object : TypeToken<List<LegacyItem>>() {}.type) ?: emptyList()
            olds.forEach { old ->
                val id = old.id ?: UUID.randomUUID().toString()
                if (repo.getItem(id) == null) repo.createItem(old.toItem(id))
                old.wearDates.orEmpty().distinct().forEach { repo.addWear(id, it) }
                old.washDates.orEmpty().distinct().forEach { repo.addWash(id, it) }
            }
            val ootds = g.fromJson<List<LegacyOotd>>(p.getString("ootds", null), object : TypeToken<List<LegacyOotd>>() {}.type) ?: emptyList()
            ootds.forEach { old ->
                val id = old.id ?: UUID.randomUUID().toString()
                if (repo.listOotds().none { it.id == id }) repo.saveOotd(Ootd(id = id, date = old.date.orEmpty(), itemIds = old.itemIds.orEmpty(), note = old.note.orEmpty()))
            }
        }.onSuccess { p.edit().putBoolean("migrated_v1", true).apply() }
    }

    private fun LegacyItem.toItem(id: String) = ClothingItem(
        id = id,
        status = if (status == "RETURNED") ItemStatus.RETURNED else ItemStatus.OWNED,
        name = name.orEmpty(), category = category ?: "未分类", subcategory = subcategory.orEmpty(),
        brand = brand.orEmpty(), store = store.orEmpty(), purchasePlatform = purchasePlatform.orEmpty(),
        productUrl = productUrl.orEmpty(), price = price, originalPrice = originalPrice,
        purchaseDate = purchaseDate.orEmpty(), sizeLabel = sizeLabel.orEmpty(), safetyCategory = safetyCategory.orEmpty(),
        comment = comment.orEmpty(), rating = rating ?: 0, returnReason = returnReason.orEmpty(),
        materials = materials.orEmpty().map { MaterialPart(name = it.name.orEmpty(), percentage = it.percentage.orEmpty()) },
        measurements = measurements.orEmpty().map { Measurement(name = it.name.orEmpty(), value = it.value.orEmpty(), unit = it.unit ?: "cm") },
        images = images.orEmpty().mapNotNull { img ->
            img.uri?.let { u -> ImageStore.copyFromUri(context, Uri.parse(u))?.let { ClothingImage(kind = runCatching { ImageKind.valueOf(img.kind ?: "FLAT") }.getOrDefault(ImageKind.FLAT), localPath = it) } }
        }
    )

    private data class LegacyItem(val id: String?, val status: String?, val name: String?, val category: String?, val subcategory: String?, val brand: String?, val store: String?, val purchasePlatform: String?, val productUrl: String?, val price: Double?, val originalPrice: Double?, val purchaseDate: String?, val sizeLabel: String?, val safetyCategory: String?, val comment: String?, val rating: Int?, val returnReason: String?, val images: List<LegacyImage>?, val materials: List<LegacyMaterial>?, val measurements: List<LegacyMeasurement>?, val wearDates: List<String>?, val washDates: List<String>?)
    private data class LegacyImage(val kind: String?, val uri: String?)
    private data class LegacyMaterial(val name: String?, val percentage: String?)
    private data class LegacyMeasurement(val name: String?, val value: String?, val unit: String?)
    private data class LegacyOotd(val id: String?, val date: String?, val itemIds: List<String>?, val note: String?)
}
