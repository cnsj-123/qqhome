package com.qq.closie.ui.lifeos.home

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.backup.LifeBackupApplier
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.finance.FinanceRoomTest
import com.qq.closie.life.repository.HomePhotoRepository
import com.qq.closie.life.repository.LifeRepository
import com.qq.closie.life.repository.PlanRepository
import com.qq.closie.navigation.modules.PresentationFactory
import com.qq.closie.ui.lifeos.LifeOsShellViewModel
import com.qq.closie.ui.lifeos.captureNativeAttachedWindows
import com.qq.closie.ui.lifeos.media.LifeMediaUi
import com.qq.closie.ui.lifeos.theme.LifeOsTheme
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w405dp-h880dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeAcceptanceEvidenceTest : FinanceRoomTest() {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var models: ViewModelStore? = null
    private val today = LocalDate.of(2026, 10, 12)
    private val zone = ZoneId.systemDefault()
    private fun stamp(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()

    @After fun closePresentation() {
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { models?.clear() }
    }

    @Test fun realPlansAndPhotoNotesFeedHomeAndCalendarThenRestoreDropsTheOldPlan() {
        val plans = PlanRepository(db, LifeRepository(db))
        val photos = HomePhotoRepository(db)
        val note = "午后散步的照片备注"
        val planNote = "想找一本关于植物的随笔集。"
        val old = runBlocking {
            val photo = photos.grantValidated(contentUri(imageFile(0)).toString(), "image/png", null, stamp(today))
            photos.setCaption(photo, note, null, RestoreStartupGate.state.value.restoreGeneration)
            val before = LifeBackupApplier.snapshot(db)
            plans.create("周末去旧书店", planNote, stamp(today.plusDays(5)), now = stamp(today), homeVisible = true)
            before
        }
        val provider = PlanHomeStateProvider(
            MediaHomeStateProvider(FinanceHomeStateProvider(finance), photos), plans, zone
        ) { today }
        lateinit var shell: LifeOsShellViewModel
        compose.setContent {
            models = LocalViewModelStoreOwner.current?.viewModelStore
            val vm: LifeOsShellViewModel = viewModel(factory = PresentationFactory(LifeOsShellViewModel::class.java) {
                LifeOsShellViewModel(provider)
            })
            shell = vm
            LaunchedEffect(vm) { vm.selectDate(today) }
            val state by vm.home.collectAsState()
            HomeScene(state, onToday = { vm.selectDate(today) }, onDate = vm::selectDate)
        }
        await { shell.home.value.date == today && shell.home.value.futureIntents.singleOrNull()?.note == planNote }
        compose.onNodeWithText("周末去旧书店").assertIsDisplayed()
        compose.onNodeWithText(planNote).assertIsDisplayed()
        capture("home-plan-canonical")

        compose.onNodeWithText("10月12日").performClick()
        compose.onNodeWithTag("home-photo-2026-10-12", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("home-important-2026-10-17", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("home-writing-2026-10-12", useUnmergedTree = true).assertIsDisplayed()
        for (label in listOf("有照片", "重要日子", "文字记录")) compose.onNodeWithText(label).assertIsDisplayed()
        capture("home-calendar-markers")
        compose.onNodeWithText("2026年10月").performClick()
        compose.onNodeWithContentDescription("年份").assertIsDisplayed()
        compose.onNodeWithContentDescription("月份").assertIsDisplayed()
        capture("home-calendar-wheel")
        compose.onNodeWithText("11月").performClick()
        compose.onNodeWithText("选择年月").performClick()
        compose.onNodeWithText("2026年11月").assertIsDisplayed()
        compose.onNodeWithTag("home-date-scrim").performTouchInput { click(Offset(4f, 4f)) }
        assertEquals(today, shell.home.value.date)

        compose.onNodeWithText("10月12日").performClick()
        compose.onNodeWithContentDescription("2026-10-08").performClick()
        await { shell.home.value.date == today.minusDays(4) && !shell.home.value.isLoading }
        compose.onNodeWithText("周末去旧书店").assertDoesNotExist()
        compose.onNodeWithText("回到今天").performClick()
        await { shell.home.value.date == today && shell.home.value.futureIntents.isNotEmpty() }
        compose.onNodeWithText(planNote).assertIsDisplayed()

        val previousGeneration = shell.home.value.restoreGeneration
        await { RestoreStartupGate.beginRestore() }
        await { shell.home.value.restoreGeneration > previousGeneration && shell.home.value.isLoading }
        compose.onNodeWithText("周末去旧书店").assertDoesNotExist()
        runBlocking { LifeBackupApplier.restoreSnapshot(db, old) }
        RestoreStartupGate.endRestoreReady()
        await {
            val state = shell.home.value
            state.restoreGeneration > previousGeneration && state.loadingSources.isEmpty() &&
                state.eligibleMedia.singleOrNull()?.caption == note && state.futureIntents.isEmpty()
        }
        compose.onNodeWithText("周末去旧书店").assertDoesNotExist()
        assertTrue(runBlocking { plans.observeHomeVisible().first().isEmpty() })
    }

    @Test fun ninePhotoCollectionReturnsFromBToTheRealSelectedStackPosition() {
        val portrait = Uri.fromFile(imageFile(1)).toString()
        val landscape = Uri.fromFile(imageFile(2)).toString()
        val photos = (0 until 9).map { index ->
            LifeMediaUi("return-" + index, if (index % 2 == 0) portrait else landscape,
                title = "合成返回照片 " + index, aspectRatio = if (index % 2 == 0) .8f else 1.4f)
        }
        // The same photo can be granted to Today and also occur in Memory. Their
        // return sources must not overwrite one another's geometry.
        val memory = HistoryItemUi("duplicate-b", today.minusDays(1), "以前的同一张照片", photos[1])
        compose.setContent { HomeScene(HomeUiState(today, eligibleMedia = photos, historyItems = listOf(memory))) }
        photoButton(0).performClick()
        viewerAction("下一张照片")
        compose.onNodeWithTag("life-origin-paper").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "第 2 张，共 9 张")
        )
        val target = photoButton(1).fetchSemanticsNode().boundsInRoot.center
        val viewer = compose.onNodeWithTag("life-photo-shared-image")
        val startDistance = (viewer.fetchSemanticsNode().boundsInRoot.center - target).getDistance()
        capture("home-photo-return-viewer-b")

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(96)
        val distanceDuringReturn = (viewer.fetchSemanticsNode().boundsInRoot.center - target).getDistance()
        assertTrue("B moves toward its mounted stack card", distanceDuringReturn < startDistance - 3f)
        capture("home-photo-return-mid")
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("life-origin-paper").assertDoesNotExist()
        photoButton(1).assertIsDisplayed()
        capture("home-photo-return-stack-b")
    }

    @Test fun memoryPhotoOutsideTheViewportOrUnmountedNeverReturnsToItsOldRectangle() {
        val uri = Uri.fromFile(imageFile(0)).toString()
        val photos = (0 until 7).map { LifeMediaUi("memory-" + it, uri, title = "回忆照片 " + it) }
        val original = photos.mapIndexed { index, photo ->
            HistoryItemUi("source-" + index, today.minusDays(index + 1L), "回忆 " + index, photo)
        }
        var history by mutableStateOf(original.mapIndexed { index, item ->
            if (index == 0) item.copy(title = "这是一段让照片来源离开可见区域的回忆。".repeat(45)) else item
        })
        compose.setContent { HomeScene(HomeUiState(today, historyItems = history)) }

        memoryPhoto(1).performScrollTo().assertIsDisplayed()
        memoryPhoto(0).performScrollTo().assertIsDisplayed()
        memoryPhoto(1).assertExists().assertIsNotDisplayed()
        memoryPhoto(0).performClick()
        viewerAction("下一张照片")
        assertFadeClose("An attached, clipped source must not receive a return flight")

        compose.runOnIdle { history = original }
        memoryPhoto(1).performScrollTo().assertIsDisplayed()
        memoryPhoto(0).assertIsDisplayed()
        // The original B rectangle is on screen. Move B out of the six rendered
        // Memory rows while retaining it in the real viewer collection.
        compose.runOnIdle { history = listOf(original[0]) + original.drop(2) + original[1] }
        memoryPhoto(1).assertDoesNotExist()
        memoryPhoto(0).performClick()
        repeat(6) { viewerAction("下一张照片") }
        compose.onNodeWithTag("life-origin-paper").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "第 7 张，共 7 张")
        )
        assertFadeClose("A disposed source must not reuse its last on-screen rectangle")
        memoryPhoto(1).assertDoesNotExist()
    }

    @Test fun removingTheSingleCurrentPhotoClosesTheViewerAndClearsTheStack() {
        var photos by mutableStateOf(listOf(LifeMediaUi("return-0", Uri.fromFile(imageFile(0)).toString(), title = "合成返回照片 0")))
        var removed: String? = null
        compose.setContent {
            HomeScene(HomeUiState(today, eligibleMedia = photos), onRemove = { id ->
                removed = id
                photos = photos.filterNot { it.id == id }
            })
        }
        photoButton(0).performClick()
        val actions = compose.onNodeWithTag("life-origin-paper").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        assertFalse(actions.any { it.label == "下一张照片" })
        viewerAction("从首页移除照片")
        compose.onNodeWithText("移除").performClick()
        compose.runOnIdle { assertEquals("return-0", removed) }
        compose.onNodeWithTag("life-origin-paper").assertDoesNotExist()
        photoButton(0).assertDoesNotExist()
    }

    @Composable private fun HomeScene(
        state: HomeUiState,
        onToday: () -> Unit = {},
        onDate: (LocalDate) -> Unit = {},
        onRemove: (String) -> Unit = {}
    ) {
        LifeOsTheme {
            Surface(color = LocalLifeOsColors.current.paper) {
                LifeOsHomeScreen(state, {}, {}, {}, {}, {}, onToday = onToday, onDateSelected = onDate,
                    onRemovePhoto = onRemove, today = today)
            }
        }
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(10_000) {
        compose.runOnIdle {}
        condition()
    }

    private fun photoButton(index: Int) =
        compose.onNode(hasContentDescription("合成返回照片 " + index) and hasClickAction() and
            hasAnyAncestor(hasTestTag("home-today-stack")))

    private fun memoryPhoto(index: Int) = compose.onNodeWithTag("home-memory-photo-source-" + index)

    private fun assertFadeClose(message: String) {
        val image = compose.onNodeWithTag("life-photo-shared-image")
        val before = image.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(96)
        val during = image.fetchSemanticsNode().boundsInRoot
        // Only the existing 8dp lift may move a photo without a live source.
        // A flight toward the old Memory thumbnail changes its center and size.
        assertTrue(message, (during.center - before.center).getDistance() < 12f)
        assertEquals(message, before.width, during.width, .5f)
        assertEquals(message, before.height, during.height, .5f)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("life-origin-paper").assertDoesNotExist()
    }

    private fun viewerAction(label: String) {
        val action = compose.onNodeWithTag("life-origin-paper").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].single { it.label == label }
        compose.runOnIdle { assertTrue(action.action()) }
    }

    private fun capture(name: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("照片载入中").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("原件暂不可用").assertDoesNotExist()
        val frame = compose.runOnIdle { captureNativeAttachedWindows() }
        val bitmap = frame.bitmap
        try {
            val colors = mutableSetOf<Int>()
            for (x in 0 until bitmap.width step 5) for (y in 0 until bitmap.height step 5) colors += bitmap.getPixel(x, y)
            assertTrue("Capture must contain a rendered Home scene", colors.size > 8)
            val metrics = File(requireNotNull(System.getProperty("lifeos.v16.metricsDir")))
            val output = File(metrics.parentFile, "robolectric-visuals").apply { mkdirs() }
            File(output, "$name.windows.json").writeText(frame.windowAttributes)
            File(output, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun imageFile(index: Int): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(if (index == 2) 400 else 320, if (index == 2) 280 else 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(listOf(0xffcad7c8, 0xffb9becd, 0xffdbb8a3)[index].toInt())
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff5c576e.toInt(); textSize = 25f }
        canvas.drawText("SYNTHETIC " + index, 38f, bitmap.height / 2f, ink)
        return File(context.cacheDir, "home-acceptance-${UUID.randomUUID()}.png").also {file ->
            try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }

    private fun contentUri(file: File): Uri {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixtureAuthority = "synthetic-home-acceptance"
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "image/png"
            override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = fixtureAuthority; applicationInfo = context.applicationInfo })
        ShadowContentResolver.registerProviderInternal(fixtureAuthority, provider)
        return Uri.parse("content://$fixtureAuthority/photo.png")
    }
}
