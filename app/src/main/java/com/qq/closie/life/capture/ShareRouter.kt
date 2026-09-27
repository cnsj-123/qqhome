package com.qq.closie.life.capture

import com.qq.closie.data.ProductLinkExtractor

/**
 * Where an incoming share belongs.
 *
 * The app has exactly one share entry point (`ACTION_SEND` / `text/plain`) but three places a piece
 * of shared text can legitimately land, and only one of them is right for any given text:
 *
 *  - [ProductLink] — a shopping-platform link. Closie already knows how to parse 淘宝 / 天猫 / 京东
 *    and the rest into a pre-filled wardrobe item, and that flow is the reason those domains were
 *    special-cased in the first place. Routing them into 资料库 instead would quietly break the
 *    existing product-import feature for every user who shares from a shopping app.
 *  - [WebLink] — any other http(s) URL. That is an article or a page, which is what 资料库 exists for.
 *  - [PlainText] — no URL at all. A note or a snippet, which belongs in 记录 like any clipboard save.
 *
 * Pure functions only: no Android types, no I/O. The routing decision is the kind of thing that is
 * easy to get subtly wrong (an ordering bug here silently sends every Taobao link to the wrong
 * feature) and impossible to see in a manual test unless you test exactly the right link — so it is
 * a unit-testable function rather than an `if` buried in an Activity callback.
 */
object ShareRouter {

    /** The three destinations, in the order the router considers them. */
    enum class Destination { PRODUCT_LINK, WEB_LINK, PLAIN_TEXT }

    /**
     * Domains whose links belong to Closie's product importer rather than to 资料库.
     *
     * **Shopping platforms only.** Every entry here is a site whose *primary purpose* is selling
     * goods, so a link to it is a product link with no further inspection needed.
     *
     * ### What used to be in this list, and why it was wrong
     *
     * An earlier revision also listed 小红书 (`xiaohongshu.com`, `xhslink.com`, `xhscdn.com`) and
     * 抖音 (`douyin.com`, `iesdouyin.com`). That conflated *the platform* with *the thing shared*.
     * These are content platforms: users save 教程, 生活信息, 园艺, 旅行攻略 and 穿搭灵感 from them in
     * far greater volume than they save products. Routing all of it into the wardrobe meant a
     * gardening tutorial became a "商品" awaiting a price and a size, and the 资料库 — the feature
     * built for exactly this material — never saw any of it.
     *
     * A user sharing a 小红书 note is overwhelmingly telling the app "save this", not "I am buying
     * this". So the platform defaults to 资料库, and product detection for it is deferred until a
     * genuine product-card share format can be recognised (documented in
     * `docs/LIFE_OS_ROADMAP.md`). Erring toward 资料库 is the cheap mistake — a misfiled product is a
     * link the user can still open, whereas a misfiled tutorial is a wardrobe entry with no meaning.
     *
     * ### Matching
     *
     * Kept as a host-suffix list so a subdomain (`item.taobao.com`, `m.tb.cn`) matches without
     * enumerating every one. The list is duplicated from [com.qq.closie.data.ProductImporter]
     * deliberately rather than derived from it: `detectPlatform` returns a *display name* (falling
     * back to the bare host for unknown sites), which is lossy for a routing decision.
     */
    private val PRODUCT_HOST_SUFFIXES = listOf(
        // ---- 中国电商 ----------------------------------------------------------------
        "tmall.com",
        "taobao.com",
        "tb.cn",
        "jd.com",
        "jd.hk",
        "3.cn",
        "pinduoduo.com",
        "yangkeduo.com",
        "dewu.com",
        "poizon.com",
        // ---- 国际电商 ----------------------------------------------------------------
        "amazon.",
        "farfetch.",
        "ssense.",
        "endclothing.com",
        "zozo.jp",
        "uniqlo.",
        "zara."
    )

    /**
     * Decides where [text] should go.
     *
     * The URL is extracted with [ProductLinkExtractor] — the same extractor the product importer
     * uses — rather than with a `startsWith("http")` test. That matters because real share text is
     * never just a URL: a Taobao share is
     * `【淘宝】某某商品…… 复制此消息，打开淘宝 https://m.tb.cn/xxxx`, and a naive prefix check on that
     * string returns false, so the link was filed as opaque text and never parsed. Using the real
     * extractor means the URL is found wherever it sits in the message.
     */
    fun route(text: String): Destination {
        val url = ProductLinkExtractor.extractFirstHttpUrl(text) ?: return Destination.PLAIN_TEXT
        return if (isProductUrl(url)) Destination.PRODUCT_LINK else Destination.WEB_LINK
    }

    /**
     * True when [url]'s host belongs to one of [PRODUCT_HOST_SUFFIXES].
     *
     * The match is on *label boundaries*, not on substrings. `host.endsWith(suffix)` alone is wrong
     * for the bare-domain entries: `nottaobao.com` ends with `taobao.com` and would be routed to the
     * product importer, so an attacker-registered lookalike — or just an unrelated site that happens
     * to end in the same letters — would open the wardrobe instead of 资料库. The correct test is
     * "the host *is* the domain, or the characters immediately before it are a dot", which is what
     * the `host == suffix || host.endsWith(".$suffix")` pair expresses.
     *
     * The suffix list also carries a few entries that end in a dot (`amazon.`, `uniqlo.`, `zara.`)
     * for domains whose TLD varies per country (`amazon.co.jp`, `amazon.de`). For those the trailing
     * dot is already the label boundary, so the same rule applies unchanged.
     */
    fun isProductUrl(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return PRODUCT_HOST_SUFFIXES.any { suffix ->
            host == suffix || host.endsWith(".$suffix") || (suffix.endsWith(".") && host.contains(suffix))
        }
    }

    /**
     * The host of [url], lowercased and stripped of `www.`, or null when it cannot be parsed.
     *
     * A small local copy rather than a call into [com.qq.closie.life.web.WebMetadataReader.Companion.hostOf]:
     * this object is deliberately free of Android and network dependencies so it can be unit-tested
     * on the JVM without Robolectric, and reaching into the reader would drag that class's jsoup and
     * HttpURLConnection imports along with it.
     */
    fun hostOf(url: String): String? {
        val trimmed = url.trim().takeIf { it.isNotEmpty() } ?: return null
        return runCatching { java.net.URI(trimmed).host }
            .getOrNull()
            ?.lowercase()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
    }

    /** The extracted URL for [text], or null. Exposed so the shell does not call the extractor twice. */
    fun urlIn(text: String): String? = ProductLinkExtractor.extractFirstHttpUrl(text)
}
