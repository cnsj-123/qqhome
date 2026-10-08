package com.xiaoming.closie

import com.xiaoming.closie.navigation.*
import com.xiaoming.closie.ui.lifeos.drawer.LifeModules
import org.junit.Assert.*
import org.junit.Test

class LifeOsNavigationTest {
    @Test
    fun `root defaults to Home and drawer connects real Closet Capture and Backup`() {
        assertEquals("life/home", LifeOsRoute.DEFAULT)
        val modules = LifeModules.groups.flatMap { it.modules }.associateBy { it.id }
        assertEquals(LifeOsRoute.CLOSET, modules.getValue("closet").route)
        assertEquals(LifeOsRoute.CAPTURE, modules.getValue("capture").route)
        assertEquals(LifeOsRoute.BACKUP, modules.getValue("backup").route)
        assertEquals(4, LifeModules.groups.size)
        assertEquals(LifeOsRoute.module("food"), modules.getValue("food").route)
    }

    @Test
    fun `share import edit and capture add resolve through Closet`() {
        val imported = ExternalCommandResolver.parse("android.intent.action.SEND", "分享的商品", "ignored", true, 1)
        assertEquals(ExternalNavCommand.Import("分享的商品", 1), imported)
        val edited = ExternalCommandResolver.parse(null, null, "item /中文", true, 2)
        assertEquals(ExternalNavCommand.Edit("item /中文", 2), edited)
        val added = ExternalCommandResolver.parse(null, null, null, true, 3)
        assertEquals(ExternalNavCommand.Add(3), added)
        for (command in listOf(imported!!, edited!!, added!!)) {
            assertEquals(LifeOsRoute.CLOSET, ExternalCommandResolver.rootDestination(command))
        }
        assertEquals("add/OWNED", ExternalCommandResolver.closetDestination(imported))
        assertEquals("add/OWNED", ExternalCommandResolver.closetDestination(added))
        assertEquals("edit/item%20%2F%E4%B8%AD%E6%96%87", ExternalCommandResolver.closetDestination(edited))
    }

    @Test
    fun `ordinary launch and empty share do not create an external command`() {
        assertNull(ExternalCommandResolver.parse("android.intent.action.MAIN", null, null, false, 1))
        assertNull(ExternalCommandResolver.parse("android.intent.action.SEND", "  ", "", false, 2))
        assertNull(ExternalCommandResolver.parse(null, "text without SEND", null, false, 3))
    }
}
