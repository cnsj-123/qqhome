package com.qq.closie.ui.lifeos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.runtime.*
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.finance.FinanceRoute
import com.qq.closie.ui.lifeos.home.*
import com.qq.closie.ui.lifeos.media.LifeMediaUi
import com.qq.closie.ui.lifeos.theme.LifeOsTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/** Rendered Compose code with synthetic data; this is explicitly NOT device/IME/projection evidence. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V16VisualEvidenceTest:FinanceRoomTest() {
    @get:Rule val compose=createComposeRule()
    private var models:ViewModelStore?=null
    @After fun stopReaders(){compose.runOnIdle {models?.clear()}}
    private fun capture(name:String) {
        compose.waitForIdle()
        // Robolectric cannot capture a hardware screen. Draw the actual top window on its native Canvas.
        val bitmap=compose.runOnIdle {
            val view=WindowInspector.getGlobalWindowViews().last {it.isAttachedToWindow && it.width>100 && it.height>100}
            Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888).also {view.draw(Canvas(it))}
        }
        val colors=mutableSetOf<Int>()
        for(x in 0 until bitmap.width step 3)for(y in 0 until bitmap.height step 3)colors.add(bitmap.getPixel(x,y))
        assertTrue("Production window must contain rendered content, not a blank bitmap",colors.size>8)
        assertTrue(bitmap.width>100 && bitmap.height>100)
        val metrics=File(requireNotNull(System.getProperty("lifeos.v16.metricsDir")))
        val output=File(metrics.parentFile,"robolectric-visuals").apply {mkdirs()}
        File(output,"EVIDENCE-TYPE.txt").writeText("ROBOLECTRIC VIEW.DRAW NATIVE CANVAS / SDK34 shadows / synthetic data / NOT DEVICE VERIFIED")
        File(output,name+".png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
    }
    private fun home(state:HomeUiState) {
        compose.setContent {LifeOsTheme {LifeOsHomeScreen(state,{},{},{},{},{})}}
        compose.onNodeWithText("Life OS").assertIsDisplayed()
    }
    @Test fun homeEmptyStateRendersFromProductionComposable() {
        home(HomeUiState(LocalDate.of(2026,10,12)))
        capture("home-empty")
    }
    @Test fun homePhotoStackRendersStableIrregularFramesAndMemory() {
        val context:Context=ApplicationProvider.getApplicationContext()
        val photos=(0..2).map {index->
            val bitmap=Bitmap.createBitmap(360,440,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap);canvas.drawColor(listOf(0xffcbd6c4,0xffcbd6e8,0xffe3cccf)[index].toInt())
            val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color=0xfff7f3e9.toInt();canvas.drawRect(40f,80f,320f,360f,paint)
            paint.color=0xff5c576e.toInt();paint.textSize=30f;canvas.drawText("SYNTHETIC "+index,60f,230f,paint)
            val file=File(context.cacheDir,"visual-fixture-"+index+".png")
            file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            LifeMediaUi("visual-"+index,Uri.fromFile(file).toString(),title="合成照片"+index,caption="午后在小区散步，风很温柔。")
        }
        home(HomeUiState(LocalDate.of(2026,10,12),eligibleMedia=photos,
            historyItems=listOf(HistoryItemUi("synthetic-memory",LocalDate.of(2026,10,8),"秋天第一次穿上薄外套。",photos[1])),
            futureIntents=listOf(FutureIntentUi("synthetic-plan",LocalDate.of(2026,10,17),"周末去旧书店"))))
        compose.waitUntil(10_000){compose.onAllNodesWithText("照片载入中").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithText("原件暂不可用").assertDoesNotExist()
        capture("home-photos")
    }
    @Config(qualifiers="w320dp-h640dp")
    @Test fun narrowHomeWithLongTextAndLargeFontRemainsScrollable() {
        val state=HomeUiState(LocalDate.of(2026,10,12),todayFacts=listOf(HomeFactUi("synthetic-long",
            "这是合成长文，用于检查窄屏大字时的行距与阅读边界。".repeat(30),"合成")))
        compose.setContent {
            val current=androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(current.density,1.5f)) {
                LifeOsTheme {LifeOsHomeScreen(state,{},{},{},{},{})}
            }
        }
        compose.onNodeWithText("Life OS").assertExists()
        capture("home-narrow-font150-long")
    }
    private fun financeScene() {
        compose.setContent {models=LocalViewModelStoreOwner.current?.viewModelStore;LifeOsTheme {FinanceRoute(finance,{})}}
        compose.waitUntil(10_000){compose.onAllNodesWithText("正在打开账本……").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithText("账簿").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun accountGridRendersProductionCurrencyAndTypeLabels() {
        runBlocking {
            finance.saveAccount(name="合成零钱",kind=FinanceAccountKind.WECHAT,openingBalanceMinor=12_345)
            finance.saveAccount(name="合成工资卡",kind=FinanceAccountKind.BANK_CARD,openingBalanceMinor=100_000)
            finance.saveAccount(name="合成现金",kind=FinanceAccountKind.CASH,openingBalanceMinor=5_000)
        }
        financeScene();compose.onNodeWithText("账户").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("合成工资卡",substring=true).fetchSemanticsNodes().isNotEmpty()}
        capture("finance-accounts")
    }
    @Test fun ledgerAndReadOnlyReceiptRenderWithoutCreatingAnotherEntry() {
        runBlocking {
            val a=finance.saveAccount(name="合成零钱",kind=FinanceAccountKind.WECHAT,openingBalanceMinor=12_345)
            finance.saveEntry(accountId=a.id,direction=FinanceDirection.OUTFLOW,amountMinor=1234,
                description="合成咖啡",occurredAt=System.currentTimeMillis(),category="餐饮")
        }
        financeScene()
        compose.onNodeWithTag("finance-ledger").performScrollToNode(hasText("合成咖啡") and hasClickAction())
        capture("finance-ledger")
        compose.onNode(hasText("合成咖啡") and hasClickAction()).performClick()
        compose.onNodeWithText("账单详情").assertIsDisplayed()
        compose.onNodeWithTag("finance-receipt").assertIsDisplayed()
        capture("finance-receipt")
        assertEquals(1,runBlocking {finance.snapshot()}.entries.size)
    }
}
