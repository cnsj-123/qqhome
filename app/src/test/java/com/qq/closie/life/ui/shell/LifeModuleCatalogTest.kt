package com.qq.closie.life.ui.shell

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM test — no Robolectric, no Android framework — because [LifeModuleCatalog] is plain
 * Kotlin. That is deliberate: the 生活 directory is product structure, not UI, so it should be
 * assertable without spinning up a runtime.
 */
class LifeModuleCatalogTest {

    @Test
    fun directory_hasExactlySevenModulesInOrder() {
        assertThat(LifeModuleCatalog.entries.map { it.title })
            .containsExactly("衣橱", "财务", "物品", "旅行", "园艺", "阅读", "计划")
            .inOrder()
    }

    @Test
    fun onlyClosetIsOpen() {
        assertThat(LifeModuleCatalog.entries.filter { it.open }.map { it.key })
            .containsExactly(LifeModuleCatalog.KEY_CLOSET)
        assertThat(LifeModuleCatalog.openEntries()).hasSize(1)
    }

    /**
     * 饮食 / 健康 / 出行 are not part of the v0.1 directory. Listing them would advertise a
     * roadmap that does not exist yet, so the test fails if they sneak back in.
     */
    @Test
    fun directory_doesNotContainDeferredModules() {
        val titles = LifeModuleCatalog.entries.map { it.title }
        assertThat(titles).containsNoneOf("饮食", "健康", "出行")
    }

    @Test
    fun statusOf_reflectsOpenState() {
        val closet = LifeModuleCatalog.entries.first { it.key == LifeModuleCatalog.KEY_CLOSET }
        val planned = LifeModuleCatalog.entries.first { it.key == "money" }

        assertThat(LifeModuleCatalog.statusOf(closet)).isEqualTo("已开放")
        assertThat(LifeModuleCatalog.statusOf(planned)).isEqualTo("即将开放")
    }

    @Test
    fun moduleKeysAreUnique() {
        assertThat(LifeModuleCatalog.entries.map { it.key }).containsNoDuplicates()
    }
}
