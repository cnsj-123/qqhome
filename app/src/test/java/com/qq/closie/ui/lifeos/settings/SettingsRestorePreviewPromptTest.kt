package com.qq.closie.ui.lifeos.settings

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.backup.EncryptedBackupManager
import com.qq.closie.data.repository.LocalWardrobeRepository
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.finance.FinanceRepository
import com.qq.closie.life.finance.FinanceRoomTest
import com.qq.closie.ui.lifeos.theme.LifeOsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w405dp-h880dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsRestorePreviewPromptTest:FinanceRoomTest() {
    @get:Rule val compose=createComposeRule()
    @get:Rule val files=TemporaryFolder()
    private fun password()="synthetic-restore-password".toCharArray()

    @Test fun choosingClickThenCancelPreservesDataAndOnlyTheExplicitConfirmationRestoresInspectedBytes() {
        val context:Context=ApplicationProvider.getApplicationContext()
        val wardrobe=LocalWardrobeRepository(context)
        val source=Uri.fromFile(files.newFile("inspected-confirmation.lifeos"))
        val entry=runBlocking {
            val account=account("合成恢复确认账户")
            val saved=payment(account.id,1234)
            EncryptedBackupManager.export(context,wardrobe,source,db,password()).getOrThrow()
            saved
        }
        val preview=runBlocking {EncryptedBackupManager.inspect(context,source,password()).getOrThrow()}
        val target=Room.inMemoryDatabaseBuilder(context,LifeDatabase::class.java).allowMainThreadQueries().build()
        try {
            val targetFinance=FinanceRepository(target)
            val before=runBlocking {targetFinance.portableSnapshot()}
            val shown=mutableStateOf(true)
            var confirmations=0
            compose.setContent {LifeOsTheme {
                if(shown.value) SettingsRestorePreviewPrompt(preview,source,enabled=true,busy=false,
                    onDismissRequest={shown.value=false},onConfirm={accepted->
                        assertSame(preview,accepted)
                        confirmations++
                        runBlocking {EncryptedBackupManager.restoreInspected(context,wardrobe,source,target,password(),accepted).getOrThrow()}
                        shown.value=false
                    })
            }}
            compose.onNodeWithText("衣橱及 Life OS 数据 · 账目记录 1 条").assertIsDisplayed()
            compose.onNodeWithText("使用点击确认").performClick()
            compose.onNodeWithText("确认恢复").assertIsEnabled()
            assertEquals(0,confirmations)
            assertEquals(before,runBlocking {targetFinance.portableSnapshot()})
            compose.onNodeWithText("取消").performClick()
            compose.waitUntil(10_000) {!shown.value}
            assertEquals(0,confirmations)
            assertEquals(before,runBlocking {targetFinance.portableSnapshot()})

            compose.runOnIdle {shown.value=true}
            // Reopening starts with hold confirmation; the previous click mode cannot leak across prompts.
            compose.onNodeWithTag("backup-restore-hold").assertIsDisplayed()
            compose.onNodeWithText("使用点击确认").performClick()
            compose.onNodeWithText("确认恢复").performClick()
            compose.waitUntil(10_000) {!shown.value}
            assertEquals(1,confirmations)
            val restored=runBlocking {targetFinance.portableSnapshot()}
            assertEquals(entry.id,restored.entries.single().id)
            assertEquals(1234L,restored.entries.single().amountMinor)
            assertTrue(requireNotNull(restored.v16).controlRows.single().restoreEpoch>0)
        } finally {target.close()}
    }

    @Test fun changingTheInspectedSourceResetsClickModeAndClosingDisablesTheAlternative() {
        val preview=com.qq.closie.data.backup.BackupRestorePreview(true,2,ByteArray(32) {1})
        val source=mutableStateOf("inspected-source-a")
        val enabled=mutableStateOf(true)
        var confirmations=0
        compose.setContent {LifeOsTheme {
            SettingsRestorePreviewPrompt(preview,source.value,enabled.value,busy=false,onDismissRequest={},onConfirm={confirmations++})
        }}
        compose.onNodeWithText("使用点击确认").performClick()
        compose.onNodeWithText("确认恢复").assertIsEnabled()
        compose.runOnIdle {source.value="inspected-source-b"}
        compose.onNodeWithTag("backup-restore-hold").assertIsDisplayed()
        compose.onNodeWithText("使用点击确认").performClick()
        compose.runOnIdle {enabled.value=false}
        compose.onNodeWithText("确认恢复").assertIsNotEnabled()
        compose.onNodeWithText("确认恢复").performClick()
        compose.runOnIdle {assertEquals(0,confirmations);enabled.value=true}
        compose.mainClock.autoAdvance=false
        compose.onNodeWithText("取消").performClick()
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("确认恢复").assertIsNotEnabled()
        compose.onNodeWithText("确认恢复").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle {assertEquals(0,confirmations)}
        compose.mainClock.autoAdvance=true
    }
}
