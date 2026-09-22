package com.xiaoming.closie.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Best-effort parsed product metadata from a store page, ready to pre-fill the editor. */
data class ProductPreview(
    val title: String = "",
    val price: Double? = null,
    val originalPrice: Double? = null,
    val brand: String = "",
    val store: String = "",
    val platform: String = "",
    val imageUrl: String? = null
) {
    val isEmpty: Boolean
        get() = title.isBlank() && price == null && originalPrice == null &&
            brand.isBlank() && store.isBlank() && imageUrl == null
}

/**
 * Fetches a product page and extracts metadata from JSON-LD (preferred) with Open Graph / meta
 * tags as fallback. Runs on the IO dispatcher and never throws; failures surface via [Result].
 */
object ProductImporter {
    suspend fun fetch(url: String): Result<ProductPreview> = withContext(Dispatchers.IO) {
        runCatching {
            val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
            if (scheme != "http" && scheme != "https") {
                throw IllegalStateException("仅支持 http/https 链接")
            }
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
                .timeout(15_000)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .get()

            val platform = detectPlatform(url)

            // Open Graph / meta fallbacks.
            var title = meta(doc, "og:title")
                .ifBlank { meta(doc, "twitter:title") }
                .ifBlank { doc.title() }
                .trim()

            var price = priceOf(
                meta(doc, "og:price:amount"),
                meta(doc, "product:price:amount"),
                meta(doc, "product:price"),
                meta(doc, "og:price"),
                itemprop(doc, "price")
            )

            var originalPrice = priceOf(
                meta(doc, "og:price:standard_amount"),
                meta(doc, "product:original_price:amount"),
                meta(doc, "product:original_price")
            )

            var brand = meta(doc, "og:brand")
                .ifBlank { meta(doc, "product:brand") }
                .ifBlank { metaName(doc, "brand") }
                .ifBlank { itemprop(doc, "brand") }

            var store = meta(doc, "og:store")
                .ifBlank { meta(doc, "product:retailer") }
                .ifBlank { meta(doc, "og:site_name") }

            var imageUrl = meta(doc, "og:image")
                .ifBlank { meta(doc, "twitter:image") }
                .ifBlank { itemprop(doc, "image") }
                .takeIf { it.isNotBlank() }

            // JSON-LD Product schema takes precedence when present.
            val ld = parseJsonLd(doc)
            if (ld.title.isNotBlank()) title = ld.title
            ld.price?.let { price = it }
            ld.originalPrice?.let { originalPrice = it }
            if (ld.brand.isNotBlank()) brand = ld.brand
            if (ld.store.isNotBlank()) store = ld.store
            if (!ld.imageUrl.isNullOrBlank()) imageUrl = ld.imageUrl

            // Resolve protocol-relative / relative image URLs against the product page URL.
            val resolvedImage = imageUrl?.let { resolveUrl(url, it) }

            if (title.isBlank() && price == null && originalPrice == null &&
                brand.isBlank() && store.isBlank() && resolvedImage == null
            ) {
                throw IllegalStateException("未能从该页面解析出商品信息")
            }
            ProductPreview(
                title = title,
                price = price,
                originalPrice = originalPrice,
                brand = brand,
                store = store,
                platform = platform,
                imageUrl = resolvedImage
            )
        }
    }

    private fun resolveUrl(pageUrl: String, imageUrl: String): String {
        val trimmed = imageUrl.trim()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.startsWith("//")) return "https:" + trimmed
        return runCatching { java.net.URI(pageUrl).resolve(trimmed).toString() }.getOrDefault(trimmed)
    }

    private fun meta(doc: Document, property: String): String =
        doc.select("meta[property=$property]").first()?.attr("content").orEmpty().trim()

    private fun metaName(doc: Document, name: String): String =
        doc.select("meta[name=$name]").first()?.attr("content").orEmpty().trim()

    private fun itemprop(doc: Document, key: String): String =
        doc.select("[itemprop=$key]").first()?.attr("content").orEmpty().trim()

    private fun priceOf(vararg candidates: String): Double? =
        candidates.firstOrNull { it.isNotBlank() }?.let { raw ->
            raw.replace("¥", "").replace(",", "").trim().toDoubleOrNull()
        }

    private fun detectPlatform(url: String): String {
        val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return when {
            "tmall.com" in host -> "天猫"
            "taobao.com" in host -> "淘宝"
            "jd.com" in host || "jd.hk" in host -> "京东"
            "amazon." in host -> "Amazon"
            "uniqlo." in host -> "Uniqlo"
            "zara." in host -> "Zara"
            host.isBlank() -> ""
            else -> host.removePrefix("www.")
        }
    }

    private fun parseJsonLd(doc: Document): ProductPreview {
        for (script in doc.select("script[type=application/ld+json]")) {
            val root = runCatching { JsonParser.parseString(script.data()) }.getOrNull() ?: continue
            val objects = mutableListOf<JsonObject>()
            collectObjects(root, objects)
            val product = objects.firstOrNull { isProduct(it) }
                ?: objects.firstOrNull {
                    it.stringOf("name").isNotBlank() || it.get("offers") != null || it.get("image") != null
                }
                ?: continue

            val name = product.stringOf("name")
            val brand = brandOf(product.get("brand"))
            val image = imageOf(product.get("image"))
            val offers = product.get("offers").firstObject()
            val price = moneyOf(offers?.get("price")) ?: moneyOf(offers?.get("lowPrice"))
            val originalPrice = moneyOf(offers?.get("highPrice"))?.takeIf { price == null || it > price }
            val store = sellerOf(offers?.get("seller"))

            if (name.isNotBlank() || brand.isNotBlank() || image != null || price != null) {
                return ProductPreview(
                    title = name,
                    price = price,
                    originalPrice = originalPrice,
                    brand = brand,
                    store = store,
                    imageUrl = image
                )
            }
        }
        return ProductPreview()
    }

    private fun collectObjects(element: JsonElement, out: MutableList<JsonObject>) {
        when {
            element.isJsonObject -> {
                out += element.asJsonObject
                element.asJsonObject.entrySet().forEach { (_, v) -> collectObjects(v, out) }
            }
            element.isJsonArray -> element.asJsonArray.forEach { collectObjects(it, out) }
        }
    }

    private fun isProduct(obj: JsonObject): Boolean {
        val type = obj.get("@type") ?: return false
        val names = when {
            type.isJsonArray -> type.asJsonArray.mapNotNull { it.stringOrNull() }
            else -> listOfNotNull(type.stringOrNull())
        }
        return names.any { it.contains("Product", ignoreCase = true) }
    }

    private fun imageOf(element: JsonElement?): String? = when {
        element == null || element.isJsonNull -> null
        element.isJsonPrimitive -> element.asString.takeIf { it.isNotBlank() }
        element.isJsonArray -> element.asJsonArray.firstNotNullOfOrNull { imageOf(it) }
        element.isJsonObject -> element.asJsonObject.stringOf("url").ifBlank { null }
        else -> null
    }

    private fun moneyOf(element: JsonElement?): Double? = when {
        element == null || element.isJsonNull -> null
        element.isJsonPrimitive -> {
            val p = element.asJsonPrimitive
            when {
                p.isNumber -> p.asDouble
                p.isString -> priceOf(p.asString)
                else -> null
            }
        }
        else -> null
    }

    private fun brandOf(element: JsonElement?): String = when {
        element == null || element.isJsonNull -> ""
        element.isJsonPrimitive -> element.asString.trim()
        element.isJsonObject -> element.asJsonObject.stringOf("name")
        element.isJsonArray -> element.asJsonArray.firstNotNullOfOrNull { brandOf(it).takeIf { b -> b.isNotBlank() } }.orEmpty()
        else -> ""
    }

    private fun sellerOf(element: JsonElement?): String = when {
        element == null || element.isJsonNull -> ""
        element.isJsonPrimitive -> element.asString.trim()
        element.isJsonObject -> element.asJsonObject.stringOf("name")
        element.isJsonArray -> element.asJsonArray.firstNotNullOfOrNull { sellerOf(it).takeIf { s -> s.isNotBlank() } }.orEmpty()
        else -> ""
    }

    private fun JsonObject.stringOf(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().trim()

    private fun JsonElement.stringOrNull(): String? =
        if (isJsonPrimitive) asString else null

    private fun JsonElement?.firstObject(): JsonObject? = when {
        this == null || isJsonNull -> null
        isJsonObject -> asJsonObject
        isJsonArray -> asJsonArray.firstNotNullOfOrNull { it.firstObject() }
        else -> null
    }
}
