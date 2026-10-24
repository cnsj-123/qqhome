package com.qq.closie.life.finance.acquisition

import com.qq.closie.life.finance.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceHostPermissionTest:FinanceRoomTest() {
    private val controller=Robolectric.buildService(FinanceFixtureService::class.java)
    private lateinit var service:FinanceFixtureService
    private val reads=mutableMapOf<String,Int>()
    private lateinit var reader:FinanceHostWindows
    @Before fun setup() {
        service=controller.create().get()
        reader=FinanceHostWindows(service){root,adapter,occluded->
            val pkg=root.packageName.toString();reads[pkg]=(reads[pkg] ?: 0)+1
            VisibleDetailReader.inspect(root,adapter,occluded)
        }
    }
    @After fun cleanup(){controller.destroy()}
    @Test fun splitScreenOnlyEnabledAlipayIsReadAndCanCreateProposal()=runBlocking {
        verifySource(FinanceHostFixtures.ALIPAY,FinanceHostFixtures.WECHAT)
    }
    @Test fun reverseSplitScreenOnlyEnabledWechatIsReadAndCanCreateProposal()=runBlocking {
        verifySource(FinanceHostFixtures.WECHAT,FinanceHostFixtures.ALIPAY)
    }
    private suspend fun verifySource(allowed:String,denied:String) {
        FinanceHostFixtures.windows(service,FinanceHostFixtures.window(1,allowed),FinanceHostFixtures.window(2,denied,false))
        assertTrue(reader.snapshot().windows.all {it.identity==null});assertTrue(reads.isEmpty())
        val target=VisibleDetailScheduler.Target(allowed,1)
        val recognized=FinanceHostFixtures.evidence(allowed)
        val config=FinanceDiscoverySettings(visibleDetailEnabled=true,enabledVisibleProviders=setOf(recognized.providerKey))
        fun permitted()=config.visibleDetailEnabled && VisibleTransactionEvidenceRouter.provider(allowed,config.enabledVisibleProviders)!=null
        val lease=requireNotNull(reader.bindVisible(target,recognized,::permitted))
        val repo=FinanceAutomationRepository(db,finance)
        val result=repo.discoverVisible(recognized){permitted() && lease.captureCurrent(reader.current(lease,::permitted))}
        assertNotNull(result.proposal);assertEquals(1,db.financeIntakeDao().proposals().size)
        assertTrue((reads[allowed] ?: 0)>0);assertEquals(0,reads[denied] ?: 0)
        assertTrue(finance.snapshot().entries.isEmpty())
    }
    @Test fun explicitClickAndMountedEditorReadOnlyTheirOriginalHostAndRevokedGrantReadsNothing() {
        val original=FinanceHostFixtures.ALIPAY;val other=FinanceHostFixtures.WECHAT
        FinanceHostFixtures.windows(service,FinanceHostFixtures.window(1,original),FinanceHostFixtures.window(2,other,false))
        val lease=requireNotNull(reader.bindActive());assertNotNull(lease.host.identity)
        FinanceHostFixtures.windows(service,FinanceHostFixtures.window(1,original,false),FinanceHostFixtures.window(2,other,false),
            FinanceHostFixtures.window(3,service.packageName,type=android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM))
        assertTrue(lease.editorCurrent(reader.current(lease,{true},true)))
        assertEquals(0,reads[other] ?: 0)
        val before=reads.toMap();reader.current(lease,{false},true);assertEquals(before,reads)
        FinanceHostFixtures.windows(service,FinanceHostFixtures.window(2,other))
        assertFalse(lease.editorCurrent(reader.current(lease,{true},true)));assertEquals(before,reads)
    }
    @Test fun changingPageBetweenEvidenceAndBindingCannotPairOldFactsWithNewWindowOrTransaction()=runBlocking {
        val a=FinanceHostFixtures.ALIPAY;val recognized=FinanceHostFixtures.evidence(a)
        val target=VisibleDetailScheduler.Target(a,1)
        FinanceHostFixtures.windows(service,FinanceHostFixtures.window(2,FinanceHostFixtures.WECHAT))
        assertNull(reader.bindVisible(target,recognized){true});assertTrue(reads.isEmpty())
        FinanceHostFixtures.windows(service,FinanceHostFixtures.window(1,a,order="ORDER-999999"))
        assertNull(reader.bindVisible(target,recognized){true})
        assertTrue(db.financeIntakeDao().proposals().isEmpty());assertTrue(finance.snapshot().entries.isEmpty())
        FinanceHostFixtures.windows(service,FinanceHostFixtures.window(1,a))
        val live=requireNotNull(reader.bindVisible(target,recognized){true})
        assertNotNull(FinanceAutomationRepository(db,finance).discoverVisible(recognized){live.captureCurrent(reader.current(live,{true}))}.proposal)
    }
    @Test fun authorizationLostAcrossRepositorySuspensionRollsBackInsertedProposalAndEvidence()=runBlocking {
        val evidence=FinanceHostFixtures.evidence();val before=finance.snapshot();var checks=0
        assertTrue(runCatching {FinanceAutomationRepository(db,finance).discoverVisible(evidence){++checks==1}}.isFailure)
        assertEquals(before,finance.snapshot());assertTrue(db.financeIntakeDao().proposals().isEmpty());assertTrue(checks>=2)
    }
}
