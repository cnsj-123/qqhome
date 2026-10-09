package com.qq.closie

import com.qq.closie.navigation.LifeOsRoute
import com.qq.closie.ui.lifeos.drawer.LifeModules
import com.qq.closie.ui.lifeos.components.LifeSwipeIntent
import org.junit.Assert.*
import org.junit.Test

class LifeDrawerStructureTest {
    @Test fun quickActionsKeepRealIntakeAndCalendarRoutes() {
        assertEquals(listOf("capture", "inbox", "calendar", "search"), LifeModules.quickActions.map { it.id })
        assertEquals(listOf("记一下", "收件匣", "日历", "搜索"), LifeModules.quickActions.map { it.label })
        assertEquals(listOf(LifeOsRoute.CAPTURE, LifeOsRoute.CAPTURE, LifeOsRoute.CALENDAR, LifeOsRoute.module("search")),
            LifeModules.quickActions.map { it.route })
    }
    @Test fun restoredNamesKeepStableIdsAndGalleryAppearsOnce() {
        val cells = LifeModules.groups.flatMap { it.modules }
        assertEquals(cells.size, cells.map { it.id }.distinct().size)
        assertEquals(1, cells.count { it.id == "gallery" })
        assertEquals(listOf("closet", "shopping", "items", "food", "skincare", "garden", "reading", "hobbies",
            "gallery", "travel", "place", "plans", "marks", "map"), LifeModules.groups[1].modules.map { it.id })
        assertEquals(listOf("衣橱", "购物与价格", "物品", "饮食", "护肤", "园艺", "阅读与影视", "兴趣", "我的油画展",
            "旅行", "地点与商家", "计划与目标", "生活印记", "生活地图"), LifeModules.groups[1].modules.map { it.label })
        assertEquals(LifeOsRoute.READING, LifeModules.find("reading").route)
        assertEquals(LifeOsRoute.PLAN, LifeModules.find("plans").route)
    }
    @Test fun swipeWaitsForHorizontalIntentAndLetsVerticalScrollWin() {
        assertNull(LifeSwipeIntent.resolve(-2f, 3f, 10f))
        assertEquals(LifeSwipeIntent.Direction.VERTICAL, LifeSwipeIntent.resolve(-15f, 50f, 10f))
        assertEquals(LifeSwipeIntent.Direction.LEFT, LifeSwipeIntent.resolve(-50f, 8f, 10f))
        assertEquals(LifeSwipeIntent.Direction.RIGHT, LifeSwipeIntent.resolve(50f, 8f, 10f))
    }
}
