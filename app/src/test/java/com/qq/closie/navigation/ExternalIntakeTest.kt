package com.qq.closie.navigation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.capture.CaptureStatus
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.reference.ReferenceImporter
import com.qq.closie.life.reference.ReferenceStatus
import com.qq.closie.life.repository.*
import com.qq.closie.life.web.WebMetadata
import com.qq.closie.navigation.intake.ExternalIntakeCoordinator
import com.qq.closie.life.ui.capture.CaptureDetailViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalIntakeTest {
    private lateinit var db: LifeDatabase
    private lateinit var captures: CaptureRepository
    private lateinit var references: ReferenceRepository
    private lateinit var media: MediaRepository
    @Before fun setUp() {
        RestoreStartupGate.resetForTesting()
        RestoreStartupGate.markReady()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LifeDatabase::class.java)
            .allowMainThreadQueries().build()
        captures = CaptureRepository(db)
        media = MediaRepository(db)
        references = ReferenceRepository(db, LifeRepository(db), media, captures)
    }
    @After fun tearDown() { db.close(); RestoreStartupGate.resetForTesting() }
    private fun coordinator(fail: Boolean = false) = ExternalIntakeCoordinator(captures,
        ReferenceImporter(references, captures, media, readMetadata = { url ->
            if (fail) error("Network unavailable")
            WebMetadata(url, title = "Source title", description = "Raw extracted source", fetched = true)
        }))
    @Test fun ordinaryLinkPreservesEvidenceAndSeparatesExtractionFromUserNotes() = runTest {
        val command = ExternalNavCommand.ReferenceLink("original share https://example.com", "https://example.com", 1)
        val intake = coordinator()
        val route = intake.intake(command)
        assertEquals(route, intake.intake(command))
        assertEquals(1, captures.count())
        val capture = captures.observeAll().first().single()
        assertEquals(command.originalText, capture.rawText)
        assertEquals(CaptureStatus.NEW, capture.status)
        val reference = references.findByOriginalCaptureId(capture.id)!!
        assertEquals(ReferenceStatus.INBOX, reference.status)
        assertNull(reference.summary)
        assertEquals("Raw extracted source", reference.ocrText)
        assertEquals(LifeOsRoute.referenceEdit(reference.id), route)
    }
    @Test fun metadataFailureLeavesAnOpenableRawCapture() = runTest {
        val route = coordinator(fail = true).intake(ExternalNavCommand.ReferenceLink("raw", "https://example.com", 2))
        val capture = captures.observeAll().first().single()
        assertEquals(LifeOsRoute.capture(capture.id), route)
        assertEquals("raw", capture.rawText)
        assertNull(references.findByOriginalCaptureId(capture.id))
    }
    @Test fun plainTextDoesNotBecomeAReferenceOrBusinessTruth() = runTest {
        val route = coordinator().intake(ExternalNavCommand.CaptureText("only evidence", 3))
        val capture = captures.observeAll().first().single()
        assertEquals(LifeOsRoute.capture(capture.id), route)
        assertNull(references.findByOriginalCaptureId(capture.id))
        assertEquals(CaptureStatus.NEW, capture.status)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancellingOrSavingBlankManualEditorCreatesNoEvidence() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val model = CaptureDetailViewModel(LifeOsRoute.NEW_ID, captures, media)
            store.put("capture", model)
            model.state.first { it.loaded }
            model.save()
            assertTrue(model.state.value.exit)
            assertEquals(0, captures.count())
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun manualSaveKeepsRawEvidenceSeparateAndRestoresCreatedIdentity() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val saved = SavedStateHandle()
        try {
            val model = CaptureDetailViewModel(LifeOsRoute.NEW_ID, captures, media, saved)
            store.put("capture", model)
            model.state.first { it.loaded }
            model.title("manual title")
            model.note("my annotation")
            model.save()
            val record = model.state.first { it.record != null && !it.busy }.record!!
            assertEquals("my annotation", record.note)
            assertNull(record.rawText)
            assertEquals(CaptureStatus.NEW, record.status)
            store.clear()
            val recreated = CaptureDetailViewModel(LifeOsRoute.NEW_ID, captures, media, saved)
            store.put("recreated", recreated)
            assertEquals(record.id, recreated.state.first { it.loaded }.record?.id)
            assertEquals(1, captures.count())
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
