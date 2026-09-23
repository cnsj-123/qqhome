package com.xiaoming.closie

import com.xiaoming.closie.data.draft.DraftImageCleanup
import com.xiaoming.closie.data.draft.DraftStore
import com.xiaoming.closie.data.model.Ootd
import com.xiaoming.closie.data.model.Outfit
import com.xiaoming.closie.data.model.Placement
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftStoreTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "draft_test_${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun `ootd draft save load delete`() {
        val dir = tempDir()
        val store = DraftStore(dir)
        val draft = Ootd(id = "d1", date = "2026-09-23", itemIds = listOf("i1", "i2"), note = "周末")
        store.saveOotdDraft(draft)

        assertEquals(1, store.listOotdDrafts().size)
        assertEquals("周末", store.listOotdDrafts().first().note)

        val removed = store.deleteOotdDraft("d1")
        assertEquals("d1", removed?.id)
        assertTrue(store.listOotdDrafts().isEmpty())
    }

    @Test
    fun `outfit draft save load delete`() {
        val dir = tempDir()
        val store = DraftStore(dir)
        val draft = Outfit(id = "o1", name = "通勤", itemIds = listOf("i1"), placements = listOf(Placement("i1")))
        store.saveOutfitDraft(draft)

        assertEquals(1, store.listOutfitDrafts().size)
        assertEquals("通勤", store.listOutfitDrafts().first().name)

        val removed = store.deleteOutfitDraft("o1")
        assertEquals("o1", removed?.id)
        assertTrue(store.listOutfitDrafts().isEmpty())
    }

    @Test
    fun `deleting an unknown draft returns null`() {
        assertNull(DraftStore(tempDir()).deleteOotdDraft("missing"))
    }

    @Test
    fun `saving a draft with an existing id updates it instead of duplicating`() {
        val dir = tempDir()
        val store = DraftStore(dir)
        // Simulates editing a live OOTD ("live-1") and saving it as a draft: the draft must keep
        // the live entity id so restoring + saving updates the original, not a copy.
        store.saveOotdDraft(Ootd(id = "live-1", date = "2026-09-23", itemIds = listOf("i1")))
        store.saveOotdDraft(Ootd(id = "live-1", date = "2026-09-24", itemIds = listOf("i1", "i2")))

        val drafts = store.listOotdDrafts()
        assertEquals(1, drafts.size)
        assertEquals("live-1", drafts.first().id)
        assertEquals("2026-09-24", drafts.first().date)
        assertEquals(2, drafts.first().itemIds.size)
    }

    @Test
    fun `drafts survive a store re-instantiation`() {
        val dir = tempDir()
        DraftStore(dir).saveOotdDraft(Ootd(id = "d1", date = "2026-09-23", itemIds = listOf("i1")))

        // New instance over the same folder simulates process death.
        val reloaded = DraftStore(dir)
        assertEquals(1, reloaded.listOotdDrafts().size)
        assertEquals("d1", reloaded.listOotdDrafts().first().id)
    }

    @Test
    fun `a restored backup without draft files yields empty draft lists`() {
        // Old backups carry no draft JSON; after restore the drafts folder is simply empty.
        val dir = tempDir()
        val store = DraftStore(dir)
        assertTrue(store.listOotdDrafts().isEmpty())
        assertTrue(store.listOutfitDrafts().isEmpty())
    }

    // ---- DraftImageCleanup ----

    @Test
    fun `orphans only deletes images referenced nowhere`() {
        val removed = listOf("a.png", "b.png", "c.png", "")
        val live = listOf("a.png")
        val remaining = listOf("b.png")
        assertEquals(setOf("c.png"), DraftImageCleanup.orphans(removed, live, remaining))
    }

    @Test
    fun `orphans keeps everything when other drafts still reference it`() {
        val removed = listOf("a.png")
        val remaining = listOf("a.png", "x.png")
        assertTrue(DraftImageCleanup.orphans(removed, emptyList(), remaining).isEmpty())
    }
}
