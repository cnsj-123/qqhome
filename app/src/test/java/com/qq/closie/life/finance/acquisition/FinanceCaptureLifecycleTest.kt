package com.qq.closie.life.finance.acquisition

import com.qq.closie.life.finance.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class FinanceCaptureLifecycleTest {
    private val page=FinanceCapturePage("synthetic.host",7,1,3)
    private val fields=FinanceOcrFields(1234,1000,FinanceProposalKind.EXPENSE,null,"synthetic","CNY")
    private class Frame {var released=false}
    private inner class Harness {
        var valid=true;var stored=0;var mounted=0;var shots=0;var tokens=0
        var captureHook:()->Unit={}
        var ocrHook:suspend ()->Unit={}
        var storeHook:()->Unit={}
        val frame=Frame()
        suspend fun run()=runFinanceCapture(page,8,4,{valid},{valid},
            token={tokens++;FinanceCommandToken("original",2)},hide={},
            capture={assertEquals(page,it.page);shots++;captureHook();frame},
            recognize={ocrHook();it.released=true;fields},releaseUnused={it.released=true},
            store={request,_,current->assertEquals(2L,request.token.restoreEpoch);check(current());stored++;storeHook();"candidate"},
            present={request,_,current->assertEquals(4L,request.restoreGeneration);if(current())mounted++})
    }
    @Test fun hostChangeDuringHideDelayCannotCaptureNextPage()=runTest {
        val h=Harness();val result=async {runCatching {h.run()}}
        runCurrent();advanceTimeBy(179);h.valid=false;advanceUntilIdle()
        assertTrue(result.await().isFailure);assertEquals(0,h.shots);assertEquals(0,h.stored)
    }
    @Test fun restoreDuringOcrRejectsOriginalRequestWithoutRebindingToken()=runTest {
        val h=Harness();h.ocrHook={delay(100);h.valid=false}
        assertTrue(runCatching {h.run()}.isFailure)
        assertEquals(1,h.tokens);assertEquals(0,h.stored);assertEquals(0,h.mounted);assertTrue(h.frame.released)
    }
    @Test fun hostChangeAfterCaptureReleasesUnconsumedFrame()=runTest {
        val h=Harness();h.captureHook={h.valid=false}
        assertTrue(runCatching {h.run()}.isFailure);assertTrue(h.frame.released);assertEquals(0,h.stored)
    }
    @Test fun lateCandidateCanRemainButCannotMountOnNewHost()=runTest {
        val h=Harness();h.storeHook={h.valid=false};h.run()
        assertEquals(1,h.stored);assertEquals(0,h.mounted);assertEquals(1,h.tokens)
    }
    @Test fun cancelledRequestNeverCapturesAndNeverStores()=runTest {
        val h=Harness();val task=launch {h.run()};runCurrent();task.cancel();advanceUntilIdle()
        assertEquals(0,h.shots);assertEquals(0,h.stored)
    }
    @Test fun validRequestUsesOriginalIdentityAndEpochThroughout()=runTest {
        val h=Harness();assertEquals("candidate",h.run())
        assertEquals(1,h.tokens);assertEquals(1,h.shots);assertEquals(1,h.stored);assertEquals(1,h.mounted)
    }
}
