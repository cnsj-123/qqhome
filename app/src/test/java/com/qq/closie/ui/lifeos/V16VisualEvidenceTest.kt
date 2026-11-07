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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
@Config(sdk=[34],qualifiers="w405dp-h880dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V16VisualEvidenceTest:FinanceRoomTest() {
    @get:Rule val compose=createComposeRule()
    private var models:ViewModelStore?=null
    private var appearanceJob:kotlinx.coroutines.Job?=null
    @After fun stopReaders(){compose.runOnIdle {models?.clear()};runBlocking {appearanceJob?.let {it.cancel();it.join()}}}
    private fun capture(name:String) {
        compose.waitForIdle()
        // Replay actual activity/dialog display lists using Robolectric's native HardwareRenderer,
        // then composite their pixels with the attached windows' real dim/alpha attributes.
        val frame=compose.runOnIdle {captureNativeAttachedWindows()}
        val bitmap=frame.bitmap
        val colors=mutableSetOf<Int>()
        for(x in 0 until bitmap.width step 3)for(y in 0 until bitmap.height step 3)colors.add(bitmap.getPixel(x,y))
        assertTrue("Production window must contain rendered content, not a blank bitmap",colors.size>8)
        assertTrue(bitmap.width>100 && bitmap.height>100)
        val metrics=File(requireNotNull(System.getProperty("lifeos.v16.metricsDir")))
        val output=File(metrics.parentFile,"robolectric-visuals").apply {mkdirs()}
        File(output,"EVIDENCE-TYPE.txt").writeText("ROBOLECTRIC SDK34 NATIVE HARDWARERENDERER / actual attached window display lists and WindowManager dim/alpha attributes / synthetic data / NOT DEVICE VERIFIED")
        File(output,name+".windows.json").writeText(frame.windowAttributes)
        try {File(output,name+".png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}
        finally {bitmap.recycle()}
    }
    private fun home(state:HomeUiState) {
        compose.setContent {LifeOsTheme {androidx.compose.material3.Surface(color=com.qq.closie.ui.lifeos.theme.LocalLifeOsColors.current.paper) {LifeOsHomeScreen(state,{},{},{},{},{})}}}
        compose.onNodeWithText("Life OS").assertIsDisplayed()
    }
    @Test fun startupLoadingRendersProductionCatAndRealStatus() {
        compose.setContent {LifeOsTheme {LifeStartupFeedback("正在打开你的日子","正在读取本地账本与衣橱")}}
        compose.onNodeWithText("正在打开你的日子").assertIsDisplayed()
        compose.onNodeWithContentDescription("正在陪你等候的猫咪").assertIsDisplayed()
        capture("startup-loading")
    }
    @Test fun homeEmptyStateRendersFromProductionComposable() {
        home(HomeUiState(LocalDate.of(2026,10,12)))
        capture("home-empty")
    }
    @Test fun homePhotoStackRendersStableIrregularFramesAndMemory() {
        val context:Context=ApplicationProvider.getApplicationContext()
        val photos=(0..3).map {index->
            val bitmap=Bitmap.createBitmap(360,440,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap);canvas.drawColor(listOf(0xffcbd6c4,0xffcbd6e8,0xffe3cccf,0xffd8c8e3)[index].toInt())
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
        compose.onNode(hasContentDescription("合成照片0") and hasClickAction()).performClick()
        compose.onNodeWithTag("life-origin-paper").assertIsDisplayed()
        capture("home-photo-viewer")
    }
    @Test fun drawerRendersTheActualMountedHomeAndModuleSurface() {
        compose.setContent {LifeOsTheme {
            com.qq.closie.ui.lifeos.components.LifeFunctionPages(open=true,enabled=true,
                style=com.qq.closie.data.appearance.FunctionPageStyle.DECK,reducedMotion=false,home=true,
                onOpenChange={},functionPage={com.qq.closie.ui.lifeos.drawer.LifeOsDrawer({},{})}) {
                androidx.compose.material3.Surface(color=com.qq.closie.ui.lifeos.theme.LocalLifeOsColors.current.paper) {
                    LifeOsHomeScreen(HomeUiState(LocalDate.of(2026,10,12)),{},{},{},{},{})
                }
            }
        }}
        compose.onNodeWithText("生活的抽屉").assertIsDisplayed()
        capture("drawer-default")
        compose.onNodeWithTag("life-drawer").performScrollToNode(hasTestTag("drawer-restore-order"))
        capture("drawer-bottom")
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
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(state.todayFacts.single().title).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {assertTrue(it(layouts))}
        val layout=layouts.single()
        val minimum=layout.layoutInput.style.fontSize.value*layout.layoutInput.density.fontScale*layout.layoutInput.density.density
        assertTrue(layout.lineCount>1)
        for(i in 1 until layout.lineCount)assertTrue("Scaled journal lines must not overlap",layout.getLineBaseline(i)-layout.getLineBaseline(i-1)>=minimum)
        compose.onNodeWithText("查看全文").assertExists()
        capture("home-narrow-font150-long")
    }
    private fun financeScene() {
        compose.setContent {models=LocalViewModelStoreOwner.current?.viewModelStore;LifeOsTheme {FinanceRoute(finance,null,null,onFinanceSettings={}) {}}}
        compose.waitUntil(10_000){compose.onAllNodesWithText("正在打开账本……").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithText("账簿").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun accountGridRendersProductionCurrencyAndTypeLabels() {
        runBlocking {
            finance.saveAccount(name="合成零钱",kind=FinanceAccountKind.WECHAT,openingBalanceMinor=12_345)
            finance.saveAccount(name="合成工资卡",kind=FinanceAccountKind.BANK_CARD,openingBalanceMinor=100_000)
            finance.saveAccount(name="合成储蓄卡",kind=FinanceAccountKind.BANK_CARD,openingBalanceMinor=4_000)
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
        compose.onNodeWithContentDescription("筛选与日期").performClick()
        compose.onNodeWithTag("finance-query-sheet").assertIsDisplayed()
        capture("finance-query")
        compose.onNodeWithContentDescription("取消查账条件").performClick()
        compose.waitUntil(10_000) {compose.onAllNodesWithTag("finance-query-sheet").fetchSemanticsNodes().isEmpty()}
        compose.onNode(hasText("合成咖啡") and hasClickAction()).performClick()
        compose.onNodeWithText("正式账单 · 已确认").assertIsDisplayed()
        val receipt=compose.onNodeWithTag("finance-receipt").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val windowHeight=compose.runOnIdle {WindowInspector.getGlobalWindowViews().last().height}
        assertTrue("A short receipt must not force an almost-fullscreen blank paper",receipt.height<windowHeight*.7f)
        capture("finance-receipt")
        assertEquals(1,runBlocking {finance.snapshot()}.entries.size)
    }
    @Test fun emptyLedgerUsesTheProductionEmptyStateWithoutInventingAnAccount() {
        financeScene()
        compose.onNodeWithText("快来记一笔账吧").assertIsDisplayed()
        capture("finance-ledger-empty")
        assertTrue(runBlocking {finance.snapshot()}.accounts.isEmpty())
    }

    @Test fun firstAccountAndFirstEntryPrintTowardTheActualVisibleLedgerRow() {
        financeScene()
        compose.onNodeWithTag("finance-add").assertIsNotEnabled()
        val firstAccount=compose.onNodeWithTag("finance-create-first-account").assertIsDisplayed()
        firstAccount.performClick()
        val accountName=compose.onNode(hasSetTextAction() and hasContentDescription("账户名称"))
        accountName.performTextReplacement("验收合成旅行卡")
        capture("finance-first-account-form")
        compose.onNode(hasText("保存账户") and hasClickAction()).performClick()
        val amount=hasSetTextAction() and hasContentDescription("金额 · CNY")
        compose.waitUntil(10_000){compose.onAllNodes(amount).fetchSemanticsNodes().isNotEmpty()}
        assertEquals("验收合成旅行卡",runBlocking {finance.snapshot()}.accounts.single().name)
        compose.onNode(amount).performScrollTo().performTextReplacement("32.50")
        compose.onNode(hasSetTextAction() and hasContentDescription("备注（可留空）"))
            .performScrollTo().performTextReplacement("验收第一笔咖啡")
        capture("finance-first-account-entry")
        compose.mainClock.autoAdvance=false
        try {
            compose.onNodeWithContentDescription("保存记录").assertIsEnabled().performClick()
            val paper=compose.onNodeWithTag("finance-printed-receipt")
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle {}
                compose.onAllNodesWithTag("finance-printed-receipt").fetchSemanticsNodes().isNotEmpty()
            }
            val committed=runBlocking {finance.snapshot()}.entries.single()
            assertEquals(3250L,committed.amountMinor)
            assertEquals("验收第一笔咖啡",committed.description)
            val ledgerRow=compose.onNodeWithTag("finance-ledger-row-${committed.id}",useUnmergedTree=true)
            ledgerRow.assertIsDisplayed()
            val fullHeight=paper.fetchSemanticsNode().boundsInRoot.height
            capture("finance-print-visible-before")
            compose.mainClock.advanceTimeBy(496)
            compose.runOnIdle {}
            assertTrue("A real destination must shrink the printed paper; fallback only fades it",
                paper.fetchSemanticsNode().boundsInRoot.height < fullHeight * .9f)
            capture("finance-print-visible-flight")
            compose.mainClock.advanceTimeBy(224)
            compose.runOnIdle {}
            paper.assertDoesNotExist()
            ledgerRow.assertIsDisplayed()
            capture("finance-print-visible-landed")
            assertEquals(committed,runBlocking {finance.snapshot()}.entries.single())
        } finally {compose.mainClock.autoAdvance=true}
        compose.onNodeWithTag("finance-search").performTextReplacement("没有这一笔")
        compose.waitUntil(10_000){compose.onAllNodesWithTag("finance-clear-conditions").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("finance-clear-conditions").assertIsDisplayed().performClick()
        compose.waitUntil(10_000){compose.onAllNodes(hasText("验收第一笔咖啡") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("finance-create-first-account").assertDoesNotExist()
    }

    @Test fun accountNameQueryRendersTheSameRealLedgerRowBeforeAndAfterApply() {
        runBlocking {
            val account=finance.saveAccount(name="旅行卡",kind=FinanceAccountKind.BANK_CARD,openingBalanceMinor=80_000)
            finance.saveEntry(accountId=account.id,direction=FinanceDirection.OUTFLOW,amountMinor=3200,
                description="咖啡",occurredAt=System.currentTimeMillis(),category="餐饮")
        }
        financeScene()
        compose.onNodeWithContentDescription("筛选与日期").performClick()
        compose.onNodeWithTag("finance-query-keyword").performTextReplacement("旅行卡")
        compose.waitUntil(10_000){compose.onAllNodesWithText("资金流出 ¥32.00",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("finance-query-preview-count").assertTextContains("1")
        capture("finance-query-account-preview")
        compose.onNodeWithText("查看结果").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithTag("finance-query-sheet").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithTag("finance-query-result-count").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodes(hasText("咖啡") and hasClickAction()).fetchSemanticsNodes().size==1}
        compose.onNodeWithTag("finance-query-result-count").assertTextContains("1")
        capture("finance-query-account-applied")
    }
    @Test fun entryAndAnalysisRenderActualCanonicalSyntheticFactsAtTheFigmaViewport() {
        runBlocking {
            val a=finance.saveAccount(name="合成零钱",kind=FinanceAccountKind.WECHAT,openingBalanceMinor=500_000)
            listOf("餐饮" to 98_600L,"购物" to 84_200L,"交通" to 40_200L,"居家" to 35_000L,"其他" to 60_000L).forEach {(category,amount)->
                finance.saveEntry(accountId=a.id,direction=FinanceDirection.OUTFLOW,amountMinor=amount,
                    description="合成"+category,category=category,occurredAt=System.currentTimeMillis())
            }
            val month=java.time.YearMonth.now()
            finance.workspace.saveBudget(finance.economics.token(),"本月预算","CNY",400_000,null,
                month.atDay(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                month.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
        }
        financeScene();compose.onNodeWithText("分析").performClick()
        compose.waitUntil(10_000) {compose.onAllNodesWithText("分类构成").fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodesWithText("共 1 项",substring=true).assertCountEquals(5)
        capture("finance-analysis")
        compose.onNodeWithTag("finance-add").performClick()
        val food=runBlocking {finance.snapshot()}.categories.single {it.name=="餐饮" && it.parentId==null}
        compose.onNodeWithTag("entry-category-"+food.id).performScrollTo().performClick()
        compose.onNodeWithTag("entry-category-template:餐饮/早餐").performScrollTo().performClick()
        capture("finance-entry")
        assertEquals(5,runBlocking {finance.snapshot()}.entries.size)
    }

    @Test fun savedTicketRendersCommittedFieldsDuringTheActualPrintMotion() {
        val row=runBlocking {
            val account=finance.saveAccount(name="合成零钱",kind=FinanceAccountKind.WECHAT,openingBalanceMinor=100_000)
            finance.saveEntry(accountId=account.id,direction=FinanceDirection.OUTFLOW,amountMinor=3_250,
                description="合成早餐",category="餐饮",occurredAt=System.currentTimeMillis())
            FinanceProjection.ledger(finance.snapshot()).single()
        }
        compose.mainClock.autoAdvance=false
        compose.setContent {LifeOsTheme {
            androidx.compose.material3.Surface(color=com.qq.closie.ui.lifeos.theme.LocalLifeOsColors.current.paper) {
                com.qq.closie.ui.lifeos.finance.FinancePrintedReceipt(row,destination={null},reducedMotion=false) {}
            }
        }}
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithText("合成早餐").assertExists()
        capture("finance-saved-print")
        assertEquals(1,runBlocking {finance.snapshot()}.entries.size)
    }

    @Test fun themeAndColorEditorRenderTheActualPreferenceOwnerWithoutSavingADraft() {
        val context:Context=ApplicationProvider.getApplicationContext()
        val job=kotlinx.coroutines.SupervisorJob();appearanceJob=job
        val scope=kotlinx.coroutines.CoroutineScope(job+kotlinx.coroutines.Dispatchers.IO)
        val store=androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope=scope) {
            File(context.cacheDir,"visual-appearance-"+java.util.UUID.randomUUID()+".preferences_pb")
        }
        val repo=com.qq.closie.data.appearance.AppearanceRepository(store,scope)
        runBlocking {repo.settings.filterNotNull().first()}
        val vm=com.qq.closie.ui.lifeos.settings.AppearanceViewModel(repo)
        models=ViewModelStore().apply {put("appearance",vm)}
        compose.setContent {LifeOsTheme {com.qq.closie.ui.lifeos.settings.AppearanceSettingsRoute(vm){}}}
        compose.waitUntil(10_000){compose.onAllNodesWithText("主题与配色").fetchSemanticsNodes().isNotEmpty()}
        capture("settings-theme")
        compose.onNodeWithText("纸").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithContentDescription("HEX 颜色").fetchSemanticsNodes().isNotEmpty()}
        capture("settings-color")
        assertEquals(com.qq.closie.data.appearance.AppearanceSettings(),runBlocking {repo.read()})
    }

    @Test fun globalSettingsRendersActualPreferenceAndDiscoveryOwners() {
        val context:Context=ApplicationProvider.getApplicationContext()
        val job=kotlinx.coroutines.SupervisorJob();appearanceJob=job
        val scope=kotlinx.coroutines.CoroutineScope(job+kotlinx.coroutines.Dispatchers.IO)
        val store=androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope=scope) {
            File(context.cacheDir,"visual-settings-"+java.util.UUID.randomUUID()+".preferences_pb")
        }
        val repo=com.qq.closie.data.appearance.AppearanceRepository(store,scope)
        runBlocking {repo.settings.filterNotNull().first()}
        val vm=com.qq.closie.ui.lifeos.settings.AppearanceViewModel(repo)
        models=ViewModelStore().apply {put("appearance",vm)}
        val automation=FinanceAutomationRepository(db,finance)
        compose.setContent {LifeOsTheme {
            com.qq.closie.ui.lifeos.settings.LifeSettingsScreen(vm,automation,{},{},{},{},{},{},{},{})
        }}
        compose.onNodeWithText("设置").assertIsDisplayed()
        capture("settings-global")
        compose.onNodeWithText("关于 Life OS").performScrollTo()
        capture("settings-global-bottom")
    }
    @Test fun backupPaperAndPasswordPromptRenderWithoutStartingAnExport() {
        val app:com.qq.closie.ClosieApplication=ApplicationProvider.getApplicationContext()
        val before=runBlocking {finance.snapshot()}
        compose.setContent {LifeOsTheme {
            com.qq.closie.ui.settings.DataSettingsScreen(app.wardrobeRepository,{},db,finance)
        }}
        compose.onNodeWithText("数据与备份").assertIsDisplayed()
        capture("settings-backup")
        compose.onNodeWithText("创建加密备份").performScrollTo().performClick()
        compose.onNodeWithTag("backup-password-fields").assertIsDisplayed()
        compose.onNodeWithText("创建并验证").assertIsNotEnabled()
        capture("settings-backup-password")
        compose.onNodeWithText("取消").performClick()
        compose.waitUntil(10_000) {compose.onAllNodesWithTag("backup-password-fields").fetchSemanticsNodes().isEmpty()}
        assertEquals(before,runBlocking {finance.snapshot()})
    }

    @Test fun modulePermissionPaperUsesTheRealDefaultPolicyAndDisablesUnboundShortGrants() {
        compose.setContent {models=LocalViewModelStoreOwner.current?.viewModelStore;LifeOsTheme {com.qq.closie.ui.lifeos.settings.SettingsPrivacyScreen(finance){}}}
        val financeRow=hasText("财务 Finance") and hasClickAction() and isEnabled()
        compose.waitUntil(15_000){compose.onAllNodes(financeRow).fetchSemanticsNodes().isNotEmpty()}
        capture("settings-privacy")
        compose.onNode(financeRow).performClick()
        compose.onNodeWithText("财务模块授权").assertIsDisplayed()
        compose.onNodeWithText("仅本次").assertIsNotEnabled()
        compose.onNodeWithText("本次对话").assertIsNotEnabled()
        capture("settings-permission")
        assertTrue(runBlocking {finance.snapshot()}.entries.isEmpty())
    }

}
