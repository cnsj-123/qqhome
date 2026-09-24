package com.qq.closie.life.ui.shell

/**
 * The v0.1 生活 directory.
 *
 * Plain Kotlin on purpose — no Compose imports — so the module list can be asserted in a JVM unit
 * test without a Robolectric runtime.
 *
 * Ordering is part of the contract: 衣橱 first because it is the only live module, then the
 * planned ones in the order they are expected to land. 饮食 / 健康 / 出行 are intentionally
 * **absent** from v0.1 — they are not "coming soon" placeholders yet, and listing them would
 * promise a roadmap that does not exist.
 */
data class LifeModuleSpec(
    val key: String,
    val title: String,
    val open: Boolean
)

object LifeModuleCatalog {

    /** Key of the only module that is actually navigable in v0.1. */
    const val KEY_CLOSET = "closet"

    val entries: List<LifeModuleSpec> = listOf(
        LifeModuleSpec(key = KEY_CLOSET, title = "衣橱", open = true),
        LifeModuleSpec(key = "money", title = "财务", open = false),
        LifeModuleSpec(key = "items", title = "物品", open = false),
        LifeModuleSpec(key = "travel", title = "旅行", open = false),
        LifeModuleSpec(key = "garden", title = "园艺", open = false),
        LifeModuleSpec(key = "reading", title = "阅读", open = false),
        LifeModuleSpec(key = "plan", title = "计划", open = false)
    )

    /** Status wording shown on the right of each row. */
    fun statusOf(spec: LifeModuleSpec): String =
        if (spec.open) "已开放" else "即将开放"

    /** Modules a user can actually enter. Everything else is disabled, so it owns no action. */
    fun openEntries(): List<LifeModuleSpec> = entries.filter { it.open }
}
