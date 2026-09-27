package com.qq.closie.life.capture

/**
 * The resolved destination of an incoming `ACTION_SEND` intent.
 *
 * A pure value type with no Android dependency, so the decision can be unit-tested on the JVM.
 * This is the layer between [ShareRouter] (which answers "what kind of link is this?") and the UI
 * (which needs an actionable command). Keeping them separate means the routing rule can be tested
 * without an Activity, and the Activity has no branching logic of its own to get wrong.
 */
sealed interface SharedContent {

    /**
     * A shopping link — hand it to Closie's product importer.
     *
     * [text] is the *whole* shared text, not just the URL, because 淘宝 share text carries the title
     * in its surrounding boilerplate and [com.qq.closie.data.ProductImporter] knows how to read it.
     */
    data class ProductLink(val text: String, val url: String) : SharedContent

    /**
     * Any other web link — file it in 资料库.
     *
     * Both the original text and the extracted [url] are carried: the URL is what gets fetched, and
     * the original text is kept for the record so the user can see what was actually shared.
     */
    data class ReferenceLink(val text: String, val url: String) : SharedContent

    /** No URL at all — a note or snippet, which belongs in 记录 like any clipboard save. */
    data class PlainText(val text: String) : SharedContent
}

/**
 * Turns raw shared text into a [SharedContent].
 *
 * Exists as a named, testable function rather than as an inline `when` in `MainActivity` because
 * getting this wrong is *silent*: a 淘宝 link routed to 资料库 looks completely normal in the UI —
 * the only symptom is that "share to app to add to wardrobe", a habit the user already has, quietly
 * stops working, and they notice weeks later. A pure function can be tested exhaustively for the
 * few cases that matter; an Activity callback cannot.
 */
object ExternalIntentRouter {

    /**
     * Routes `ACTION_SEND` text.
     *
     * Blank input is [SharedContent.PlainText] rather than an error — the caller checks for blank
     * before calling, and inventing a fourth "invalid" state here would only push an unreachable
     * branch into every `when`.
     */
    fun route(text: String): SharedContent {
        val url = ShareRouter.urlIn(text) ?: return SharedContent.PlainText(text)
        return if (ShareRouter.isProductUrl(url)) {
            SharedContent.ProductLink(text = text, url = url)
        } else {
            SharedContent.ReferenceLink(text = text, url = url)
        }
    }
}
