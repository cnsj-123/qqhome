package com.qq.closie.ui.lifeos.finance

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.LifeOsTheme
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w405dp-h880dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinanceRecordNavigationTest: FinanceRoomTest() {
    @get:Rule val compose=createComposeRule()
    private val models=ViewModelStore()
    private lateinit var vm: FinanceViewModel
    private var receiptFile: File?=null

    @After fun dispose() {models.clear();receiptFile?.delete()}

    private fun show(id:String,ledger:Boolean=false) {
        vm=ViewModelProvider(models,object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T:ViewModel> create(modelClass:Class<T>):T=FinanceViewModel(finance) as T
        })[FinanceViewModel::class.java]
        compose.setContent {
            val state by vm.state.collectAsState()
            LifeOsTheme {
                if(state.loaded) {
                    if(ledger) FinanceModuleScreen(state,vm,onBack={})
                    else FinanceCanonicalRecordSheet(state,vm,id,onClose={})
                }
            }
        }
        compose.waitUntil(10_000) {vm.state.value.loaded && !vm.state.value.busy}
    }

    private suspend fun prepare(available:Boolean):Pair<FinanceSnapshot,Uri> {
        val account=account()
        val entry=finance.saveEntry(accountId=account.id,direction=FinanceDirection.OUTFLOW,amountMinor=1_234,
            description="真实凭据付款",occurredAt=System.currentTimeMillis(),category="餐饮",subcategory="咖啡",event=FinanceEventInput())
        val bytes="%PDF-1.4\nsynthetic receipt fixture\n%%EOF".toByteArray()
        val uri=Uri.parse("content://record-detail-evidence/receipt.pdf")
        if(available) {
            val context=ApplicationProvider.getApplicationContext<Context>()
            val file=File(context.cacheDir,"record-evidence-${UUID.randomUUID()}.pdf").apply {writeBytes(bytes)}
            receiptFile=file
            val provider=object:ContentProvider() {
                override fun onCreate()=true
                override fun getType(uri:Uri)="application/pdf"
                override fun openFile(uri:Uri,mode:String)=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
                override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
                override fun insert(uri:Uri,values:ContentValues?):Uri?=null
                override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
                override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
            }
            provider.attachInfo(context,ProviderInfo().apply {authority=uri.authority;applicationInfo=context.applicationInfo})
            ShadowContentResolver.registerProviderInternal(uri.authority,provider)
        }
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
        finance.media.attach(finance.economics.token(),FinanceMediaTarget(eventId=eventId(entry.id)),
            FinanceSelectedDocument(uri.toString(),"application/pdf",hash,bytes.size.toLong()),"合成付款凭据")
        return finance.snapshot() to uri
    }

    @Test fun formalEvidenceOpensTheOriginalAndEventDrillDownReturnsWithoutCreatingDrafts() {
        val (data,uri)=runBlocking {prepare(available=true)}
        show(data.entries.single().id)
        compose.onNodeWithContentDescription("查看账单凭据").performScrollTo().performClick()
        assertReadOnlyEvidence()
        compose.onNodeWithText("检查并打开凭据").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            !vm.state.value.busy && compose.onAllNodesWithText("原件摘要匹配。").fetchSemanticsNodes().isNotEmpty()
        }
        val launched=shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertNotNull(launched)
        assertEquals(android.content.Intent.ACTION_VIEW,launched.action)
        assertEquals(uri,launched.data)
        assertEquals("application/pdf",launched.type)
        assertTrue((launched.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0)
        compose.onNodeWithText("收起").performClick()

        compose.onNodeWithText("关联事项 · 真实凭据付款").performScrollTo().performClick()
        pane("经济事项详情").assertExists()
        compose.onNodeWithText("出资与承担").assertExists()
        compose.onNodeWithContentDescription("查看事项凭据").performScrollTo().performClick()
        assertReadOnlyEvidence()
        compose.onNodeWithText("收起").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        pane("账单详情").assertExists()
        assertEquals(data,runBlocking {finance.snapshot()})
        assertTrue(runBlocking {finance.snapshot()}.v16!!.economicDraftRows.isEmpty())
        compose.onNodeWithText("关联事项 · 真实凭据付款").performScrollTo().performClick()
        compose.onNodeWithContentDescription("编辑出资与承担").performClick()
        pane("编辑经济分配").assertExists()
        compose.onNodeWithText("舍弃本次分配草稿，保留正式事实").performClick()
        compose.waitUntil(10_000) {
            !vm.state.value.busy && compose.onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle,"编辑经济分配")).fetchSemanticsNodes().isEmpty()
        }
        val afterExplicitEdit=runBlocking {finance.snapshot()}
        assertEquals(data.entries,afterExplicitEdit.entries)
        assertEquals(data.v4!!.events,afterExplicitEdit.v4!!.events)
    }

    @Test fun ledgerCurrentCashNavigationIsBoundedAndMissingEvidenceDoesNotLaunchOrEdit() {
        val (data,_)=runBlocking {prepare(available=false)}
        show(data.entries.single().id,ledger=true)
        val row=hasText("真实凭据付款") and hasClickAction()
        compose.onNodeWithTag("finance-ledger").performScrollToNode(row)
        compose.onNode(row).performClick()
        repeat(2) {
            compose.onNodeWithText("关联事项 · 真实凭据付款").performScrollTo().performClick()
            pane("经济事项详情").assertExists()
            compose.onNode(hasText("合成账户 · 付款") and hasClickAction()).performScrollTo().performClick()
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle,"经济事项详情")).assertCountEquals(0)
            pane("账单详情").assertExists()
        }
        compose.onNodeWithContentDescription("查看账单凭据").performScrollTo().performClick()
        assertReadOnlyEvidence()
        compose.onNodeWithText("检查并打开凭据").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            !vm.state.value.busy && compose.onAllNodesWithText("原件已移除或授权失效，账务已保留，请重新选择原件。").fetchSemanticsNodes().isNotEmpty()
        }
        assertNull(shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity)
        assertEquals(data,runBlocking {finance.snapshot()})
    }

    private fun assertReadOnlyEvidence() {
        pane("凭据与附件").assertExists()
        compose.onNode(hasText("合成付款凭据") and hasAnyAncestor(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle,"凭据与附件"))).assertExists()
        listOf("选择原件并保留引用","关联同一媒体原件","重新选择同一原件以恢复访问","移除此引用，保留原件")
            .forEach {compose.onAllNodesWithText(it).assertCountEquals(0)}
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle,"编辑经济分配")).assertCountEquals(0)
    }

    private fun pane(title:String)=compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle,title))
}
