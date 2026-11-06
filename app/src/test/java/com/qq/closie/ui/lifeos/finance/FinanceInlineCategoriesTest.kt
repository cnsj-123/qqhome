package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.qq.closie.life.finance.FinanceCategoryEntity
import com.qq.closie.life.finance.FinanceSnapshot
import com.qq.closie.ui.lifeos.theme.LifeOsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w405dp-h880dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinanceInlineCategoriesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun anEmptyLedgerUsesDraftTemplateNamesWithoutInventingCanonicalCategoryIds() {
        var draftRoot="";var draftChild="";var canonicalCalls=0
        compose.setContent {LifeOsTheme {
            var root by remember {mutableStateOf("")};var child by remember {mutableStateOf("")}
            Column {FinanceInlineCategories(FinanceSnapshot(),null,false,selectedName=root,selectedChild=child,
                onTemplate={parent,leaf->root=parent;child=leaf.orEmpty();draftRoot=root;draftChild=child}) {canonicalCalls++}}
        }}
        compose.onNodeWithTag("entry-category-template:餐饮").performClick()
        compose.onNodeWithTag("entry-category-template:餐饮/早餐").assertIsDisplayed().performClick()
        compose.onNodeWithTag("entry-category-template:餐饮/早餐").assertIsSelected()
        compose.onNodeWithTag("entry-category-template:学习").assertIsDisplayed()
        assertEquals("餐饮",draftRoot);assertEquals("早餐",draftChild);assertEquals(0,canonicalCalls)
    }

    @Test fun childSelectionKeepsTheRootGridAndUsesTheSameTypedCategoryId() {
        val snapshot = FinanceSnapshot(categories = listOf(FinanceCategoryEntity("food", "餐饮"),
            FinanceCategoryEntity("breakfast", "早餐", "food"), FinanceCategoryEntity("transport", "交通")))
        var selection: String? = null
        compose.setContent { LifeOsTheme {
            var selected by remember { mutableStateOf<String?>(null) }
            Column { FinanceInlineCategories(snapshot, selected, false) { selected = it; selection = it } }
        } }
        compose.onNodeWithTag("entry-category-breakfast").assertDoesNotExist()
        compose.onNodeWithTag("entry-category-food").performClick()
        compose.onNodeWithTag("entry-category-breakfast").assertIsDisplayed().performClick()
        assertEquals("breakfast", selection)
        compose.onNodeWithTag("entry-category-food").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("entry-category-transport").assertIsDisplayed()
        compose.onNodeWithTag("entry-category-breakfast").assertIsSelected()
        compose.onNodeWithTag("entry-category-food").performClick()
        compose.onNodeWithTag("entry-category-breakfast").assertDoesNotExist()
        assertEquals("food", selection)
    }
}
