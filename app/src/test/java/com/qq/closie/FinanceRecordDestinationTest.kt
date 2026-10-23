package com.qq.closie

import com.qq.closie.ui.lifeos.finance.FinanceRecordDestination
import org.junit.Assert.*
import org.junit.Test

class FinanceRecordDestinationTest {
    @Test fun corruptOrMissingTargetsNeverRestoreAsNewEntries() {
        for (parts in listOf(emptyList(), listOf("edit"), listOf("view", ""), listOf("edit", "entry", "NaN"),
            listOf("edit", "entry", "-1"), listOf("unknown", "entry"))) {
            assertEquals(FinanceRecordDestination.Closed, FinanceRecordDestination.decode(parts))
        }
    }
    @Test fun viewingEditingAndCreatingRemainDistinctAcrossRestore() {
        assertEquals(FinanceRecordDestination.Viewing("entry"), FinanceRecordDestination.decode(listOf("view", "entry")))
        assertEquals(FinanceRecordDestination.Editing("entry", 7), FinanceRecordDestination.decode(listOf("edit", "entry", "7")))
        assertEquals(FinanceRecordDestination.Creating("draft"), FinanceRecordDestination.decode(listOf("create", "draft")))
    }
}
