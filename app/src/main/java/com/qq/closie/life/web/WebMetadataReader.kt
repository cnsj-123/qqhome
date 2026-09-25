package com.qq.closie.life.web

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * The bare host of a URL, or null if it cannot be determined.
 *
 * A top-level function rather than a private method on [WebMetadataReader] because [WebMetadata]
 * needs it for [WebMetadata.displayTitle] / [WebMetadata.displaySource], and a data class cannot
 * reach into the reader that produced it.
 *
 * `URI.host` returns null for anything it does not consider a well-formed absolute URL — including
 * the common case of a user pasting "example.com/page" with no scheme. That case is handled by
 * retrying with an assumed `https://` prefix, because showing "example.com" beats showing the raw
 * fragment the user pasted. If both fail, `removePrefix("www.")` on a best-effort parse is still
 * preferable to returning nothing, so the failure is a null only when there is genuinely no host to
 * name.
 */
internal fun hostOf(url: String): String? {
    val trimmed = url.trim().takeIf { it.isNotEmpty() } ?: return null
    val candidates = listOf(trimmed, "https://$trimmed")
    for (candidate in candidates) {
        val host = runCatching { URI(candidate).host }.getOrNull()
        if (!host.isNullOrBlank()) return host.removePrefix("www.")
    }
    return null
}

/**
 * Metadata pulled from a saved web page. Every field is optional: a page that refuses to be read
 * still produces a usable [WebMetadata] carrying at least the URL.
 */
data class WebMetadata(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val siteName: String? = null,
    val author: String? = null,
    val fetched: Boolean = false
) {
    /**
     * The title to show when the page said nothing useful. A domain is a poor title but an honest
     * one — far better than "未命名" or an empty row, which would leave the user with a list of
     * blanks and no way to tell them apart.
     */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: (hostOf(url) ?: url)

    val displaySource: String?
        get() = siteName?.takeIf { it.isNotBlank() } ?: hostOf(url)
}

/**
 * Reads a page's title, description, site name and author for the 资料库 link capture.
 *
 * This is deliberately separate from [com.qq.closie.data.ProductImporter]. Product import answers
 * "what is this thing and what does it cost" and carries marketplace-specific parsing; clipping a
 * page for the reference library answers "what is this page called", which is a strictly smaller
 * and more general question. Sharing one extractor would mean the reference library inherits every
 * marketplace hack, and a change to Taobao parsing could break article clipping.
 *
 * **This never throws.** A timeout, a 403, an anti-bot interstitial or a page with no metadata at
 * all all resolve to a [WebMetadata] whose [WebMetadata.fetched] is false. Failing to *read* a page
 * must never stop the user from *saving* the link — the URL alone is still worth keeping.
 */
class WebMetadataReader(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val timeoutMs: Int = 15_000
) {

    suspend fun read(rawUrl: String): WebMetadata = withContext(Dispatchers.IO) {
        val url = normalize(rawUrl)
        if (url == null) {
            return@withContext WebMetadata(url = rawUrl.trim(), fetched = false)
        }
        runCatching {
            val resolved = resolveRedirects(url)
            val doc = fetch(resolved)
            val meta = extract(doc, resolved)
            meta
        }.getOrElse {
            // Could not read it. Keep the URL — that is the part the user actually gave us.
            WebMetadata(url = url, fetched = false)
        }
    }

    /**
     * Trims, adds a scheme when the user typed a bare domain, and drops anything that is not
     * http(s). Returns null for input that cannot be a web address at all.
     */
    fun normalize(raw: String): String? = withScheme(raw.trim())

    private fun withScheme(value: String): String? {
        if (value.isBlank()) return null
        val candidate = when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            // "example.com/x" — a bare domain the user pasted without its scheme.
            looksLikeBareDomain(value) -> "https://$value"
            else -> return null
        }
        val scheme = runCatching { URI(candidate).scheme?.lowercase() }.getOrNull()
        return candidate.takeIf { scheme == "http" || scheme == "https" }
    }

    /**
     * A bare domain has a first label containing a dot and no spaces. Deliberately conservative:
     * "hello world" must not become a URL, but "sspai.com/post/1" must.
     */
    private fun looksLikeBareDomain(value: String): Boolean {
        if (value.contains(' ')) return false
        val host = value.substringBefore('/')
        if (!host.contains('.')) return false
        return host.substringAfterLast('.').length >= 2
    }

    /** Follows at most a few redirect hops (short links); never bypasses a login or a captcha. */
    private suspend fun resolveRedirects(url: String): String = withContext(Dispatchers.IO) {
        var current = url
        repeat(MAX_REDIRECTS) {
            val next = followOne(current) ?: return@withContext current
            current = next
        }
        current
    }

    private fun followOne(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", userAgent)
        try {
            if (conn.responseCode in 300..399) {
                conn.getHeaderField("Location")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { loc -> runCatching { URI(url).resolve(loc).toString() }.getOrNull() }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun fetch(url: String): Document = Jsoup.connect(url)
        .userAgent(userAgent)
        .timeout(timeoutMs)
        .followRedirects(true)
        // A 404 page still has a title, and a 403 interstitial sometimes names the site. Keeping
        // the body lets us salvage a site name where a strict fetch would give us nothing.
        .ignoreHttpErrors(true)
        .get()

    private fun extract(doc: Document, url: String): WebMetadata {
        val title = firstNonBlank(
            metaProperty(doc, "og:title"),
            metaName(doc, "twitter:title"),
            doc.title()
        )
        val description = firstNonBlank(
            metaProperty(doc, "og:description"),
            metaName(doc, "description"),
            metaName(doc, "twitter:description")
        )
        val siteName = firstNonBlank(
            metaProperty(doc, "og:site_name"),
            metaName(doc, "application-name")
        )
        val author = firstNonBlank(
            metaName(doc, "author"),
            metaProperty(doc, "article:author"),
            metaProperty(doc, "og:article:author")
        )
        return WebMetadata(
            url = url,
            title = title?.takeIf { !isAntiBotTitle(it) },
            description = description,
            siteName = siteName,
            author = author,
            fetched = true
        )
    }

    private fun metaProperty(doc: Document, property: String): String? =
        doc.select("meta[property=$property]").first()?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

    private fun metaName(doc: Document, name: String): String? =
        doc.select("meta[name=$name]").first()?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    companion object {
        private const val MAX_REDIRECTS = 5

        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36"

        /** Login walls and bot checks. Such a title is worse than no title. */
        fun isAntiBotTitle(title: String): Boolean {
            val t = title.trim().lowercase()
            if (t.isBlank()) return true
            val markers = listOf(
                "登录", "安全验证", "验证码", "请完成验证", "滑动验证", "人机验证",
                "系统繁忙", "访问过于频繁", "请稍后重试", "页面不存在", "just a moment",
                "access denied", "forbidden", "captcha", "verify you are human", "unusual traffic",
                "attention required", "403 forbidden", "404 not found"
            )
            return markers.any { t.contains(it) }
        }

        /** Host without the `www.` prefix, or null when [url] is not parseable. */
        fun hostOf(url: String): String? = runCatching {
            URI(url).host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
