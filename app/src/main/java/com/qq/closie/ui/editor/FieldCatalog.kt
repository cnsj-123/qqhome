package com.qq.closie.ui.editor

import com.qq.closie.data.model.ClothingItem

/**
 * Static presets for smart entry plus pure helpers that turn the user's already-saved
 * [ClothingItem]s into future suggestions. No extra database: suggestions come straight
 * from `repo.items`.
 */
object FieldCatalog {
    val categories = listOf(
        "上衣", "下装", "外套", "裙装", "连体", "鞋", "包", "配饰", "运动", "家居服", "内衣", "其他"
    )

    val brands = listOf(
        "Uniqlo", "GU", "MUJI", "COS", "Zara", "H&M", "Massimo Dutti",
        "Nike", "Adidas", "New Balance", "Puma",
        "Lululemon", "Arc'teryx", "The North Face", "Patagonia",
        "Levi's", "Ralph Lauren", "Tommy Hilfiger",
        "Coach", "Longchamp", "其他"
    )

    val platforms = listOf(
        "淘宝", "天猫", "京东", "拼多多", "小红书", "得物", "抖音",
        "品牌官网", "微信小程序", "线下门店",
        "Amazon", "Farfetch", "SSENSE", "END.", "ZOZOTOWN", "其他"
    )

    val stores = listOf("品牌官方旗舰店", "品牌官网", "线下门店", "百货商场", "其他")

    val safetyCategories = listOf("A类", "B类", "C类", "其他")

    val returnReasons = listOf(
        "尺码不合适", "版型不合适", "颜色不合适", "材质不喜欢", "做工 / 质量问题",
        "太透", "不舒服", "实物与图片不符", "性价比低", "重复 / 不需要", "物流问题", "其他"
    )

    val materials = listOf(
        "棉", "有机棉", "羊毛", "美利奴羊毛", "羊绒", "亚麻", "真丝",
        "聚酯纤维", "锦纶 / 尼龙", "腈纶", "氨纶 / 弹性纤维",
        "粘胶", "莱赛尔 / 天丝", "莫代尔", "醋酸纤维",
        "皮革", "羊皮", "牛皮", "麂皮", "羽绒", "羽毛", "其他"
    )

    val units = listOf("cm", "mm", "m", "inch", "g", "kg")

    val apparelSizes = listOf("XXS", "XS", "S", "M", "L", "XL", "2XL", "3XL", "Free")
    val pantsSizes = (24..36).map { it.toString() }
    val euSizes = listOf("42", "44", "46", "48", "50", "52", "54", "56")
    val shoeSizes = (35..46).flatMap { n ->
        if (n < 46) listOf(n.toString(), "$n.5") else listOf(n.toString())
    }

    private val subcategories = mapOf(
        "上衣" to listOf("T恤", "衬衫", "Polo", "背心", "吊带", "针织衫", "毛衣", "卫衣", "打底衫", "短袖上衣", "长袖上衣", "其他"),
        "下装" to listOf("牛仔裤", "西裤", "休闲裤", "阔腿裤", "直筒裤", "工装裤", "运动裤", "短裤", "半身裙", "打底裤", "其他"),
        "外套" to listOf("西装", "夹克", "牛仔外套", "皮衣", "风衣", "大衣", "羽绒服", "棉服", "开衫", "马甲", "冲锋衣", "其他"),
        "裙装" to listOf("连衣裙", "半身裙", "长裙", "短裙", "吊带裙", "针织裙", "其他"),
        "鞋" to listOf("运动鞋", "休闲鞋", "板鞋", "跑鞋", "乐福鞋", "德比鞋", "牛津鞋", "靴子", "短靴", "凉鞋", "高跟鞋", "拖鞋", "其他"),
        "包" to listOf("双肩包", "托特包", "斜挎包", "单肩包", "手提包", "腰包", "公文包", "旅行包", "其他"),
        "配饰" to listOf("帽子", "围巾", "腰带", "眼镜", "首饰", "手表", "袜子", "手套", "领带", "其他"),
        "运动" to listOf("运动上衣", "运动裤", "瑜伽服", "运动内衣", "骑行服", "泳装", "户外服", "其他"),
        "连体" to listOf("连衣裙", "连体裤", "背带裤", "其他"),
        "家居服" to listOf("睡衣", "家居服", "浴袍", "其他"),
        "内衣" to listOf("内裤", "文胸", "打底", "袜子", "其他")
    )

    private val measurementNames = mapOf(
        "上衣" to listOf("衣长", "肩宽", "胸围", "袖长", "袖口", "下摆"),
        "外套" to listOf("衣长", "肩宽", "胸围", "袖长", "袖口", "下摆"),
        "下装" to listOf("裤长", "腰围", "臀围", "前裆", "后裆", "大腿围", "裤脚", "内长"),
        "裙装" to listOf("裙长", "腰围", "臀围", "下摆"),
        "鞋" to listOf("内长", "鞋底长", "鞋跟高", "筒高", "筒围"),
        "包" to listOf("宽", "高", "厚", "肩带长", "手柄高")
    )

    fun subcategoriesFor(category: String): List<String> =
        subcategories[category] ?: listOf("其他")

    fun sizesFor(category: String): List<String> = when (category) {
        "鞋" -> shoeSizes
        "下装" -> pantsSizes + euSizes
        "外套", "上衣", "裙装", "连体" -> apparelSizes + euSizes
        else -> apparelSizes + euSizes
    }

    fun measurementNamesFor(category: String): List<String> =
        measurementNames[category] ?: listOf("衣长", "肩宽", "胸围", "袖长", "腰围", "臀围")
}

data class ExistingValues(
    val categories: List<String> = emptyList(),
    val subcategories: List<String> = emptyList(),
    val brands: List<String> = emptyList(),
    val stores: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val sizes: List<String> = emptyList(),
    val materials: List<String> = emptyList(),
    val measurementNames: List<String> = emptyList(),
    val units: List<String> = emptyList(),
    val safetyCategories: List<String> = emptyList(),
    val returnReasons: List<String> = emptyList()
)

fun collectExistingValues(items: List<ClothingItem>): ExistingValues = ExistingValues(
    categories = freqDedupe(items.map { it.category }),
    subcategories = freqDedupe(items.map { it.subcategory }),
    brands = freqDedupe(items.map { it.brand }),
    stores = freqDedupe(items.map { it.store }),
    platforms = freqDedupe(items.map { it.purchasePlatform }),
    sizes = freqDedupe(items.map { it.sizeLabel }),
    materials = freqDedupe(items.flatMap { it.materials }.map { it.name }),
    measurementNames = freqDedupe(items.flatMap { it.measurements }.map { it.name }),
    units = freqDedupe(items.flatMap { it.measurements }.map { it.unit }),
    safetyCategories = freqDedupe(items.map { it.safetyCategory }),
    returnReasons = freqDedupe(items.map { it.returnReason })
)

/** Case-insensitive dedup keeping the user's original spelling, ordered by frequency. */
fun freqDedupe(values: List<String>): List<String> {
    val groups = LinkedHashMap<String, MutableList<String>>()
    values.forEach { raw ->
        val t = raw.trim()
        if (t.isNotEmpty()) groups.getOrPut(t.lowercase()) { mutableListOf() }.add(t)
    }
    return groups.entries
        .sortedByDescending { it.value.size }
        .map { entry ->
            entry.value.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: entry.value.first()
        }
}

/** Existing values first, then presets, deduped case-insensitively. */
fun mergeOptions(existing: List<String>, presets: List<String>): List<String> {
    val result = mutableListOf<String>()
    val seen = HashSet<String>()
    existing.forEach { if (seen.add(it.lowercase())) result.add(it) }
    presets.forEach { if (seen.add(it.lowercase())) result.add(it) }
    return result
}
