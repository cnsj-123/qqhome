package com.qq.closie.life.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The share-routing decision, pinned.
 *
 * Why this is a test and not a comment: the router decides which of three *features* a shared piece
 * of text opens, and getting it wrong is invisible in review and nearly invisible in manual testing.
 * A Taobao link routed to 资料库 instead of the product importer looks like a working app — the item
 * just lands in the wrong place, and the user's existing "share from Taobao" habit silently stops
 * doing what it has always done. The bug surfaces weeks later as "why does sharing a product no
 * longer add it to my closet?".
 *
 * The ordering matters too: PRODUCT_LINK must be checked before WEB_LINK, since every product URL is
 * also a valid http(s) URL.
 */
class ShareRouterTest {

    // ---- The three destinations --------------------------------------------------

    @Test
    fun `no url at all routes to plain text`() {
        assertThat(ShareRouter.route("今天下午三点开会")).isEqualTo(ShareRouter.Destination.PLAIN_TEXT)
        assertThat(ShareRouter.route("")).isEqualTo(ShareRouter.Destination.PLAIN_TEXT)
        assertThat(ShareRouter.route("   ")).isEqualTo(ShareRouter.Destination.PLAIN_TEXT)
    }

    @Test
    fun `an ordinary article link routes to the web library`() {
        assertThat(ShareRouter.route("https://sspai.com/post/12345"))
            .isEqualTo(ShareRouter.Destination.WEB_LINK)
        assertThat(ShareRouter.route("看看这篇 https://zhuanlan.zhihu.com/p/999"))
            .isEqualTo(ShareRouter.Destination.WEB_LINK)
    }

    @Test
    fun `a product link routes to the product importer`() {
        assertThat(ShareRouter.route("https://item.taobao.com/item.htm?id=1"))
            .isEqualTo(ShareRouter.Destination.PRODUCT_LINK)
        assertThat(ShareRouter.route("https://item.jd.com/10086.html"))
            .isEqualTo(ShareRouter.Destination.PRODUCT_LINK)
    }

    // ---- The real-world share text, which is never just a URL --------------------

    @Test
    fun `a real taobao share message still routes to the product importer`() {
        // This is the shape that broke the old `startsWith("http")` test: the URL is buried inside
        // Chinese boilerplate, so a prefix check returned false and the link was filed as text.
        val shared = "【淘宝】某某商品 限时优惠…… 复制此消息，打开淘宝 https://m.tb.cn/h.abcdef 打开淘宝"
        assertThat(ShareRouter.route(shared)).isEqualTo(ShareRouter.Destination.PRODUCT_LINK)
    }

    @Test
    fun `a real article share message routes to the web library`() {
        val shared = "推荐阅读：如何整理你的截图 https://sspai.com/post/12345 （来自少数派）"
        assertThat(ShareRouter.route(shared)).isEqualTo(ShareRouter.Destination.WEB_LINK)
    }

    // ---- Host matching -----------------------------------------------------------

    @Test
    fun `product host suffixes match subdomains but not lookalikes`() {
        assertThat(ShareRouter.isProductUrl("https://m.tb.cn/x")).isTrue()
        assertThat(ShareRouter.isProductUrl("https://item.taobao.com/x")).isTrue()
        assertThat(ShareRouter.isProductUrl("https://www.tmall.com/x")).isTrue()

        // The old substring check would have matched "taobao.com.evil.test" — the domain is only
        // the product domain when the *suffix* after the dot is the real one.
        assertThat(ShareRouter.isProductUrl("https://taobao.com.evil.test/x")).isFalse()
        assertThat(ShareRouter.isProductUrl("https://nottaobao.com/x")).isFalse()
        assertThat(ShareRouter.isProductUrl("https://example.com/taobao.com")).isFalse()
    }

    @Test
    fun `host extraction normalises case and www`() {
        assertThat(ShareRouter.hostOf("https://WWW.Taobao.COM/item")).isEqualTo("taobao.com")
        assertThat(ShareRouter.hostOf("https://m.tb.cn/x")).isEqualTo("m.tb.cn")
        assertThat(ShareRouter.hostOf("not a url")).isNull()
    }

    // ---- urlIn -------------------------------------------------------------------

    @Test
    fun `urlIn returns the embedded url or null`() {
        assertThat(ShareRouter.urlIn("【淘宝】… https://m.tb.cn/abc 打开淘宝"))
            .isEqualTo("https://m.tb.cn/abc")
        assertThat(ShareRouter.urlIn("纯文本")).isNull()
    }

    // ---- content platforms are NOT product platforms ------------------------------

    /**
     * 小红书 and 抖音 are content platforms, not shops.
     *
     * They were previously listed as product domains, which meant a gardening tutorial shared from
     * 小红书 became a "商品" waiting for a price and a size — while the 资料库, the feature built for
     * exactly that material, never saw any of it. The platform does not tell you what was shared;
     * only a product share format would, and that detection is deferred (see LIFE_OS_ROADMAP).
     */
    @Test
    fun `xiaohongshu and douyin are not product hosts`() {
        assertThat(ShareRouter.isProductUrl("https://www.xiaohongshu.com/discovery/item/abc")).isFalse()
        assertThat(ShareRouter.isProductUrl("https://xhslink.com/a/bCdEf")).isFalse()
        assertThat(ShareRouter.isProductUrl("https://www.douyin.com/video/123")).isFalse()
        assertThat(ShareRouter.isProductUrl("https://v.iesdouyin.com/share/video/123")).isFalse()
    }

    @Test
    fun `a xiaohongshu note routes to the reference library`() {
        val shared = "99 这招真的有用，园艺小白也能养活 https://www.xiaohongshu.com/discovery/item/abc123"
        assertThat(ShareRouter.route(shared)).isEqualTo(ShareRouter.Destination.WEB_LINK)
    }

    @Test
    fun `a douyin tutorial routes to the reference library`() {
        val shared = "旅行攻略｜三天两夜怎么玩 https://www.douyin.com/video/1234567890"
        assertThat(ShareRouter.route(shared)).isEqualTo(ShareRouter.Destination.WEB_LINK)
    }

    @Test
    fun `shopping platforms still route to the wardrobe`() {
        // The other half of the same rule: narrowing the list must not have broken the platforms
        // that genuinely are shops, because 淘宝 → 衣橱 is a habit users already have.
        assertThat(ShareRouter.isProductUrl("https://item.taobao.com/item.htm?id=1")).isTrue()
        assertThat(ShareRouter.isProductUrl("https://detail.tmall.com/item.htm?id=1")).isTrue()
        assertThat(ShareRouter.isProductUrl("https://item.jd.com/10086.html")).isTrue()
        assertThat(ShareRouter.isProductUrl("https://mobile.yangkeduo.com/goods.html")).isTrue()
        assertThat(ShareRouter.isProductUrl("https://www.dewu.com/detail/1")).isTrue()
        assertThat(ShareRouter.isProductUrl("https://www.amazon.co.jp/dp/B0")).isTrue()
    }
}
