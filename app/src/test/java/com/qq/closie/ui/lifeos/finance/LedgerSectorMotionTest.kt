package com.qq.closie.ui.lifeos.finance

import org.junit.Assert.*
import org.junit.Test

class LedgerSectorMotionTest {
    @Test fun fortyRealCategoriesStillFillExactlyOneCircleAtTheTerminalFrame() {
        val sweeps=(1..40).map {it.toFloat()}
        val sum=sweeps.sum()
        assertEquals(0f,sweeps.indices.sumOf {ledgerSectorProgress(0f,it).toDouble()}.toFloat(),0f)
        val painted=sweeps.mapIndexed {index,amount->amount/sum*360f*ledgerSectorProgress(1f,index)}.sum()
        assertEquals(360f,painted,.001f)
        assertTrue(sweeps.indices.all {ledgerSectorProgress(.5f,it) in 0f..1f})
    }
}
