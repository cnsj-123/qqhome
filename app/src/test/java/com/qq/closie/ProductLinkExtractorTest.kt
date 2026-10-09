package com.qq.closie

import com.qq.closie.data.ProductImporter
import com.qq.closie.data.ProductLinkExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductLinkExtractorTest {

    @Test
    fun `extracts a plain url`() {
        assertEquals("https://m.tb.cn/abc", ProductLinkExtractor.extractFirstHttpUrl("https://m.tb.cn/abc"))
    }

    @Test
    fun `extracts url from a taobao share message`() {
        val text = "【淘宝】某某商品…… 复制此消息，打开淘宝 https://m.tb.cn/abc 打开淘宝"
        assertEquals("https://m.tb.cn/abc", ProductLinkExtractor.extractFirstHttpUrl(text))
    }

    @Test
    fun `extracts multiple urls`() {
        val urls = ProductLinkExtractor.extractHttpUrls("看看 https://a.com 还有 https://b.com 也不错")
        assertEquals(listOf("https://a.com", "https://b.com"), urls)
    }

    @Test
    fun `strips trailing chinese punctuation`() {
        assertEquals("https://m.tb.cn/abc", ProductLinkExtractor.cleanUrl("https://m.tb.cn/abc，"))
        assertEquals("https://m.tb.cn/abc", ProductLinkExtractor.cleanUrl("https://m.tb.cn/abc。"))
        assertEquals("https://m.tb.cn/abc", ProductLinkExtractor.cleanUrl("https://m.tb.cn/abc）"))
        assertEquals("https://m.tb.cn/abc", ProductLinkExtractor.extractFirstHttpUrl("（https://m.tb.cn/abc）打开淘宝"))
    }

    @Test
    fun `keeps query params intact`() {
        assertEquals("https://a.com/x?id=1&y=2", ProductLinkExtractor.cleanUrl("https://a.com/x?id=1&y=2，"))
    }

    @Test
    fun `returns nothing when there is no url`() {
        assertNull(ProductLinkExtractor.extractFirstHttpUrl("这里没有链接"))
        assertTrue(ProductLinkExtractor.extractHttpUrls("纯文本，无链接").isEmpty())
    }

    // ---- Platform detection (pure, no network) ----

    @Test
    fun `short taobao links detect as taobao`() {
        assertEquals("淘宝", ProductImporter.detectPlatform("https://m.tb.cn/abc"))
        assertEquals("淘宝", ProductImporter.detectPlatform("https://tb.cn/abc"))
        assertEquals("淘宝", ProductImporter.detectPlatform("https://item.taobao.com/item.htm?id=1"))
    }

    @Test
    fun `marketplace hosts detect their platforms`() {
        assertEquals("天猫", ProductImporter.detectPlatform("https://detail.tmall.com/item.htm"))
        assertEquals("京东", ProductImporter.detectPlatform("https://item.jd.com/1.html"))
        assertEquals("京东", ProductImporter.detectPlatform("https://3.cn/abc"))
        assertEquals("小红书", ProductImporter.detectPlatform("https://xhslink.com/abc"))
    }

    // ---- Share-text title cleanup (pure, no network) ----

    @Test
    fun `share text title strips boilerplate and platform markers`() {
        val text = "【淘宝】花满絮全开襟古法旗袍蓝色 复制此消息打开淘宝 https://m.tb.cn/xxxx"
        assertEquals("花满絮全开襟古法旗袍蓝色", ProductImporter.parseShareText(text).title)
    }

    @Test
    fun `share text keeps the platform and url even without a title`() {
        val p = ProductImporter.parseShareText("复制链接 https://tb.cn/x 打开淘宝")
        assertEquals("淘宝", p.platform)
        assertEquals("https://tb.cn/x", p.url)
    }

    @Test
    fun `share text without a url yields empty preview`() {
        assertTrue(ProductImporter.parseShareText("没有链接的纯文本").isEmpty)
    }

    // ---- Anti-bot title rejection (pure) ----

    @Test
    fun `anti-bot titles are rejected`() {
        assertTrue(ProductImporter.isAntiBotTitle("登录淘宝"))
        assertTrue(ProductImporter.isAntiBotTitle("安全验证"))
        assertTrue(ProductImporter.isAntiBotTitle("请输入验证码"))
        assertTrue(ProductImporter.isAntiBotTitle("Access Denied"))
        assertTrue(ProductImporter.isAntiBotTitle("Forbidden"))
        assertTrue(ProductImporter.isAntiBotTitle("Just a moment..."))
        assertTrue(ProductImporter.isAntiBotTitle(""))
    }

    @Test
    fun `real product titles are accepted`() {
        assertTrue(!ProductImporter.isAntiBotTitle("花满絮全开襟古法旗袍蓝色"))
        assertTrue(!ProductImporter.isAntiBotTitle("Uniqlo U Crew Neck T-Shirt"))
    }
}
