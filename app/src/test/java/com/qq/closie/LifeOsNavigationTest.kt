package com.qq.closie

import com.qq.closie.navigation.*
import com.qq.closie.ui.lifeos.drawer.LifeModules
import org.junit.Assert.*
import org.junit.Test

class LifeOsNavigationTest {
    @Test fun rootAndRealModulesHaveOneCatalog() {
        assertEquals("life/home", LifeOsRoute.DEFAULT)
        val modules = LifeModules.groups.flatMap { it.modules }.associateBy { it.id }
        assertEquals(4, LifeModules.groups.size)
        assertEquals(modules.size, LifeModules.groups.sumOf { it.modules.size })
        mapOf("closet" to LifeOsRoute.CLOSET, "capture" to LifeOsRoute.CAPTURE,
            "inbox" to LifeOsRoute.CAPTURE, "backup" to LifeOsRoute.BACKUP,
            "knowledge" to LifeOsRoute.REFERENCE, "reading" to LifeOsRoute.READING,
            "plans" to LifeOsRoute.PLAN).forEach { (id, route) -> assertEquals(route, modules.getValue(id).route) }
        assertEquals(LifeOsRoute.module("finance"), modules.getValue("finance").route)
    }
    @Test fun sharedContentKeepsItsIntakeSemantics() {
        val product = ExternalCommandResolver.parse("android.intent.action.SEND", "https://item.taobao.com/item.htm?id=1", null, false, 1)
        val reference = ExternalCommandResolver.parse("android.intent.action.SEND", "看看 https://example.com/article", null, false, 2)
        val plain = ExternalCommandResolver.parse("android.intent.action.SEND", "记住这句话", null, false, 3)
        assertTrue(product is ExternalNavCommand.ProductImport)
        assertEquals(ExternalNavCommand.ReferenceLink("看看 https://example.com/article", "https://example.com/article", 2), reference)
        assertEquals(ExternalNavCommand.CaptureText("记住这句话", 3), plain)
        assertTrue(ExternalCommandResolver.isClosetCommand(product!!))
        assertFalse(ExternalCommandResolver.isClosetCommand(reference!!))
        assertFalse(ExternalCommandResolver.isClosetCommand(plain!!))
    }
    @Test fun editAndAddStillBelongToCloset() {
        val edit = ExternalCommandResolver.parse(null, null, "item /中文", true, 4)
        val add = ExternalCommandResolver.parse(null, null, null, true, 5)
        assertEquals(ExternalNavCommand.Edit("item /中文", 4), edit)
        assertEquals(ExternalNavCommand.Add(5), add)
        assertTrue(ExternalCommandResolver.isClosetCommand(edit!!))
        assertTrue(ExternalCommandResolver.isClosetCommand(add!!))
        assertEquals("life/reference/item%20%2F%E4%B8%AD%E6%96%87/edit", LifeOsRoute.referenceEdit("item /中文"))
    }
    @Test fun ordinaryLaunchHasNoCommand() {
        assertNull(ExternalCommandResolver.parse("android.intent.action.MAIN", null, null, false, 1))
        assertNull(ExternalCommandResolver.parse("android.intent.action.SEND", "  ", "", false, 2))
        assertNull(ExternalCommandResolver.parse(null, "not a share", null, false, 3))
    }
}
