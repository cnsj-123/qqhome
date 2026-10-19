package com.qq.closie.ui.lifeos.finance
import org.junit.Assert.*
import org.junit.Test
class FinanceCalendarStopsTest {
    @Test fun releaseDocksAtOneStopAndVelocityMovesOnlyToNextAvailableStop() {
        assertEquals(FinanceCalendarStop.MONTH,financeCalendarRelease(.1f,0f))
        assertEquals(FinanceCalendarStop.WEEK,financeCalendarRelease(.55f,0f))
        assertEquals(FinanceCalendarStop.COLLAPSED,financeCalendarRelease(.85f,0f))
        assertEquals(FinanceCalendarStop.WEEK,financeCalendarRelease(.1f,-600f))
        assertEquals(FinanceCalendarStop.COLLAPSED,financeCalendarRelease(.7f,-600f))
        assertEquals(FinanceCalendarStop.WEEK,financeCalendarRelease(.9f,600f))
    }
}
