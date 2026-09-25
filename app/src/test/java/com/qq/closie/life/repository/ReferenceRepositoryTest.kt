package com.qq.closie.life.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.media.MediaType
import com.qq.closie.life.reference.ReferenceEntityType
import com.qq.closie.life.reference.ReferenceStatus
import com.qq.closie.life.reference.ReferenceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for 资料库's data layer.
 *
 * The assertions here are mostly about *invariants the UI relies on* rather than about CRUD:
 * every reference being a real LifeEntity, one media asset being shareable, and deleting a
 * reference never touching the media. Those are the properties that, if they broke, would fail
 * silently in the app and only be noticed as data loss.
 *
 * Robolectric is pinned to API 34 because 4.12.2 does not support 35 (the app's targetSdk).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReferenceRepositoryTest {

    private lateinit var db: LifeDatabase
    private lateinit var life: LifeRepository
    private lateinit var media: MediaRepository
    private lateinit var repo: ReferenceRepository
    private lateinit var captureRepo: CaptureRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        life = LifeRepository(db)
        media = MediaRepository(db)
        repo = ReferenceRepository(db, life, media)
        captureRepo = CaptureRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    //  Every reference is a LifeEntity
    // ------------------------------------------------------------------

    @Test
    fun create_writesBackingLifeEntityOfTypeReference() = runTest {
        val ref = repo.create(
            title = "React 19 的 use() 怎么用",
            referenceType = ReferenceType.ARTICLE,
            now = 1_000L
        )

        val entity = life.getEntity(ref.lifeEntityId)
        assertThat(entity).isNotNull()
        assertThat(entity!!.entityType).isEqualTo(ReferenceEntityType.REFERENCE)
        assertThat(entity.deletedAt).isNull()
        // A brand-new entity starts at revision 1 — creating a reference is one write, not two.
        assertThat(entity.revision).isEqualTo(1L)
    }

    @Test
    fun create_normalizesAndNullsEmptyOptionalFields() = runTest {
        val ref = repo.create(
            title = "  收衣服  ",
            referenceType = ReferenceType.NOTE,
            summary = "   ",
            ocrText = "",
            sourceUrl = "  ",
            sourceName = "",
            author = "   ",
            now = 1_000L
        )

        assertThat(ref.title).isEqualTo("收衣服")
        // Whitespace-only input must become NULL, not an empty string: the UI checks for null to
        // decide whether to render a row at all, and "" would render an empty line.
        assertThat(ref.summary).isNull()
        assertThat(ref.ocrText).isNull()
        assertThat(ref.sourceUrl).isNull()
        assertThat(ref.sourceName).isNull()
        assertThat(ref.author).isNull()
    }

    @Test
    fun create_withUnknownCaptureId_storesNullRatherThanDanglingId() = runTest {
        // There is no physical FK on originalCaptureId, so the repository is the only guard. A
        // dangling id would be permanent — captures are never cascade-deleted.
        val ref = repo.create(
            title = "无来源截图",
            referenceType = ReferenceType.NOTE,
            originalCaptureId = "capture-that-does-not-exist",
            now = 1_000L
        )

        assertThat(ref.originalCaptureId).isNull()
    }

    @Test
    fun create_withRealCaptureId_keepsProvenance() = runTest {
        val captureDao = db.captureDao()
        val capture = com.qq.closie.life.capture.CaptureItemEntity(
            id = "cap-real",
            source = com.qq.closie.life.capture.CaptureSource.MANUAL,
            status = com.qq.closie.life.capture.CaptureStatus.CONFIRMED,
            rawText = "某篇文章的链接",
            createdAt = 1_000L,
            updatedAt = 1_000L
        )
        captureDao.insert(capture)

        val ref = repo.create(
            title = "一篇好文章",
            referenceType = ReferenceType.ARTICLE,
            originalCaptureId = "cap-real",
            now = 2_000L
        )

        assertThat(ref.originalCaptureId).isEqualTo("cap-real")
        assertThat(repo.findByOriginalCaptureId("cap-real")!!.id).isEqualTo(ref.id)
    }

    @Test
    fun create_directlyAsOrganized_stampsOrganizedAt() = runTest {
        val inbox = repo.create(
            title = "还没整理",
            referenceType = ReferenceType.NOTE,
            now = 1_000L
        )
        val organized = repo.create(
            title = "已经整理过了",
            referenceType = ReferenceType.NOTE,
            status = ReferenceStatus.ORGANIZED,
            now = 2_000L
        )

        assertThat(inbox.status).isEqualTo(ReferenceStatus.INBOX)
        assertThat(inbox.organizedAt).isNull()
        assertThat(organized.organizedAt).isEqualTo(2_000L)
    }

    // ------------------------------------------------------------------
    //  Status transitions
    // ------------------------------------------------------------------

    @Test
    fun markOrganized_stampsTimeOnceAndKeepsItOnArchive() = runTest {
        val ref = repo.create(title = "x", referenceType = ReferenceType.NOTE, now = 1_000L)

        assertThat(repo.markOrganized(ref.id, now = 2_000L)).isTrue()
        assertThat(repo.getById(ref.id)!!.organizedAt).isEqualTo(2_000L)

        // Re-organising must not keep pushing the date forward — organizedAt records the FIRST
        // time the item left the inbox.
        assertThat(repo.markOrganized(ref.id, now = 3_000L)).isTrue()
        assertThat(repo.getById(ref.id)!!.organizedAt).isEqualTo(2_000L)

        repo.archive(ref.id, now = 4_000L)
        assertThat(repo.getById(ref.id)!!.organizedAt).isEqualTo(2_000L)
    }

    @Test
    fun unarchive_returnsToInboxAndClearsOrganizedAt() = runTest {
        val ref = repo.create(title = "x", referenceType = ReferenceType.NOTE, now = 1_000L)
        repo.archive(ref.id, now = 2_000L)

        repo.unarchive(ref.id, now = 3_000L)

        val reloaded = repo.getById(ref.id)!!
        assertThat(reloaded.status).isEqualTo(ReferenceStatus.INBOX)
        assertThat(reloaded.organizedAt).isNull()
    }

    @Test
    fun statusChange_isVisibleThroughObserveById() = runTest {
        val ref = repo.create(title = "x", referenceType = ReferenceType.NOTE, now = 1_000L)
        assertThat(repo.observeById(ref.id).first()!!.status).isEqualTo(ReferenceStatus.INBOX)

        repo.markOrganized(ref.id, now = 2_000L)

        assertThat(repo.observeById(ref.id).first()!!.status).isEqualTo(ReferenceStatus.ORGANIZED)
    }

    @Test
    fun statusChange_bumpsLifeEntityRevision() = runTest {
        // Soft delete and edit both bump the revision so the audit trail sees a real change; status
        // changes have to do the same or a future sync layer would miss them.
        val ref = repo.create(title = "x", referenceType = ReferenceType.NOTE, now = 1_000L)
        assertThat(life.getEntity(ref.lifeEntityId)!!.revision).isEqualTo(1L)

        repo.markOrganized(ref.id, now = 2_000L)

        assertThat(life.getEntity(ref.lifeEntityId)!!.revision).isEqualTo(2L)
    }

    // ------------------------------------------------------------------
    //  Edit
    // ------------------------------------------------------------------

    @Test
    fun update_appliesOnlyProvidedFields() = runTest {
        val ref = repo.create(
            title = "原标题",
            referenceType = ReferenceType.ARTICLE,
            summary = "原摘要",
            sourceUrl = "https://example.com/a",
            now = 1_000L
        )

        val updated = repo.update(id = ref.id, title = "新标题", now = 2_000L)!!

        assertThat(updated.title).isEqualTo("新标题")
        assertThat(updated.summary).isEqualTo("原摘要")
        assertThat(updated.sourceUrl).isEqualTo("https://example.com/a")
        assertThat(updated.updatedAt).isEqualTo(2_000L)
    }

    @Test
    fun update_canClearAnOptionalFieldByPassingEmptyString() = runTest {
        // Clearing and "not touching" have to be distinguishable, which is why the clear path goes
        // through a non-null empty string rather than through null.
        val ref = repo.create(
            title = "有摘要",
            referenceType = ReferenceType.NOTE,
            summary = "要去掉我",
            now = 1_000L
        )

        val updated = repo.update(id = ref.id, summary = "", now = 2_000L)!!

        assertThat(updated.summary).isNull()
    }

    @Test
    fun update_unknownIdReturnsNull() = runTest {
        assertThat(repo.update(id = "nope", title = "x", now = 1_000L)).isNull()
    }

    // ------------------------------------------------------------------
    //  Search
    // ------------------------------------------------------------------

    @Test
    fun search_matchesTitleSummaryOcrSourceNameAndUrl() = runTest {
        repo.create(
            title = "收纳小技巧",
            referenceType = ReferenceType.GUIDE,
            ocrText = "把毛衣卷起来放",
            now = 1_000L
        )
        repo.create(
            title = "An article",
            referenceType = ReferenceType.ARTICLE,
            sourceName = "少数派",
            now = 2_000L
        )
        repo.create(
            title = "Another",
            referenceType = ReferenceType.ARTICLE,
            sourceUrl = "https://example.com/workspace",
            now = 3_000L
        )

        assertThat(repo.search("毛衣").first().map { it.title }).containsExactly("收纳小技巧")
        assertThat(repo.search("少数派").first().map { it.title }).containsExactly("An article")
        assertThat(repo.search("workspace").first().map { it.title }).containsExactly("Another")
    }

    @Test
    fun search_treatsPercentAndUnderscoreAsLiteralCharacters() = runTest {
        // Without escaping, "50%" would match every row — a search that silently returns everything
        // is worse than one that returns nothing.
        repo.create(title = "打折 50% 的毛衣", referenceType = ReferenceType.NOTE, now = 1_000L)
        repo.create(title = "完全无关的东西", referenceType = ReferenceType.NOTE, now = 2_000L)
        repo.create(title = "snake_case_name", referenceType = ReferenceType.NOTE, now = 3_000L)

        assertThat(repo.search("50%").first().map { it.title }).containsExactly("打折 50% 的毛衣")
        assertThat(repo.search("_").first().map { it.title }).containsExactly("snake_case_name")
    }

    @Test
    fun search_blankQueryReturnsEverything() = runTest {
        repo.create(title = "a", referenceType = ReferenceType.NOTE, now = 1_000L)
        repo.create(title = "b", referenceType = ReferenceType.NOTE, now = 2_000L)

        assertThat(repo.search("   ").first()).hasSize(2)
    }

    @Test
    fun escapeLike_escapesBackslashBeforeWildcards() = runTest {
        // Order matters: escaping % and _ first would produce sequences the backslash pass then
        // double-escapes.
        assertThat(repo.escapeLike("50%")).isEqualTo("50\\%")
        assertThat(repo.escapeLike("a_b")).isEqualTo("a\\_b")
        assertThat(repo.escapeLike("a\\b")).isEqualTo("a\\\\b")
        assertThat(repo.escapeLike("100%_x")).isEqualTo("100\\%\\_x")
    }

    // ------------------------------------------------------------------
    //  Tags go through the LifeEntity
    // ------------------------------------------------------------------

    @Test
    fun addTag_isVisibleThroughBothTheReferenceAndTheLifeEntity() = runTest {
        val ref = repo.create(title = "x", referenceType = ReferenceType.NOTE, now = 1_000L)

        val tag = repo.addTag(ref.id, "  收纳  ")

        assertThat(tag).isNotNull()
        assertThat(tag!!.normalizedName).isEqualTo("收纳")
        assertThat(repo.tagsFor(ref).map { it.id }).containsExactly(tag.id)
        // The same rows, read through the shared table — one tagging system, not two.
        assertThat(life.getTagsForEntity(ref.lifeEntityId).map { it.id }).containsExactly(tag.id)
    }

    @Test
    fun removeTag_detachesOnlyThatTag() = runTest {
        val ref = repo.create(title = "x", referenceType = ReferenceType.NOTE, now = 1_000L)
        val keep = repo.addTag(ref.id, "保留")!!
        val drop = repo.addTag(ref.id, "丢弃")!!

        assertThat(repo.removeTag(ref.id, drop.id)).isTrue()

        assertThat(repo.tagsFor(ref).map { it.id }).containsExactly(keep.id)
    }

    // ------------------------------------------------------------------
    //  Media — linked, shared, never cascaded
    // ------------------------------------------------------------------

    @Test
    fun linkMedia_attachesAssetToTheLifeEntity() = runTest {
        val ref = repo.create(title = "截图", referenceType = ReferenceType.NOTE, now = 1_000L)
        val asset = media.createMediaAsset(MediaType.IMAGE, timestamp = 2_000L)

        assertThat(repo.linkMedia(ref.id, asset.id)).isTrue()

        assertThat(repo.mediaFor(ref)).containsExactly(asset.id)
    }

    @Test
    fun linkMedia_rejectsMissingAssetOrReference() = runTest {
        val ref = repo.create(title = "截图", referenceType = ReferenceType.NOTE, now = 1_000L)
        val asset = media.createMediaAsset(MediaType.IMAGE, timestamp = 2_000L)

        assertThat(repo.linkMedia(ref.id, "no-such-asset")).isFalse()
        assertThat(repo.linkMedia("no-such-ref", asset.id)).isFalse()
        assertThat(repo.mediaFor(ref)).isEmpty()
    }

    @Test
    fun oneMediaAssetCanBackSeveralReferences() = runTest {
        // This is the whole reason delete() does not cascade. A photo of a receipt may legitimately
        // belong to 财务 and 旅行 at the same time.
        val a = repo.create(title = "A", referenceType = ReferenceType.NOTE, now = 1_000L)
        val b = repo.create(title = "B", referenceType = ReferenceType.NOTE, now = 2_000L)
        val asset = media.createMediaAsset(MediaType.IMAGE, timestamp = 3_000L)

        repo.linkMedia(a.id, asset.id)
        repo.linkMedia(b.id, asset.id)

        assertThat(repo.mediaFor(a)).containsExactly(asset.id)
        assertThat(repo.mediaFor(b)).containsExactly(asset.id)
    }

    @Test
    fun deleteReference_leavesMediaAndOtherReferencesUntouched() = runTest {
        val keep = repo.create(title = "留着", referenceType = ReferenceType.NOTE, now = 1_000L)
        val drop = repo.create(title = "删掉", referenceType = ReferenceType.NOTE, now = 2_000L)
        val asset = media.createMediaAsset(MediaType.IMAGE, timestamp = 3_000L)
        repo.linkMedia(keep.id, asset.id)
        repo.linkMedia(drop.id, asset.id)

        assertThat(repo.delete(drop.id, now = 4_000L)).isTrue()

        // The reference is gone from the typed table...
        assertThat(repo.getById(drop.id)).isNull()
        // ...its LifeEntity is soft-deleted, not erased...
        assertThat(life.getEntity(drop.lifeEntityId)!!.deletedAt).isEqualTo(4_000L)
        // ...and the shared media asset is still there for the other reference.
        assertThat(db.mediaDao().getAssetById(asset.id)).isNotNull()
        assertThat(repo.mediaFor(keep)).containsExactly(asset.id)
    }

    @Test
    fun delete_unknownIdIsANoOp() = runTest {
        assertThat(repo.delete("nope", now = 1_000L)).isFalse()
    }

    // ------------------------------------------------------------------
    //  Reading module source
    // ------------------------------------------------------------------

    @Test
    fun observeReading_returnsOnlyActiveReadingAndArticles() = runTest {
        repo.create(title = "在读书", referenceType = ReferenceType.READING, now = 1_000L)
        repo.create(title = "在读文章", referenceType = ReferenceType.ARTICLE, now = 2_000L)
        repo.create(title = "一条笔记", referenceType = ReferenceType.NOTE, now = 3_000L)
        val archived = repo.create(title = "归档的书", referenceType = ReferenceType.READING, now = 4_000L)
        repo.archive(archived.id, now = 5_000L)

        val titles = repo.observeReading().first().map { it.title }

        assertThat(titles).containsExactly("在读书", "在读文章")
    }

    @Test
    fun countsTrackTheInbox() = runTest {
        assertThat(repo.count()).isEqualTo(0)
        assertThat(repo.countInbox()).isEqualTo(0)

        val a = repo.create(title = "a", referenceType = ReferenceType.NOTE, now = 1_000L)
        repo.create(title = "b", referenceType = ReferenceType.NOTE, now = 2_000L)

        assertThat(repo.count()).isEqualTo(2)
        assertThat(repo.countInbox()).isEqualTo(2)

        repo.markOrganized(a.id, now = 3_000L)

        assertThat(repo.count()).isEqualTo(2)
        assertThat(repo.countInbox()).isEqualTo(1)
    }

    @Test
    fun distinctTypes_reportsOnlyTypesActuallyPresent() = runTest {
        repo.create(title = "a", referenceType = ReferenceType.NOTE, now = 1_000L)
        repo.create(title = "b", referenceType = ReferenceType.ARTICLE, now = 2_000L)
        repo.create(title = "c", referenceType = ReferenceType.ARTICLE, now = 3_000L)

        assertThat(repo.distinctTypes()).containsExactly(ReferenceType.NOTE, ReferenceType.ARTICLE)
    }

    // ------------------------------------------------------------------
    //  OCR write (bug #13)
    // ------------------------------------------------------------------

    /**
     * OCR arriving must bump the backing LifeEntity's revision.
     *
     * bug #13: `updateOcrText` wrote the reference row inside no transaction and never touched the
     * entity. Two consequences — the write was not atomic with the revision, and anything watching
     * the life graph never saw that the item had changed, because the revision *is* the graph's
     * change notification. An item whose OCR text appears and whose revision does not move is
     * invisible to sync and to any "recently updated" view.
     */
    @Test
    fun updateOcrText_bumpsLifeEntityRevision() = runTest {
        val ref = repo.create(title = "截图", referenceType = ReferenceType.NOTE, now = 1_000L)
        assertThat(life.getEntity(ref.lifeEntityId)!!.revision).isEqualTo(1L)

        assertThat(repo.updateOcrText(ref.id, "识别出来的文字", now = 2_000L)).isTrue()

        assertThat(repo.getById(ref.id)!!.ocrText).isEqualTo("识别出来的文字")
        assertThat(life.getEntity(ref.lifeEntityId)!!.revision).isEqualTo(2L)
    }

    /** OCR output is searchable, which is the point of storing it separately from the summary. */
    @Test
    fun updateOcrText_makesTheTextSearchable() = runTest {
        val ref = repo.create(title = "截图", referenceType = ReferenceType.NOTE, now = 1_000L)
        repo.updateOcrText(ref.id, "发票编号 A12345", now = 2_000L)

        assertThat(repo.search("A12345").first().map { it.id }).contains(ref.id)
    }

    @Test
    fun updateOcrText_unknownIdIsANoOp() = runTest {
        assertThat(repo.updateOcrText("nope", "x", now = 1_000L)).isFalse()
    }

    // ------------------------------------------------------------------
    //  Idempotency (bug #4)
    // ------------------------------------------------------------------

    /**
     * `findByOriginalCaptureId` is the lookup that makes capture→reference import idempotent.
     *
     * Without it, tapping 存进资料库 twice (or a retried import) produced two identical references
     * pointing at the same capture — two entries in 资料库 for one screenshot, and no way to tell
     * which was the "real" one. The importer now checks this first and returns the existing reference
     * rather than creating a second.
     */
    @Test
    fun findByOriginalCaptureId_returnsTheExistingReferenceOrNull() = runTest {
        val capture = captureRepo.create(
            id = "cap-1",
            source = com.qq.closie.life.capture.CaptureSource.CLIPBOARD,
            rawText = "一段文字",
            now = 1_000L
        )
        assertThat(capture).isTrue()

        val ref = repo.create(
            title = "来自记录",
            referenceType = ReferenceType.NOTE,
            originalCaptureId = "cap-1",
            now = 2_000L
        )

        assertThat(repo.findByOriginalCaptureId("cap-1")?.id).isEqualTo(ref.id)
        assertThat(repo.findByOriginalCaptureId("no-such-capture")).isNull()
    }
}
