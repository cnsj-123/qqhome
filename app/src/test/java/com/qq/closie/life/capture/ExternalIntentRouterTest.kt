package com.qq.closie.life.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for the share → destination decision.
 *
 * These are *production-routing* tests, not pure-function tests of [ShareRouter] alone. The bug
 * this file exists to catch is not "is the domain list right" but "is the router actually wired
 * in": [ShareRouter] was previously referenced only from its own test — dead code — while every
 * `ACTION_SEND` went straight to Closie's product importer. A user sharing a 知乎 article got the
 * "add a garment" form. [ExternalIntentRouter] is the junction that makes the decision reachable
 * from `MainActivity`, so it is what gets tested.
 *
 * Plain JVM tests: no Robolectric, no Android types, no network. The whole reason the routing logic
 * is a pair of pure objects is so this file can be fast and exhaustive.
 */
class ExternalIntentRouterTest {

    // ------------------------------------------------------------------
    //  商品链接 → ProductImport
    // ------------------------------------------------------------------

    @Test
    fun taobaoShare_routesToProductImport() {
        // The real shape of a 淘宝 share: boilerplate around a short link.
        val shared = "【淘宝】https://m.tb.cn/h.gXyZ 点击链接直接打开 或者 淘宝搜索直接打开"
        val routed = ExternalIntentRouter.route(shared)
        assertThat(routed).isInstanceOf(SharedContent.ProductLink::class.java)
        assertThat((routed as SharedContent.ProductLink).url).isEqualTo("https://m.tb.cn/h.gXyZ")
        // The whole text is carried, not just the URL: the product importer reads the title out of
        // that surrounding boilerplate.
        assertThat(routed.text).isEqualTo(shared)
    }

    @Test
    fun jdLink_routesToProductImport() {
        val routed = ExternalIntentRouter.route("https://item.jd.com/10086.html")
        assertThat(routed).isInstanceOf(SharedContent.ProductLink::class.java)
    }

    // ------------------------------------------------------------------
    //  普通网页 → ReferenceLink
    // ------------------------------------------------------------------

    @Test
    fun ordinaryWebPage_routesToReference() {
        val routed = ExternalIntentRouter.route("https://sspai.com/post/12345")
        assertThat(routed).isInstanceOf(SharedContent.ReferenceLink::class.java)
        assertThat((routed as SharedContent.ReferenceLink).url).isEqualTo("https://sspai.com/post/12345")
    }

    @Test
    fun zhihuAnswer_routesToReference() {
        val routed = ExternalIntentRouter.route("看看这篇 https://zhuanlan.zhihu.com/p/999")
        assertThat(routed).isInstanceOf(SharedContent.ReferenceLink::class.java)
    }

    @Test
    fun blogPostWithSurroundingText_extractsUrlAndRoutesToReference() {
        val routed = ExternalIntentRouter.route("推荐这个教程 https://example.com/gardening 很有用")
        assertThat(routed).isInstanceOf(SharedContent.ReferenceLink::class.java)
        assertThat((routed as SharedContent.ReferenceLink).url).isEqualTo("https://example.com/gardening")
        // The original text survives so the capture can record what the user actually shared.
        assertThat(routed.text).contains("推荐这个教程")
    }

    // ------------------------------------------------------------------
    //  内容平台必须是资料库，不是商品
    // ------------------------------------------------------------------

    @Test
    fun xiaohongshuTutorial_routesToReference_notProduct() {
        // A 小红书 share is a note, not a product. The platform being 小红书 says nothing about
        // whether the note sells something, and users save 教程 / 攻略 / 灵感 in far greater volume
        // than products. Routing these to the wardrobe turned a gardening tutorial into a 商品
        // waiting for a price and a size.
        val shared = "99 这招真的有用，园艺小白也能养活 https://www.xiaohongshu.com/discovery/item/abc123"
        val routed = ExternalIntentRouter.route(shared)
        assertThat(routed).isInstanceOf(SharedContent.ReferenceLink::class.java)
    }

    @Test
    fun xiaohongshuShortLink_routesToReference() {
        val routed = ExternalIntentRouter.route("https://xhslink.com/a/bCdEf")
        assertThat(routed).isInstanceOf(SharedContent.ReferenceLink::class.java)
    }

    @Test
    fun douyinTutorial_routesToReference_notProduct() {
        val shared = "旅行攻略｜三天两夜怎么玩 https://www.douyin.com/video/1234567890"
        val routed = ExternalIntentRouter.route(shared)
        assertThat(routed).isInstanceOf(SharedContent.ReferenceLink::class.java)
    }

    @Test
    fun douyinShortLink_routesToReference() {
        val routed = ExternalIntentRouter.route("https://v.iesdouyin.com/share/video/123")
        assertThat(routed).isInstanceOf(SharedContent.ReferenceLink::class.java)
    }

    // ------------------------------------------------------------------
    //  纯文本 → CaptureText
    // ------------------------------------------------------------------

    @Test
    fun textWithoutUrl_routesToCapture() {
        val routed = ExternalIntentRouter.route("明天下午三点开会")
        assertThat(routed).isInstanceOf(SharedContent.PlainText::class.java)
        assertThat((routed as SharedContent.PlainText).text).isEqualTo("明天下午三点开会")
    }

    @Test
    fun blankText_routesToCaptureRatherThanCrashing() {
        // MainActivity guards blank before calling, but the router must not be the thing that
        // decides to throw — a defensive branch here is cheaper than an unreachable `when`.
        assertThat(ExternalIntentRouter.route("")).isInstanceOf(SharedContent.PlainText::class.java)
        assertThat(ExternalIntentRouter.route("   ")).isInstanceOf(SharedContent.PlainText::class.java)
    }

    @Test
    fun productLookalikeDomain_routesToReference() {
        // `nottaobao.com` ends with `taobao.com`. Label-boundary matching keeps it out of the
        // product importer; a `contains`/naive `endsWith` would have handed an unrelated (or
        // hostile) site the wardrobe flow.
        val routed = ExternalIntentRouter.route("https://nottaobao.com/item/1")
        assertThat(routed).isInstanceOf(SharedContent.ReferenceLink::class.java)
    }
}
