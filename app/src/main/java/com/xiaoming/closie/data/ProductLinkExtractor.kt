package com.xiaoming.closie.data

/**
 * Extracts clean http(s) URLs from free-form pasted text: share messages, notes, chat forward
 * snippets, etc. Pure functions (no Android dependencies) so they can be unit-tested in isolation.
 *
 * Typical input looks like:
 *
 *   【淘宝】某某商品…… 复制此消息，打开淘宝 https://m.tb.cn/xxxx
 *
 * and the only field we want out of it is `https://m.tb.cn/xxxx`.
 */
object ProductLinkExtractor {

    /** Characters that are valid inside a URL (RFC 3986 unreserved + reserved). */
    private val URL_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#[]@!$&'()*+,;=%"

    /**
     * A URL starts at an http/https scheme and runs until whitespace, quotes, angle brackets,
     * parentheses, or common CJK punctuation that is never part of a real URL.
     */
    private val HTTP_URL_REGEX =
        Regex("""https?://[^\s"'<>()\[\]{}，。、；：！？【】「」『』“”‘’…]+""")

    /** Returns the first http(s) URL found in [text], or null when there is none. */
    fun extractFirstHttpUrl(text: String): String? = extractHttpUrls(text).firstOrNull()

    /** Returns every distinct http(s) URL found in [text], each cleaned of trailing junk. */
    fun extractHttpUrls(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        for (match in HTTP_URL_REGEX.findAll(text)) {
            val cleaned = cleanUrl(match.value)
            if (cleaned.isNotEmpty()) seen.add(cleaned)
        }
        return seen.toList()
    }

    /**
     * Strips trailing garbage that a share sheet may glue onto a URL — CJK/ASCII punctuation,
     * quotes, brackets, emoji, newlines and share wording — without ever touching query parameters
     * or the fragment.
     */
    fun cleanUrl(raw: String): String {
        var s = raw.trim()
        s = s.trim('"', '\'', '`', '\u3000', '（', '）', '【', '】', '「', '」', '“', '”', '‘', '’')
        while (s.isNotEmpty() && s.last() !in URL_CHARS) s = s.dropLast(1)
        while (s.isNotEmpty() && s.first() !in URL_CHARS) s = s.drop(1)
        // A trailing "." / "," is almost always sentence punctuation, never a query param.
        while (s.endsWith(".") || s.endsWith(",")) s = s.dropLast(1)
        return s
    }
}
