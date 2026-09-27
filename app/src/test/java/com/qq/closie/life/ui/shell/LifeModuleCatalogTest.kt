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
    fun directory_hasExactlyEightModulesInOrder() {
        // v0.3.0 fixes the sequence to the design's: the four built modules first, then the four
        // that only have a landing page. 资料库 is second because it is the module this release is
        // really about — burying it after 财务 would misrepresent what shipped.
        assertThat(LifeModuleCatalog.entries.map { it.title })
            .containsExactly("衣橱", "资料库", "计划", "阅读", "财务", "物品", "旅行", "园艺")
            .inOrder()
    }

    @Test
    fun theFourBuiltModulesAreOpenAndLive() {
        assertThat(LifeModuleCatalog.liveEntries().map { it.key })
            .containsExactly(
                LifeModuleCatalog.KEY_CLOSET,
                LifeModuleCatalog.KEY_REFERENCE,
                LifeModuleCatalog.KEY_PLAN,
                LifeModuleCatalog.KEY_READING
            )
            .inOrder()
    }

    @Test
    fun everyRowIsEnterable() {
        // No row is a dead label any more: the unbuilt four open a landing page that says what the
        // module will be. A disabled row that silently does nothing is the bug this replaced.
        assertThat(LifeModuleCatalog.entries.filter { !it.open }).isEmpty()
        assertThat(LifeModuleCatalog.openEntries()).hasSize(8)
    }

    /**
     * 饮食 / 健康 / 出行 are not part of the directory. Listing them would advertise a
     * roadmap that does not exist yet, so the test fails if they sneak back in.
     */
    @Test
    fun directory_doesNotContainDeferredModules() {
        val titles = LifeModuleCatalog.entries.map { it.title }
        assertThat(titles).containsNoneOf("饮食", "健康", "出行")
    }

    @Test
    fun statusOf_distinguishesBuiltFromPlannedFromComingSoon() {
        val closet = LifeModuleCatalog.byKey(LifeModuleCatalog.KEY_CLOSET)!!
        val money = LifeModuleCatalog.byKey(LifeModuleCatalog.KEY_MONEY)!!

        assertThat(LifeModuleCatalog.statusOf(closet)).isEqualTo("已开放")
        // 财务 opens a landing page but is not implemented — calling it 已开放 would claim a module
        // exists when it does not.
        assertThat(LifeModuleCatalog.statusOf(money)).isEqualTo("规划中")
    }

    @Test
    fun byKey_findsEveryDeclaredKeyAndRejectsUnknownOnes() {
        LifeModuleCatalog.entries.forEach { spec ->
            assertThat(LifeModuleCatalog.byKey(spec.key)).isEqualTo(spec)
        }
        assertThat(LifeModuleCatalog.byKey("nope")).isNull()
    }

    @Test
    fun moduleKeysAreUnique() {
        assertThat(LifeModuleCatalog.entries.map { it.key }).containsNoDuplicates()
    }
}
