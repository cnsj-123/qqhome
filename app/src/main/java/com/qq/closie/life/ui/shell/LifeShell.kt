package com.qq.closie.life.ui.shell

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
// Wildcard on purpose: `weight` is overloaded for RowScope / ColumnScope and the package also
// exposes an internal `weight` property, which an explicit single-symbol import would drag in and
// fail on ("Cannot access 'weight': it is internal"). The wildcard skips internal declarations.
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.navArgument
import com.qq.closie.ExternalNavCommand
import com.qq.closie.data.ProductLinkExtractor
import com.qq.closie.data.repository.WardrobeRepository
import com.qq.closie.life.capture.CaptureSource
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.ui.modules.ModuleLandingCatalog
import com.qq.closie.life.ui.modules.ModuleLandingScreen
import com.qq.closie.life.ui.modules.ModuleLandingSpec
import com.qq.closie.life.ui.plan.PlanEditScreen
import com.qq.closie.life.ui.plan.PlanScreen
import com.qq.closie.life.ui.plan.PlanViewModel
import com.qq.closie.life.ui.reading.ReadingScreen
import com.qq.closie.life.reference.ReferenceImportResult
import com.qq.closie.life.ui.reference.ReferenceDetailScreen
import com.qq.closie.life.ui.reference.ReferenceEditScreen
import com.qq.closie.life.ui.reference.ReferenceLibraryScreen
import com.qq.closie.life.ui.reference.ReferenceViewModel
import com.qq.closie.life.ui.theme.LifeColors
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.life.ui.theme.LifeTheme
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.navigation.ClosieHostMode
import com.qq.closie.navigation.ClosieNavHost
import com.qq.closie.navigation.Route
import com.qq.closie.navigation.TopLevel
import com.qq.closie.ui.quickcapture.QuickCaptureActivity
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

sealed class LifeDestination(val route: String, val label: String, val icon: ImageVector) {
    data object Home : LifeDestination("life_home", "首页", Icons.Outlined.Home)
    data object Timeline : LifeDestination("life_timeline", "记录", Icons.Outlined.CalendarMonth)
    data object Modules : LifeDestination("life_modules", "生活", Icons.Outlined.GridView)
    data object Me : LifeDestination("life_me", "我的", Icons.Outlined.PersonOutline)

    /** Not a tab: hosts the pre-existing Closie closet, which owns its own bottom bar. */
    data object Closet : LifeDestination("life_closet", "衣橱", Icons.Outlined.Home)

    /** Not a tab: opens Closie straight on its 设置 screen (备份与恢复 lives there). */
    data object ClosetSettings : LifeDestination("life_closet_settings", "设置", Icons.Outlined.Home)

    // ---- v0.3.0 modules: none of these are tabs, all are pushed from 生活 ----------

    /** 资料库 — the reference library. */
    data object Reference : LifeDestination("life_reference", "资料库", Icons.Outlined.GridView)

    /** 资料库 item detail. */
    data object ReferenceDetail :
        LifeDestination("life_reference/{id}", "资料内容", Icons.Outlined.GridView)

    /** 资料库 item editor. `{id}` is [NEW_ID] for a brand-new reference. */
    data object ReferenceEdit :
        LifeDestination("life_reference_edit/{id}", "编辑资料", Icons.Outlined.GridView)

    /** 计划. */
    data object Plan : LifeDestination("life_plan", "计划", Icons.Outlined.CalendarMonth)

    /** 计划 item editor. `{id}` is [NEW_ID] for a brand-new plan. */
    data object PlanEdit : LifeDestination("life_plan_edit/{id}", "编辑计划", Icons.Outlined.CalendarMonth)

    /** 阅读 — a view over 资料库, not a second schema. */
    data object Reading : LifeDestination("life_reading", "阅读", Icons.Outlined.GridView)

    /**
     * The unified landing page for a module that is planned but not built.
     *
     * One route for all four (财务/物品/旅行/园艺) rather than four near-identical routes: the page
     * is the same component and only the copy differs, so a single route keyed by
     * [selectedModule] keeps the graph — and the back stack — from growing one entry per module
     * that will eventually be deleted anyway.
     */
    data object ModuleLanding :
        LifeDestination("life_module/{key}", "模块", Icons.Outlined.GridView)

    /** A 记录 (capture) opened for viewing / editing. */
    data object CaptureDetail :
        LifeDestination("life_capture/{id}", "记录详情", Icons.Outlined.CalendarMonth)

    companion object {
        /** Sentinel `{id}` meaning "create new" rather than "edit existing". */
        const val NEW_ID = "new"

        /**
         * Which module's landing page to render.
         *
         * A plain field on the companion rather than a nav argument, because
         * [ModuleLanding.route] is a single destination and Navigation-Compose would need the value
         * threaded through an argument bundle for no benefit — the landing page is stateless and
         * reads its copy synchronously. It is set immediately before navigating and read
         * immediately after, on the same thread, so it cannot go stale.
         */
        var selectedModule: String = LifeModuleCatalog.KEY_MONEY

        fun referenceDetail(id: String) = "life_reference/$id"
        fun referenceEdit(id: String) = "life_reference_edit/$id"
        fun planEdit(id: String) = "life_plan_edit/$id"
        fun captureDetail(id: String) = "life_capture/$id"
        fun moduleLanding(key: String) = "life_module/$key"

        // Same circular-init trap as TopLevel.entries (see ClosieNavigation.kt): a direct
        // listOf(Home, …) inside the companion's <clinit> captures a null INSTANCE whenever the
        // first static touch of the family is one of the data objects themselves. lazy defers
        // the read until all child <clinit>s have unwound — tabs can then never hold a null.
        val tabs: List<LifeDestination> by lazy { listOf(Home, Timeline, Modules, Me) }
        val tabRoutes: List<String> by lazy { tabs.map { it.route } }

        /**
         * Routes whose destination is NOT a tab. Kept as a list so `showBottomBar` can stay a
         * single membership test instead of an ever-growing chain of `!=` comparisons.
         *
         * The bottom bar rule is unchanged from v0.2 and still the simplest honest one: the bar is
         * shown on a tab, hidden everywhere else. Module screens are pushed from 生活 and carry
         * their own `‹ 返回` in [LifeTopAppBar], so they need no tab bar — and two stacked
         * navigation bars is exactly the bug §7 is about.
         */
        val nonTabRoutes: List<String> by lazy {
            listOf(
                Closet.route, ClosetSettings.route,
                Reference.route, ReferenceDetail.route, ReferenceEdit.route,
                Plan.route, PlanEdit.route, Reading.route,
                ModuleLanding.route, CaptureDetail.route
            )
        }
    }
}

/**
 * The Life OS shell.
 *
 * Bottom navigation is 首页 / 记录 / ＋ / 生活 / 我的. The ＋ item is not a tab: it never becomes
 * selected and its only job is to open [CaptureBottomSheet]. It is a plain icon at the same size
 * as every other item — no oversized floating ball, no Dynamic Island imitation.
 *
 * 衣橱 and 设置 are *destinations without a tab*. When either is on screen the Life OS bottom bar
 * is hidden and the existing [ClosieNavHost] renders instead, keeping the app's original theme
 * ([LifeTheme] is applied per Life OS page, never around ClosieNavHost). That is what guarantees
 * exactly one bottom bar on screen at a time and no restyling of the shipped closet.
 */
@Composable
fun LifeShellNavHost(
    wardrobeRepository: WardrobeRepository,
    lifeContainer: LifeContainer,
    externalCommand: ExternalNavCommand? = null,
    onExternalCommandConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in LifeDestination.tabRoutes

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val captureRepository = lifeContainer.captureRepository
    var showCaptureSheet by remember { mutableStateOf(false) }

    // ---- 从相册 (§10) --------------------------------------------------------------
    //
    // Uses the *system* Photo Picker (`PickVisualMedia`) rather than `OpenDocument` or a
    // `READ_MEDIA_IMAGES` permission. Two reasons, both of which matter on a real device:
    //
    //  1. **No permission.** The picker runs in a separate process and hands back a single
    //     user-chosen URI. Life OS never needs to ask for access to the whole gallery, so it
    //     doesn't — which also means no permission dialog to explain away.
    //  2. **No fleet of stale URIs.** The URI the picker returns is a *grant*, not a durable
    //     location: it can be revoked, and the file behind it can be moved or deleted by the user
    //     at any time. So the bytes are copied into Life OS's own media directory immediately
    //     (see MediaStoreImporter) and only the *managed* path is ever stored. Keeping the picker
    //     URI itself would produce records that render as broken images a week later.
    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // ONE call does the whole job: managed copy → 记录 row → OCR → INBOX 资料库 entry →
            // media link, all sharing a single MediaAsset. Previously this block imported the image,
            // wrote a capture row and stopped, so 从相册 produced a record with no 资料库 entry, no
            // OCR and nothing to tap through to.
            val result = lifeContainer.referenceImporter.importGalleryImage(uri)
            if (result != null) {
                // Land on the *editor*, not the detail page. Everything on this reference so far is
                // an automatic guess: the title came from OCR text, the type is the importer's
                // default, the summary is a snippet. Showing the finished detail page would present
                // those guesses as settled facts the user has to notice and then go fix. The editor
                // is where "here is what I read from your screenshot, correct me" belongs — and it
                // is the same screen the link flow ends on, so both entry points behave alike.
                nav.navigate(LifeDestination.referenceEdit(result.referenceId))
            }
            // A null result means the bytes could not be read (revoked grant, missing file). Left
            // silent rather than toasted: the user chose a photo and nothing appeared, which is the
            // honest outcome, and there is no different retry to offer.
        }
    }

    // One ViewModel per module, obtained through the ViewModelStore rather than `remember`.
    //
    // Hoisted to the shell because 资料库 and 阅读 are two views over the SAME data: a reference
    // archived in 资料库 must already be gone when the user switches to 阅读 without the screen being
    // recreated. Two instances would each hold their own `stateIn` cache and could briefly disagree.
    //
    // These used to be `remember { ReferenceViewModel(...) }`. That looked equivalent and was not:
    // `remember` bypasses `ViewModelStore`, so `onCleared()` never fires and the `viewModelScope`
    // behind every `stateIn` keeps collecting across configuration changes — a slow leak that also
    // silently breaks the ViewModel contract these classes rely on. The explicit `key` makes
    // 资料库 and 阅读 resolve to one shared instance while 计划 gets its own, and going through the
    // store means a rotation reuses the instance instead of rebuilding it.
    val referenceViewModel: ReferenceViewModel = viewModel(
        key = "life_reference",
        factory = ReferenceViewModelFactory(lifeContainer)
    )
    val planViewModel: PlanViewModel = viewModel(
        key = "life_plan",
        factory = PlanViewModelFactory(lifeContainer)
    )

    // A share / deep-link intent is dispatched by destination.
    //
    // Only ProductImport / Edit / Add belong to Closie — its editor is the destination for all
    // three, so the shell navigates there and ClosieNavHost finishes the job. ReferenceLink and
    // CaptureText are Life OS's own business and are handled *here*, in the shell, which is the
    // component that owns the Life OS navigation graph. Sending them to Closie (as the previous
    // single-command design did) is what made "share a web page" open the wardrobe.
    LaunchedEffect(externalCommand) {
        when (val command = externalCommand) {
            null -> Unit

            is ExternalNavCommand.ProductImport,
            is ExternalNavCommand.Edit,
            is ExternalNavCommand.Add -> {
                nav.navigate(LifeDestination.Closet.route) { launchSingleTop = true }
            }

            is ExternalNavCommand.ReferenceLink -> {
                // Capture the share, then file it: metadata fetch → INBOX 资料库 entry → editor, so
                // the user lands on a screen where the auto-filled title/summary/source can be
                // corrected before it becomes permanent.
                //
                // The capture row is the durable half: if metadata fetching fails the URL is still
                // saved, and the user still gets the editor with the domain as the title. Nothing
                // about a failed fetch loses the link.
                val captureId = UUID.randomUUID().toString()
                val created = captureRepository.create(
                    id = captureId,
                    source = CaptureSource.SHARE,
                    rawText = command.originalText,
                    sourceUrl = command.url
                )
                val result = if (created) lifeContainer.referenceImporter.importCapture(captureId) else null
                val referenceId = result?.reference?.id
                if (referenceId != null) {
                    nav.navigate(LifeDestination.referenceEdit(referenceId))
                } else {
                    // Metadata and filing both failed — fall back to the record itself rather than
                    // dropping the share on the floor.
                    nav.navigate(LifeDestination.captureDetail(captureId))
                }
                onExternalCommandConsumed()
            }

            is ExternalNavCommand.CaptureText -> {
                val captureId = UUID.randomUUID().toString()
                captureRepository.create(
                    id = captureId,
                    source = CaptureSource.SHARE,
                    rawText = command.text,
                    sourceUrl = null
                )
                nav.navigate(LifeDestination.captureDetail(captureId))
                onExternalCommandConsumed()
            }
        }
    }

    Scaffold(
        containerColor = LifeColors.Paper,
        bottomBar = {
            if (showBottomBar) {
                LifeBottomNavigation(
                    currentRoute = currentRoute,
                    onSelectTab = { nav.navigateLifeTab(it) },
                    onCapture = { showCaptureSheet = true }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // The Scaffold is the ONE owner of the system-bar insets. Applying its
                // padding here and then consuming it means nested content (Life OS pages,
                // and Closie's own M3 Scaffold / TopAppBar when the closet is open) sees a
                // zero status-bar inset instead of adding a second, third layer of top
                // padding — the v0.1 "status bar → first line ≈ 60dp" bug.
                .consumeWindowInsets(padding)
        ) {
            NavHost(
                navController = nav,
                startDestination = LifeDestination.Home.route,
                modifier = modifier
            ) {
                composable(LifeDestination.Home.route) {
                    LifeTheme {
                        LifeHomeScreen(
                            captureRepository = captureRepository,
                            planViewModel = planViewModel,
                            onQuickCapture = { showCaptureSheet = true },
                            onOpenRecord = { id -> nav.navigate(LifeDestination.captureDetail(id)) },
                            onOpenPlan = { nav.openModule(LifeModuleCatalog.KEY_PLAN) }
                        )
                    }
                }

                composable(LifeDestination.Timeline.route) {
                    LifeTheme {
                        TimelineScreen(
                            captureRepository = captureRepository,
                            onOpenRecord = { id -> nav.navigate(LifeDestination.captureDetail(id)) }
                        )
                    }
                }

                composable(LifeDestination.Modules.route) {
                    LifeTheme {
                        LifeModulesScreen(
                            onOpenModule = { key -> nav.openModule(key) }
                        )
                    }
                }

                composable(LifeDestination.Me.route) {
                    LifeTheme {
                        ProfileScreen(
                            onOpenSettings = { nav.navigate(LifeDestination.ClosetSettings.route) },
                            onOpenBackup = { nav.navigate(LifeDestination.ClosetSettings.route) }
                        )
                    }
                }

                // Closie keeps the app theme — no LifeTheme wrapper here.
                //
                // ClosieHostMode.EmbeddedInLifeOs is the whole point of §7: the closet must know it
                // was entered from Life OS, because that is the only case where it owes the user a
                // visible "‹ Life OS" control and where its back press has to return to the shell
                // instead of finishing the Activity. Passing the mode (rather than letting Closie
                // guess from the route) means the answer is decided here, once, by the component
                // that actually knows how it was launched.
                composable(LifeDestination.Closet.route) {
                    ClosieNavHost(
                        repository = wardrobeRepository,
                        externalCommand = externalCommand,
                        onExternalCommandConsumed = onExternalCommandConsumed,
                        startDestination = TopLevel.Closet.route,
                        onExit = { nav.navigateLifeTab(LifeDestination.Modules) },
                        mode = ClosieHostMode.EmbeddedInLifeOs,
                        lifeDatabase = lifeContainer.lifeDatabase
                    )
                }

                composable(LifeDestination.ClosetSettings.route) {
                    ClosieNavHost(
                        repository = wardrobeRepository,
                        startDestination = Route.Settings.route,
                        onExit = { nav.navigateLifeTab(LifeDestination.Me) },
                        mode = ClosieHostMode.EmbeddedInLifeOs,
                        lifeDatabase = lifeContainer.lifeDatabase
                    )
                }

                // ---- v0.3.0 modules ------------------------------------------------
                //
                // All four are pushed from 生活 and are NOT tabs, so the Life OS bottom bar is
                // hidden on each of them by the `showBottomBar` test above. Every one of them
                // carries `‹ 返回` in its own LifeTopAppBar, which is the single back affordance
                // rule from §31–33; there is no second control and no second bottom bar.

                composable(LifeDestination.Reference.route) {
                    LifeTheme {
                        ReferenceLibraryScreen(
                            viewModel = referenceViewModel,
                            onOpenItem = { id -> nav.navigate(LifeDestination.referenceDetail(id)) },
                            onBack = { nav.backToModules() },
                            onAddItem = { showCaptureSheet = true }
                        )
                    }
                }

                composable(
                    LifeDestination.ReferenceDetail.route,
                    arguments = listOf(navArgument("id") { type = NavType.StringType })
                ) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    LifeTheme {
                        ReferenceDetailScreen(
                            viewModel = referenceViewModel,
                            referenceId = id,
                            onBack = { nav.popBackStack() },
                            onEdit = { nav.navigate(LifeDestination.referenceEdit(it)) }
                        )
                    }
                }

                composable(
                    LifeDestination.ReferenceEdit.route,
                    arguments = listOf(navArgument("id") { type = NavType.StringType })
                ) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    LifeTheme {
                        ReferenceEditScreen(
                            viewModel = referenceViewModel,
                            referenceId = id,
                            onDone = { nav.popBackStack() },
                            onBack = { nav.popBackStack() }
                        )
                    }
                }

                composable(LifeDestination.Plan.route) {
                    LifeTheme {
                        PlanScreen(
                            viewModel = planViewModel,
                            onAddPlan = { nav.navigate(LifeDestination.planEdit(LifeDestination.NEW_ID)) },
                            onEditPlan = { nav.navigate(LifeDestination.planEdit(it.id)) },
                            onBack = { nav.backToModules() }
                        )
                    }
                }

                composable(
                    LifeDestination.PlanEdit.route,
                    arguments = listOf(navArgument("id") { type = NavType.StringType })
                ) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    val isNew = id == LifeDestination.NEW_ID
                    // The plan being edited is read once, from the id in the route. `null` means
                    // "create" — PlanEditScreen is written to take exactly that, so there is no
                    // separate create-vs-edit screen and no chance of the two drifting apart.
                    val planFlow = remember(id) {
                        if (isNew) flowOf(null) else planViewModel.observeItem(id)
                    }
                    val plan by planFlow.collectAsStateWithLifecycle(initialValue = null)
                    LifeTheme {
                        PlanEditScreen(
                            plan = plan,
                            onSave = { title, note, dueAt, clearDue ->
                                scope.launch {
                                    if (plan == null) {
                                        planViewModel.create(title, note, dueAt)
                                    } else {
                                        planViewModel.update(plan!!, title, note, dueAt, clearDue)
                                    }
                                    nav.popBackStack()
                                }
                            },
                            onBack = { nav.popBackStack() }
                        )
                    }
                }

                composable(LifeDestination.Reading.route) {
                    LifeTheme {
                        ReadingScreen(
                            referenceViewModel = referenceViewModel,
                            onOpenItem = { id -> nav.navigate(LifeDestination.referenceDetail(id)) },
                            onAddReading = { showCaptureSheet = true },
                            onBack = { nav.backToModules() }
                        )
                    }
                }

                composable(
                    LifeDestination.ModuleLanding.route,
                    arguments = listOf(navArgument("key") { type = NavType.StringType })
                ) { entry ->
                    val key = entry.arguments?.getString("key")
                        ?: LifeModuleCatalog.KEY_MONEY
                    // `specFor` is nullable; fall back to the module's own title so an unknown key
                    // renders a real page ("当前功能尚在建设") rather than crashing or showing a
                    // blank screen. The key only ever comes from LifeModuleCatalog, so this is
                    // defensive rather than expected.
                    val spec = ModuleLandingCatalog.specFor(key) ?: ModuleLandingSpec(
                        key = key,
                        title = LifeModuleCatalog.byKey(key)?.title ?: "模块",
                        tagline = "这个模块还在规划中。",
                        body = "先把想到的内容记到资料库，等它准备好，你会在这里看到它。"
                    )
                    LifeTheme {
                        ModuleLandingScreen(
                            spec = spec,
                            onBack = { nav.backToModules() },
                            onSaveToLibrary = { showCaptureSheet = true }
                        )
                    }
                }

                composable(
                    LifeDestination.CaptureDetail.route,
                    arguments = listOf(navArgument("id") { type = NavType.StringType })
                ) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    LifeTheme {
                        CaptureDetailScreen(
                            captureRepository = captureRepository,
                            captureId = id,
                            onBack = { nav.popBackStack() },
                            onDeleted = { nav.popBackStack() },
                            mediaRepository = lifeContainer.mediaRepository,
                            onFileToLibrary = { captureId ->
                                scope.launch {
                                    // Best-effort by design: a photo capture whose bytes are gone,
                                    // or a record with no text at all, simply produces nothing.
                                    // The one rule that is never violated is §20 — the capture
                                    // itself is left exactly as it was, media included.
                                    val result = lifeContainer.referenceImporter
                                        .importCapture(captureId)
                                    if (result != null) {
                                        nav.navigate(
                                            LifeDestination.referenceDetail(result.reference.id)
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showCaptureSheet) {
        LifeTheme {
            CaptureBottomSheet(
                onDismiss = { showCaptureSheet = false },
                    onAction = { action ->
                        showCaptureSheet = false
                        when (action) {
                            // 从相册 must launch an activity-result contract, which is only legal
                            // from a composable-scoped launcher — so it is handled here rather than
                            // inside the suspend performCaptureAction below.
                            CaptureAction.FromGallery -> galleryPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )

                            // 手动记录 opens the editor on a *new* record — but the row is NOT
                            // created here.
                            //
                            // v0.2 inserted an empty capture immediately and then opened it, so the
                            // mere act of tapping 手动记录 wrote a "（空记录）" into 记录 that the user
                            // had to back out of and delete. Cancelling the editor left the empty row
                            // behind — the app creating noise on the user's behalf. Opening the
                            // editor on the [LifeDestination.NEW_ID] sentinel instead means the
                            // capture is created by CaptureDetailScreen the first time the user
                            // actually saves, and an abandoned edit creates nothing at all. See
                            // CaptureDetailScreen's `isNew` handling.
                            CaptureAction.ManualRecord -> nav.navigate(
                                LifeDestination.captureDetail(LifeDestination.NEW_ID)
                            )

                            else -> scope.launch {
                                // The route to navigate to after the action completes, or null when
                                // the action itself navigates (gallery picker / quick capture).
                                val destination = performCaptureAction(
                                    context = context,
                                    action = action,
                                    create = { source, text, url ->
                                        val id = UUID.randomUUID().toString()
                                        val ok = captureRepository.create(
                                            id = id,
                                            source = source,
                                            rawText = text,
                                            sourceUrl = url
                                        )
                                        if (ok) id else null
                                    },
                                    importCapture = { captureId ->
                                        lifeContainer.referenceImporter.importCapture(captureId)
                                    }
                                )
                                if (destination != null) nav.navigate(destination)
                            }
                        }
                    }
            )
        }
    }
}

/**
 * Runs a capture action and returns the route to navigate to, or null.
 *
 * Returning a route rather than navigating internally is what lets the link flow end *somewhere
 * useful*. The previous version created the capture and returned, so ＋ → 链接 produced a bare
 * record in the timeline and nothing else: no metadata fetch, no 资料库 entry, no editor. The user
 * had to find the record again and manually file it — for a flow whose entire premise is "the app
 * noticed a link and did something with it".
 *
 * `create` returns the new capture's id (or null if the write failed) rather than a Boolean, so the
 * link branch can hand that exact id to the importer. Deriving the id any other way — re-reading the
 * newest capture, say — would be a race against any concurrent capture.
 */
private suspend fun performCaptureAction(
    context: Context,
    action: CaptureAction,
    create: suspend (CaptureSource, String?, String?) -> String?,
    importCapture: suspend (String) -> ReferenceImportResult?
): String? {
    when (action) {
        CaptureAction.QuickCapture -> {
            context.startActivity(Intent(context, QuickCaptureActivity::class.java))
            return null
        }

        CaptureAction.FromGallery -> return null // handled by the shell's own picker launcher

        CaptureAction.PasteText -> {
            val text = readClipboardText(context) ?: return null
            create(CaptureSource.CLIPBOARD, text, null)
            return null
        }

        CaptureAction.Link -> {
            // The clipboard rarely holds a bare URL — a shared 淘宝 link is
            // `【淘宝】… 复制此消息，打开淘宝 https://m.tb.cn/xxxx`, and an app share often appends
            // its own banner. The old `looksLikeUrl` tested `startsWith("http")`, which is false for
            // every one of those, so `sourceUrl` was left null: the record rendered as plain text
            // and the link was never fetched or parsed.
            //
            // Now the URL is pulled out of the surrounding text by the same extractor the product
            // importer and ShareRouter use, so the link is found wherever it sits.
            val text = readClipboardText(context) ?: return null
            val url = ProductLinkExtractor.extractFirstHttpUrl(text)
                ?: return handleLinkWithoutUrl(context)

            val captureId = create(CaptureSource.SHARE, text, url) ?: return null

            // File it immediately: metadata fetch → INBOX 资料库 entry → editor. Doing the work here
            // rather than making the user tap 存进资料库 afterwards is the difference between
            // "＋ → 链接 saved my link" and "＋ → 链接 made a note I still have to process".
            //
            // No metadataFetched branch: the editor is correct either way. When the page could be
            // read the fields are pre-filled and worth confirming; when it could not, the title is
            // the domain and the URL is right there for the user to type a real name next to. The
            // URL is saved in both cases, so nothing is lost to a failed fetch.
            val result = importCapture(captureId)
            return result?.reference?.id?.let { LifeDestination.referenceEdit(it) }
        }

        // Manual record used to `create(MANUAL, null, null)` — i.e. it inserted an empty row and
        // said nothing. The result was a timeline slowly filling with "（空记录）" entries the user
        // never asked for and could only delete one by one: noise the app created on the user's
        // behalf. §9's fix is that this row now opens the editor instead, so a record only exists
        // once the user has actually written something. The shell routes it; see the call site.
        CaptureAction.ManualRecord -> return null
    }
}

/**
 * What to do when ＋ → 链接 found no URL in the clipboard.
 *
 * Two wrong answers were available and both had been used at various points: silently creating a
 * "链接" record with no link (a record that is not what it claims to be), or doing nothing at all (a
 * tap that appears broken). Neither is honest.
 *
 * The chosen answer is to do nothing *and say so*. The clipboard text is deliberately not turned
 * into a record: the user asked for a link, and quietly filing their clipboard as a note is the app
 * deciding for them. A toast explains that no link was recognised, so the tap has a visible cause —
 * and 手动记录 is one tap away in the same sheet if a note is what they actually wanted.
 */
private fun handleLinkWithoutUrl(context: Context): String? {
    Toast.makeText(context, "剪贴板里没有识别到链接", Toast.LENGTH_SHORT).show()
    return null
}

private fun readClipboardText(context: Context): String? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return null
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
}

/**
 * The bottom bar. Custom rather than Material's NavigationBar: no indicator pill, no tonal
 * elevation, no scrim — selected state is carried by ink weight alone, which keeps the bar from
 * reading as a Material dashboard. Height is a plain 60dp minimum with navigation-bar padding for
 * edge-to-edge / gesture nav.
 */
@Composable
private fun LifeBottomNavigation(
    currentRoute: String?,
    onSelectTab: (LifeDestination) -> Unit,
    onCapture: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = LifeColors.SurfaceRaised,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .heightIn(min = 60.dp)
                .padding(horizontal = LifeSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LifeDestination.tabs.take(2).forEach { tab ->
                LifeTabItem(
                    selected = tab.route == currentRoute,
                    label = tab.label,
                    icon = tab.icon,
                    onClick = { onSelectTab(tab) }
                )
            }

            // ＋ — a capture affordance, sized like every other item. Never becomes "selected".
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = LifeSpacing.minTouchTarget)
                    .clickable(onClick = onCapture)
                    .padding(vertical = LifeSpacing.xxs),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "添加记录",
                    tint = LifeColors.Accent,
                    modifier = Modifier.size(LifeSpacing.iconSize)
                )
                Spacer(Modifier.height(2.dp))
                // Reserve exactly the Navigation label line box (16sp) so the ＋ glyph sits on
                // the same baseline as the labelled tabs. An empty Text would work too, but it
                // leaves a pointless node in the tree.
                Spacer(Modifier.height(16.dp))
            }

            LifeDestination.tabs.drop(2).forEach { tab ->
                LifeTabItem(
                    selected = tab.route == currentRoute,
                    label = tab.label,
                    icon = tab.icon,
                    onClick = { onSelectTab(tab) }
                )
            }
        }
    }
}

// RowScope receiver, not a plain function: the tab has to call Modifier.weight() to share the bar
// evenly with the ＋ item, and weight() only exists on a Row/Column scope.
@Composable
private fun RowScope.LifeTabItem(
    selected: Boolean,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val tint = if (selected) LifeColors.TextPrimary else LifeColors.TextTertiary
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = LifeSpacing.minTouchTarget)
            .clickable(onClick = onClick)
            .padding(vertical = LifeSpacing.xxs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(LifeSpacing.iconSize)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = LifeType.Navigation,
            color = tint
        )
    }
}

private fun NavHostController.navigateLifeTab(tab: LifeDestination) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Opens a module from 生活.
 *
 * The catalog key → route mapping lives here rather than in [LifeModulesScreen] so the screen stays
 * a pure renderer of [LifeModuleCatalog] and knows nothing about navigation. [LifeModuleCatalog]
 * carries no Compose or Navigation imports precisely so the module list can be unit-tested on the
 * JVM, and leaking a route string into it would break that.
 *
 * Four built modules get their own destination; every planned module funnels into the single
 * [LifeDestination.ModuleLanding] route with its key passed as an argument.
 */
private fun NavHostController.openModule(key: String) {
    val route = when (key) {
        LifeModuleCatalog.KEY_CLOSET -> LifeDestination.Closet.route
        LifeModuleCatalog.KEY_REFERENCE -> LifeDestination.Reference.route
        LifeModuleCatalog.KEY_PLAN -> LifeDestination.Plan.route
        LifeModuleCatalog.KEY_READING -> LifeDestination.Reading.route
        else -> LifeDestination.moduleLanding(key)
    }
    navigate(route) { launchSingleTop = true }
}

/**
 * Returns to 生活.
 *
 * `popBackStack(Modules.route, inclusive = false)` is preferred over a plain `popBackStack()`
 * because it is idempotent: whether the user arrived from 生活 → 资料库 (one pop) or
 * 生活 → 资料库 → 详情 → 编辑 (three pops), the same call lands on 生活 in one step and can never
 * leave the user on a half-popped stack. The boolean result tells us whether 生活 was actually on
 * the stack — if it was not (e.g. a deep link landed straight on 资料库), we navigate to it instead
 * rather than silently doing nothing.
 */
private fun NavHostController.backToModules() {
    if (!popBackStack(LifeDestination.Modules.route, inclusive = false)) {
        navigate(LifeDestination.Modules.route) { launchSingleTop = true }
    }
}
