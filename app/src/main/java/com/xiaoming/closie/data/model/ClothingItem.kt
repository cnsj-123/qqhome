package com.xiaoming.closie.data.model

import java.util.UUID

enum class ItemStatus { OWNED, RETURNED }
enum class ImageKind { FLAT, ME, MODEL, PRODUCT }

/** Image files live in app-private storage now; a future server may also provide remoteUrl. */
data class ClothingImage(val id: String = UUID.randomUUID().toString(), val kind: ImageKind, val localPath: String? = null, val remoteUrl: String? = null, val createdAt: Long = System.currentTimeMillis())
data class MaterialPart(val id: String = UUID.randomUUID().toString(), val name: String = "", val percentage: String = "")
data class Measurement(val id: String = UUID.randomUUID().toString(), val name: String = "", val value: String = "", val unit: String = "cm")

/** Core wardrobe entity; IDs rather than copied fields are used by OOTD and Outfit. */
data class ClothingItem(
    val id: String = UUID.randomUUID().toString(), val status: ItemStatus = ItemStatus.OWNED,
    val name: String = "", val category: String = "未分类", val subcategory: String = "",
    val brand: String = "", val store: String = "", val purchasePlatform: String = "", val productUrl: String = "",
    val price: Double? = null, val originalPrice: Double? = null, val purchaseDate: String = "",
    val sizeLabel: String = "", val safetyCategory: String = "", val comment: String = "", val rating: Int = 0,
    val returnReason: String = "", val images: List<ClothingImage> = emptyList(),
    val materials: List<MaterialPart> = emptyList(), val measurements: List<Measurement> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis()
)
