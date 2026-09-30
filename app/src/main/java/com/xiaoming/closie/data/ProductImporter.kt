package com.xiaoming.closie.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.HttpURLConnection
import java.net.URL

/** Best-effort parsed product metadata from a store page, ready to pre-fill the editor. */
data class ProductPreview(
    val title: String = "",
    val price: Double? = null,
    val originalPrice: Double? = null,
    val brand: String = "",
    val store: String = "",
    val platform: String = "",
    val imageUrl: String? = null,
    val url: String = ""
) {
    val isEmpty: Boolean
        get() = title.isBlank() && price == null && originalPrice == null &&
            brand.isBlank() && store.isBlank() && imageUrl == null && url.isBlank()

    val hasAny: Boolean
        get() = !isEmpty
}

/**
 * Layered, best-effort product import. It is intentionally *not* an all-or-nothing scrape:
 *
 *   input text → extract URL → resolve short link → detect platform → try page metadata
 *              → parse share text → fill whatever we found → preview → user confirms.
 *
 * Taobao / JD / Xiaohongshu pages are frequently JS-rendered, anti-bot or login-walled, so a page
 * parse failure must never fail the whole import. We fall back to rule-based share-text parsing
 * and always surface at least the resolved URL and detected platform. Runs on IO; never throws.
 */
object ProductImporter {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /** Import entry point: accepts a raw URL or an arbitrary share message. */
    suspend fun import(text: String): Result<ProductPreview> = withContext(Dispatchers.IO) {
        runCatching {
            val url = ProductLinkExtractor.extractFirstHttpUrl(text)
            val fromShare = parseShareText(text)
            if (url == null) {
                if (fromShare.hasAny) return@runCatching fromShare
                else throw IllegalStateException("未在文本中找到商品链接")
            }
            val resolved = resolveRedirect(url)
            val platform = detectPlatform(resolved)
            val fromWeb = runCatching { fetchPage(resolved) }.getOrDefault(ProductPreview())
            // A web title only wins when it is clearly a real product title, never an anti-bot or
            // login-wall page. Share-text fields always survive as fallback so a page failure can
            // never turn the whole import into a failure.
            val webTitleUsable = fromWeb.title.isNotBlank() && !isAntiBotTitle(fromWeb.title)
            ProductPreview(
                title = if (webTitleUsable) fromWeb.title else fromShare.title,
                price = fromWeb.price ?: fromShare.price,
                originalPrice = fromWeb.originalPrice ?: fromShare.originalPrice,
                brand = if (!isAntiBotTitle(fromWeb.brand)) fromWeb.brand else fromShare.brand,
                store = if (!isAntiBotTitle(fromWeb.store)) fromWeb.store else fromShare.store,
                platform = fromWeb.platform.ifBlank { platform },
                imageUrl = fromWeb.imageUrl ?: fromShare.imageUrl,
                url = resolved
            )
        }
    }

    /** Imports from a single already-known URL (kept for callers that only have a URL). */
    suspend fun fetch(url: String): Result<ProductPreview> = withContext(Dispatchers.IO) {
        runCatching {
            val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
            if (scheme != "http" && scheme != "https") {
                throw IllegalStateException("仅支持 http/https 链接")
            }
            val resolved = resolveRedirect(url)
            fetchPage(resolved).let { p ->
                p.copy(url = resolved, platform = p.platform.ifBlank { detectPlatform(resolved) })
            }
        }
    }

    /** Fetches a page and extracts metadata; returns a partial preview rather than throwing. */
    private fun fetchPage(url: String): ProductPreview {
        val doc = Jsoup.connect(url)
            .userAgent(UA)
            .timeout(15_000)
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .get()

        val platform = detectPlatform(url)

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

        val ld = parseJsonLd(doc)
        if (ld.title.isNotBlank()) title = ld.title
        ld.price?.let { price = it }
        ld.originalPrice?.let { originalPrice = it }
        if (ld.brand.isNotBlank()) brand = ld.brand
        if (ld.store.isNotBlank()) store = ld.store
        if (!ld.imageUrl.isNullOrBlank()) imageUrl = ld.imageUrl

        return ProductPreview(
            title = title,
            price = price,
            originalPrice = originalPrice,
            brand = brand,
            store = store,
            platform = platform,
            imageUrl = imageUrl?.let { resolveUrl(url, it) },
            url = url
        )
    }

    /**
     * True when a page title is an obvious anti-bot / login-wall marker rather than a real product
     * name. Used to stop such titles from overriding a valid share-text title.
     */
    fun isAntiBotTitle(title: String): Boolean {
        val t = title.trim().lowercase()
        if (t.isBlank()) return true
        val markers = listOf(
            "登录淘宝", "登录天猫", "登录京东", "登录", "安全验证", "验证码", "请完成验证",
            "滑动验证", "人机验证", "系统繁忙", "访问过于频繁", "请稍后重试",
            "access denied", "forbidden", "just a moment", "verify you are human",
            "captcha", "unusual traffic"
        )
        return markers.any { t.contains(it) }
    }

    /** Best-effort short-link / redirect resolution. Never bypasses login, captcha or anti-bot. */
    suspend fun resolveRedirect(url: String): String = withContext(Dispatchers.IO) {
        var current = url
        repeat(5) {
            val next = followOneRedirect(current) ?: return@withContext current
            current = next
        }
        current
    }

    private fun followOneRedirect(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", UA)
        try {
            val code = conn.responseCode
            if (code in 300..399) {
                conn.getHeaderField("Location")?.takeIf { it.isNotBlank() }?.let { loc ->
                    java.net.URI(url).resolve(loc).toString()
                }
            } else null
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** Rule-based title/platform extraction from a pasted share message (no AI, no network). */
    fun parseShareText(text: String): ProductPreview {
        val url = ProductLinkExtractor.extractFirstHttpUrl(text) ?: return ProductPreview()
        val platform = detectPlatform(url)
        return ProductPreview(title = extractTitleCandidate(text), platform = platform, url = url)
    }

    /** Removes URLs, platform markers and share boilerplate before trimming out the title. */
    private fun extractTitleCandidate(text: String): String {
        var s = text.replace(HTTP_URL_REGEX, " ")
        s = s.replace(Regex("""【[^】]{1,20}】"""), " ")
        BOILERPLATE_PHRASES.forEach { phrase -> s = s.replace(phrase, " ") }
        return s.split(Regex("[\r\n]+"))
            .map { it.trim().trim('，', '。', ' ', '！', '？', '：', '；', ',', '.', '|', '-', '—', '…') }
            .firstOrNull { line ->
                line.length in 2..80 &&
                    !line.all { it.isDigit() || it in "￥¥., \t" }
            }.orEmpty()
    }

    private val HTTP_URL_REGEX = Regex("""https?://[^\s"'<>()\[\]{}，。、；：！？【】「」『』“”‘’…]+""")

    private val BOILERPLATE_PHRASES = listOf(
        "复制此消息", "复制这条消息", "复制整段", "复制链接", "复制口令",
        "打开淘宝", "打开天猫", "打开京东", "打开拼多多", "打开抖音", "打开小红书",
        "点击链接", "淘宝搜索", "分享自", "復zhi", "復製", "立即查看", "快来", "去淘宝"
    ).sortedByDescending { it.length }

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

    /** Expanded platform detection covering Chinese marketplaces plus the listed fashion sites. */
    fun detectPlatform(url: String): String {
        val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return when {
            "tmall.com" in host -> "天猫"
            "taobao.com" in host || "m.tb.cn" in host || "tb.cn" in host -> "淘宝"
            "jd.com" in host || "jd.hk" in host || "3.cn" in host -> "京东"
            "pinduoduo.com" in host || "yangkeduo.com" in host -> "拼多多"
            "xiaohongshu.com" in host || "xhslink.com" in host || "xhscdn.com" in host -> "小红书"
            "dewu.com" in host || "poizon.com" in host -> "得物"
            "douyin.com" in host || "iesdouyin.com" in host -> "抖音"
            "amazon." in host -> "Amazon"
            "farfetch." in host -> "Farfetch"
            "ssense." in host -> "SSENSE"
            "endclothing.com" in host || host == "end." -> "END."
            "zozo.jp" in host -> "ZOZOTOWN"
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
