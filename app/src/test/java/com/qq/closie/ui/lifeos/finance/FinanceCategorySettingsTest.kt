package com.qq.closie.ui.lifeos.finance

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.finance.FinanceImportRepository
import com.qq.closie.life.finance.FinanceRepository
import com.qq.closie.ui.lifeos.theme.LifeOsTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w405dp-h880dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinanceCategorySettingsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: LifeDatabase
    private lateinit var repository: FinanceRepository
    private lateinit var model: FinanceViewModel
    private var models: ViewModelStore? = null

    @Before fun open() {
        RestoreStartupGate.resetForTesting()
        RestoreStartupGate.markReady()
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LifeDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = FinanceRepository(database)
    }

    @After fun close() {
        try { compose.runOnIdle { models?.clear() } }
        finally { try { database.close() } finally { RestoreStartupGate.resetForTesting() } }
    }

    @Test fun emptySettingsCanCreateAndSelectRealRootAndChildWithoutCashAndRejectDuplicate() {
        compose.setContent {
            models = requireNotNull(LocalViewModelStoreOwner.current).viewModelStore
            LifeOsTheme {
                FinanceSettingsRoute(repository, FinanceImportRepository(database, repository),
                    onDiscovery = {}, onReview = {}, onPrivacy = {}, onBackup = {}, onBack = {})
            }
        }
        compose.runOnIdle {
            model = ViewModelProvider(requireNotNull(models), FinancePresentationFactory(repository))[FinanceViewModel::class.java]
        }
        awaitPresentation("initial category snapshot") { model.state.value.loaded }
        compose.onNodeWithText("分类与图标").performScrollTo().performClick()
        compose.onNodeWithText("没有匹配的分类").assertExists()
        newCategory("餐饮")
        saveCategory("餐饮")
        val root = snapshot().categories.single()
        assertEquals("餐饮", root.name)
        assertNull(root.parentId)
        compose.onNodeWithTag("finance-category-option-" + root.id).assertIsSelected()

        newCategory("咖啡")
        chooseParent("餐饮")
        saveCategory("咖啡")
        val child = snapshot().categories.single { it.name == "咖啡" }
        assertEquals(root.id, child.parentId)
        compose.onNodeWithTag("finance-category-option-" + root.id).performClick()
        compose.onNodeWithTag("finance-category-option-" + child.id).assertIsSelected()
        val beforeDuplicate = snapshot()
        assertTrue(beforeDuplicate.accounts.isEmpty())
        assertTrue(beforeDuplicate.entries.isEmpty())
        assertTrue(beforeDuplicate.transfers.isEmpty())

        newCategory("咖啡")
        chooseParent("餐饮")
        compose.onNodeWithTag("finance-save-category").assertIsEnabled().performScrollTo().performClick()
        awaitPresentation("duplicate child rejection") { !model.state.value.busy && model.state.value.error?.contains("同名子分类") == true }
        compose.onNodeWithContentDescription("分类名称").assertTextContains("咖啡")
        compose.onNodeWithTag("finance-save-category").assertExists()
        assertEquals(beforeDuplicate, snapshot())
    }

    private fun newCategory(name: String) {
        compose.onNodeWithTag("finance-new-category").performScrollTo().performClick()
        compose.onNodeWithContentDescription("分类名称").performTextReplacement(name)
        compose.onNodeWithContentDescription("分类名称").assertTextContains(name)
    }

    @Test fun duplicateRootAndMissingParentLeaveTheCanonicalSnapshotUnchanged() = runBlocking {
        val root = repository.createCategory("餐饮", null)
        val before = repository.snapshot()
        assertEquals(null, root.parentId)
        assertTrue(runCatching { repository.createCategory(" 餐饮 ", null) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(before, repository.snapshot())
        assertTrue(runCatching { repository.createCategory("咖啡", "missing-parent") }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(before, repository.snapshot())
        assertTrue(before.entries.isEmpty())
        assertTrue(before.accounts.isEmpty())
    }

    private fun chooseParent(name: String) {
        compose.onNodeWithTag("finance-new-category-parent").performClick()
        compose.onAllNodes(hasText(name) and hasClickAction()).onFirst().performClick()
    }

    private fun saveCategory(name: String) {
        compose.onNodeWithTag("finance-save-category").assertIsEnabled().performScrollTo().performClick()
        awaitPresentation("saved category $name and returned category list") {
            model.state.value.let { !it.busy && it.snapshot.categories.any {category->category.name==name} } &&
                compose.onAllNodesWithTag("finance-save-category").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
    }

    private fun awaitPresentation(description: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(10_000) {
                // Room completes on its executor and posts the ViewModel continuation to Android's
                // main looper. A state-only false predicate must not skip draining that looper.
                compose.runOnIdle {}
                condition()
            }
        } catch (failure: ComposeTimeoutException) {
            val state = model.state.value
            throw AssertionError("$description: loaded=${state.loaded}, busy=${state.busy}, error=${state.error}, " +
                "categories=${state.snapshot.categories.map { it.id to it.name }}", failure)
        }
    }

    private fun snapshot() = runBlocking { repository.snapshot() }
}
