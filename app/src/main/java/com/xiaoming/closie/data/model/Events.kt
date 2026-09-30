package com.xiaoming.closie.data.model

import java.util.UUID

enum class WearSource { MANUAL, OOTD }
data class WearEvent(val id: String = UUID.randomUUID().toString(), val itemId: String, val date: String, val source: WearSource = WearSource.MANUAL, val ootdId: String? = null, val note: String = "")
data class WashEvent(val id: String = UUID.randomUUID().toString(), val itemId: String, val date: String, val note: String = "")
data class Ootd(val id: String = UUID.randomUUID().toString(), val date: String, val itemIds: List<String> = emptyList(), val note: String = "", val images: List<String> = emptyList(), val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis())
data class Placement(val itemId: String, val x: Float = .5f, val y: Float = .5f, val scale: Float = 1f, val zIndex: Int = 0)
data class Outfit(val id: String = UUID.randomUUID().toString(), val name: String = "", val itemIds: List<String> = emptyList(), val note: String = "", val placements: List<Placement> = emptyList(), val tryOnImages: List<String> = emptyList(), val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis())
