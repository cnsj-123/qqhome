package com.qq.closie.life.ui.shell

/**
 * The 生活 directory.
 *
 * Plain Kotlin on purpose — no Compose imports — so the module list can be asserted in a JVM unit
 * test without a Robolectric runtime.
 *
 * Ordering is part of the contract, and v0.3.0 fixes it to the design's sequence:
 *
 *   衣橱 → 资料库 → 计划 → 阅读 → 财务 → 物品 → 旅行 → 园艺
 *
 * The two groups are deliberate. The first four are **open**: 衣橱 deep-links into the shipped
 * closet, and 资料库 / 计划 / 阅读 are the three modules v0.3.0 actually builds. The last four are
 * **not built yet**, but they are no longer dead rows — each one opens
 * [com.qq.closie.life.ui.modules.ModuleLandingScreen], an honest landing page that says what the
 * module will be and gives the user somewhere to put the thought *today* (先记到资料库) instead of
 * presenting a disabled label that apparently does nothing.
 *
 * 饮食 / 健康 / 出行 remain **absent** — they are not "coming soon" placeholders, and listing them
 * would promise a roadmap that does not exist.
 */
data class LifeModuleSpec(
    val key: String,
    val title: String,
    /** True when the row leads somewhere real (a built module or a landing page). */
    val open: Boolean,
    /** True when the module is actually implemented, as opposed to a landing page. */
    val live: Boolean = false
)

object LifeModuleCatalog {

    const val KEY_CLOSET = "closet"
    const val KEY_REFERENCE = "reference"
    const val KEY_PLAN = "plan"
    const val KEY_READING = "reading"
    const val KEY_MONEY = "money"
    const val KEY_ITEMS = "items"
    const val KEY_TRAVEL = "travel"
    const val KEY_GARDEN = "garden"

    val entries: List<LifeModuleSpec> = listOf(
        LifeModuleSpec(key = KEY_CLOSET, title = "衣橱", open = true, live = true),
        LifeModuleSpec(key = KEY_REFERENCE, title = "资料库", open = true, live = true),
        LifeModuleSpec(key = KEY_PLAN, title = "计划", open = true, live = true),
        LifeModuleSpec(key = KEY_READING, title = "阅读", open = true, live = true),
        LifeModuleSpec(key = KEY_MONEY, title = "财务", open = true, live = false),
        LifeModuleSpec(key = KEY_ITEMS, title = "物品", open = true, live = false),
        LifeModuleSpec(key = KEY_TRAVEL, title = "旅行", open = true, live = false),
        LifeModuleSpec(key = KEY_GARDEN, title = "园艺", open = true, live = false)
    )

    /**
     * Status wording shown on the right of each row.
     *
     * Three states, not two. "已开放" for a built module, "规划中" for one that only has a landing
     * page — labelling the landing-page group 已开放 would claim a module exists when it does not,
     * which is exactly the kind of small dishonesty that makes a UI untrustworthy.
     */
    fun statusOf(spec: LifeModuleSpec): String = when {
        spec.live -> "已开放"
        spec.open -> "规划中"
        else -> "即将开放"
    }

    /** Modules a user can actually enter (built module or landing page). */
    fun openEntries(): List<LifeModuleSpec> = entries.filter { it.open }

    /** Only the modules v0.3.0 genuinely ships. Used by tests and the home page. */
    fun liveEntries(): List<LifeModuleSpec> = entries.filter { it.live }

    fun byKey(key: String): LifeModuleSpec? = entries.firstOrNull { it.key == key }
}
