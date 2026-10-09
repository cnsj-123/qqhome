package com.qq.closie.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.qq.closie.navigation.ExternalNavCommand
import com.qq.closie.data.PendingImport
import com.qq.closie.data.model.ItemStatus
import com.qq.closie.data.repository.WardrobeRepository
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.ui.closet.ClosetScreen
import com.qq.closie.ui.detail.DetailScreen
import com.qq.closie.ui.editor.EditorScreen
import com.qq.closie.ui.home.HomeScreen
import com.qq.closie.ui.ootd.OotdScreen
import com.qq.closie.ui.outfit.OutfitListScreen
import com.qq.closie.ui.outfit.OutfitStudioScreen
import com.qq.closie.ui.settings.DataSettingsScreen
import com.qq.closie.ui.theme.ClosieColor
import com.qq.closie.ui.LocalWardrobeSnapshot

sealed class TopLevel(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Home : TopLevel("home", "首页", Icons.Outlined.Home)
    data object Closet : TopLevel("closet", "衣橱", Icons.Outlined.Checkroom)
    data object Ootd : TopLevel("ootd", "OOTD", Icons.Outlined.CalendarMonth)
    data object Outfits : TopLevel("outfits", "搭配", Icons.Outlined.AutoAwesome)

    companion object {
        // by lazy is NOT cosmetic. `val entries = listOf(Home, Closet, …)` runs inside
        // Companion's <clinit>, and that clinit can be entered *from a child object's own
        // clinit* — e.g. the first static touch of the process is TopLevel.Closet.INSTANCE
        // (LifeOsRoot passes TopLevel.Closet.route as ClosieNavHost's startDestination):
        //
        //   Closet.<clinit> → super TopLevel.<clinit> → Companion.<clinit>
        //     → listOf(... Closet.INSTANCE ...)  ← Closet's <clinit> is still on the stack,
        //                                           so the recursive read yields NULL
        //
        // The list then permanently holds a null, and the next `entries.any { it.route == … }`
        // dies with "Cannot invoke TopLevel.getRoute() because \"it\" is null" — the v0.1
        // real-device crash on 生活 → 衣橱 that static reading could not find and CI could not
        // see. The JVM allows a same-thread recursive class-init to return the not-yet-assigned
        // static; `by lazy` defers reading the instances until every <clinit> has unwound, so
        // the list is always complete. Regression-tested in TopLevelEntriesTest, which touches
        // Closet first on purpose to pin this exact order.
        val entries: List<TopLevel> by lazy { listOf(Home, Closet, Ootd, Outfits) }
    }
}

sealed class Route(val route: String) {
    data object Detail : Route("detail/{id}")
    data object Edit : Route("edit/{id}")
    data object Add : Route("add/{status}")
    data object Settings : Route("settings")
    data object OutfitEdit : Route("outfit_edit/{id}")
    data object OutfitDraftEdit : Route("outfit_draft_edit/{draftId}")
}

/**
 * Legacy Closet module graph inside Life OS. Local Closet/OOTD/outfit pages retain their
 * repository and navigation; this graph is never a standalone product root.
 * [onExit] returns to the owning Life OS graph once the module stack is exhausted.
 */
@Composable
fun ClosieNavHost(
    repository: WardrobeRepository,
    externalCommand: ExternalNavCommand? = null,
    onExternalCommandConsumed: () -> Unit = {},
    startDestination: String = TopLevel.Closet.route,
    onExit: () -> Unit,
    onExitLabel: String = "Life OS",
    /**
     * The Life OS database, threaded down to 设置 so 备份与恢复 can include the Life OS half.
     *
     * **Required, not nullable.** A v0.3 backup is only complete if it carries the Life OS section, so
     * every caller has to hand over a database. An earlier revision defaulted this to `null`, which let
     * a caller silently produce a `v2` archive with `includesLifeOs=false` — a wardrobe-only file that
     * looks like a complete backup and quietly drops every Life OS row on restore. A required parameter
     * turns that into a compile error instead of a data-loss surprise.
     */
    lifeDatabase: LifeDatabase
) {
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val currentRoute = current?.destination?.route
    val showBottomBar = TopLevel.entries.any { it.route == currentRoute }

    // At module roots, always offer an explicit return to the single Life OS shell.
    val showReturnAffordance = TopLevel.entries.any { it.route == currentRoute }

    /**
     * The single back rule for everything inside Closie.
     *
     * Backing out of a detail / editor / OOTD / outfit screen pops one Closie page. Backing out of
     * the screen Closie was *opened on* — 衣橱 or 设置, whichever was passed as [startDestination]
     * — has nothing left to pop, and that is the only point at which [onExit] fires and the Life OS
     * shell takes over again.
     *
     * Owning this in one place (rather than per screen) is what makes the chain work:
     *
     *   Life OS → 衣橱 → OOTD → back → 衣橱 → back → Life OS Home
     *   Life OS → 设置 → back → Home
     *
     * and it is also what stops a back press at the Closie root from finishing the Activity — the
     * default behaviour when the NavHost has nothing to pop.
     */
    val backOrExit: () -> Unit = { if (!nav.popBackStack()) onExit() }

    // Intercept the *system* back key / gesture. Nested (per-screen) back handling, if any, still
    // runs first, so this only fires once Closie's own stack is exhausted.
    BackHandler(onBack = backOrExit)

    // Handle "分享至 Closie" and quick-capture "保存并继续编辑" deep links. The command is a
    // one-shot: once navigated it is consumed and cleared by the host activity.
    //
    // Only the three Closie-owned commands appear here. `ReferenceLink` and `CaptureText` are
    // consumed by LifeOsRoot instead — they belong to 资料库 / 记录, not the wardrobe, and this
    // NavHost is never reached for them. That split is the point: previously every share arrived
    // here as `Import`, so a shared article opened the "add a garment" form.
    LaunchedEffect(externalCommand) {
        when (val cmd = externalCommand) {
            null -> Unit
            is ExternalNavCommand.ProductImport -> {
                PendingImport.text = cmd.text
                nav.navigate(Route.Add.route.replace("{status}", ItemStatus.OWNED.name))
                onExternalCommandConsumed()
            }
            is ExternalNavCommand.Edit -> {
                nav.navigate(Route.Edit.route.replace("{id}", cmd.itemId))
                onExternalCommandConsumed()
            }
            is ExternalNavCommand.Add -> {
                nav.navigate(Route.Add.route.replace("{status}", ItemStatus.OWNED.name))
                onExternalCommandConsumed()
            }
            // Not this NavHost's business — LifeOsRoot consumes it. Consuming here as well would
            // clear the command before the shell ever saw it.
            is ExternalNavCommand.ReferenceLink,
            is ExternalNavCommand.CaptureText -> Unit
        }
    }

    // ONE canonical wardrobe collection for the whole Closie nav graph.
    //
    // This is the single point at which the wardrobe is read into Compose inside Closie. Every
    // screen downstream that needs multiple surfaces (items + wear + wash, outfits + items, …)
    // reads LocalWardrobeSnapshot.current, which is the same instance for all of them — so no two
    // screens can be composed from two different generations. See [WardrobeRepository.snapshot]
    // for why five per-surface StateFlows could not give this guarantee.
    //
    // The collection lives in ClosieNavHost, not in LifeOsRoot: Life OS does not read the wardrobe,
    // and the snapshot is only meaningful while a Closie NavHost is actually composed. When the
    // user navigates back to Life OS, this composition leaves and the collector stops — there is
    // no stale subscription keeping a snapshot alive that nobody renders.
    val wardrobe by repository.snapshot.collectAsState()

    CompositionLocalProvider(LocalWardrobeSnapshot provides wardrobe) {
    Scaffold(
        containerColor = ClosieColor.Canvas,
        bottomBar = {
            if (showBottomBar) {
                ClosieBottomNavigation(
                    currentRoute = currentRoute,
                    onSelect = { nav.navigateTopLevel(it) }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = startDestination,
            modifier = Modifier.padding(padding)
        ) {
            composable(TopLevel.Home.route) {
                HomeScreen(
                    repository,
                    onSettings = { nav.navigate(Route.Settings.route) },
                    onOpenCloset = { nav.navigateTopLevel(TopLevel.Closet) },
                    onOpenOotd = { nav.navigateTopLevel(TopLevel.Ootd) }
                )
            }

            composable(TopLevel.Closet.route) {
                ClosetScreen(
                    repository,
                    open = { nav.navigate(Route.Detail.route.replace("{id}", it)) },
                    add = { status -> nav.navigate(Route.Add.route.replace("{status}", status.name)) },
                    // Null unless Closie was entered from Life OS. Passing the label rather than
                    // the whole mode keeps the wording decision in the shell's vocabulary and
                    // leaves ClosetScreen with a single, testable "show a back control or not".
                    onReturnToLifeOs = if (showReturnAffordance) backOrExit else null,
                    returnLabel = onExitLabel
                )
            }

            composable(TopLevel.Ootd.route) { OotdScreen(repository) { backOrExit() } }

            composable(TopLevel.Outfits.route) {
                OutfitListScreen(
                    repository,
                    open = { nav.navigate(Route.OutfitEdit.route.replace("{id}", it)) },
                    openDraft = { nav.navigate(Route.OutfitDraftEdit.route.replace("{draftId}", it)) },
                    create = { nav.navigate(Route.OutfitEdit.route.replace("{id}", "new")) },
                    back = backOrExit
                )
            }

            composable(
                Route.Add.route,
                arguments = listOf(navArgument("status") { type = NavType.StringType })
            ) { entry ->
                EditorScreen(
                    repository,
                    null,
                    entry.arguments?.getString("status") ?: "OWNED"
                ) { backOrExit() }
            }

            composable(
                Route.Detail.route,
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { entry ->
                DetailScreen(
                    repository,
                    entry.arguments?.getString("id").orEmpty(),
                    edit = { nav.navigate(Route.Edit.route.replace("{id}", it)) },
                    back = backOrExit
                )
            }

            composable(
                Route.Edit.route,
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { entry ->
                EditorScreen(
                    repository,
                    entry.arguments?.getString("id"),
                    null
                ) { backOrExit() }
            }

            composable(
                Route.OutfitEdit.route,
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { entry ->
                val id = entry.arguments?.getString("id")
                OutfitStudioScreen(repository, id?.takeIf { it != "new" }) { backOrExit() }
            }

            composable(
                Route.OutfitDraftEdit.route,
                arguments = listOf(navArgument("draftId") { type = NavType.StringType })
            ) { entry ->
                val draftId = entry.arguments?.getString("draftId")
                OutfitStudioScreen(repository, outfitId = null, draftId = draftId) { backOrExit() }
            }

            composable(Route.Settings.route) {
                // Same rule as the system back key: pop inside Closie, and when 设置 *is* the start
                // destination there is nothing to pop, so hand the press to the Life OS shell.
                DataSettingsScreen(
                    repo = repository,
                    back = backOrExit,
                    lifeDatabase = lifeDatabase
                )
            }
        }
    }
    }   // CompositionLocalProvider(LocalWardrobeSnapshot provides wardrobe)
}

private fun NavHostController.navigateTopLevel(tab: TopLevel) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun ClosieBottomNavigation(
    currentRoute: String?,
    onSelect: (TopLevel) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = ClosieColor.Paper,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .heightIn(min = 60.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopLevel.entries.forEach { tab ->
                val selected = tab.route == currentRoute
                val tint = if (selected) ClosieColor.Ink else ClosieColor.Stone
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = { onSelect(tab) })
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = tint,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(tab.label, style = MaterialTheme.typography.labelSmall, color = tint)
                    Box(
                        modifier = Modifier
                            .width(if (selected) 16.dp else 0.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(ClosieColor.Fig)
                    )
                }
            }
        }
    }
}
