package com.qq.closie.ui.lifeos.drawer

import com.qq.closie.navigation.LifeOsRoute

data class LifeModule(val id: String, val label: String, val route: String = LifeOsRoute.module(id))
data class LifeModuleGroup(val label: String, val modules: List<LifeModule>)

object LifeModules {
    val groups = listOf(
        LifeModuleGroup("随手与回看", listOf(
            LifeModule("capture", "Capture · 记一下", LifeOsRoute.CAPTURE),
            LifeModule("inbox", "Inbox · 稍后整理", LifeOsRoute.CAPTURE),
            LifeModule("calendar", "生活日历", LifeOsRoute.CALENDAR), LifeModule("search", "搜索")
        )),
        LifeModuleGroup("生活的一格一格", listOf(
            LifeModule("closet", "衣橱", LifeOsRoute.CLOSET), LifeModule("shopping", "购物"),
            LifeModule("items", "物品"), LifeModule("food", "吃喝"), LifeModule("skincare", "护肤"),
            LifeModule("garden", "园艺"), LifeModule("reading", "阅读", LifeOsRoute.READING), LifeModule("hobbies", "兴趣"),
            LifeModule("travel", "旅行"), LifeModule("place", "地点"), LifeModule("plans", "计划", LifeOsRoute.PLAN),
            LifeModule("marks", "生活印记"), LifeModule("map", "生活地图", LifeOsRoute.MAP)
        )),
        LifeModuleGroup("记录与作品", listOf(
            LifeModule("plog", "Plog · 生活手账"), LifeModule("gallery", "作品集"),
            LifeModule("knowledge", "资料库", LifeOsRoute.REFERENCE), LifeModule("media", "媒体"),
            LifeModule("companion", "伙伴聊天", LifeOsRoute.COMPANION), LifeModule("vault", "创作库")
        )),
        LifeModuleGroup("只属于自己的设置", listOf(
            LifeModule("finance", "账目"), LifeModule("membership", "会员"), LifeModule("health", "健康"),
            LifeModule("privacy", "隐私"), LifeModule("backup", "备份与恢复", LifeOsRoute.BACKUP),
            LifeModule("settings", "设置与主题", LifeOsRoute.SETTINGS)
        ))
    )
    fun label(id: String) = groups.flatMap { it.modules }.firstOrNull { it.id == id }?.label ?: "生活模块"
}
