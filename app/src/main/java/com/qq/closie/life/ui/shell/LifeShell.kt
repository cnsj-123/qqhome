package com.qq.closie.life.ui.shell

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.qq.closie.ExternalNavCommand
import com.qq.closie.data.repository.WardrobeRepository
import com.qq.closie.life.capture.CaptureSource
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.ui.theme.LifeColors
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.life.ui.theme.LifeTheme
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.navigation.ClosieNavHost
import com.qq.closie.navigation.Route
import com.qq.closie.navigation.TopLevel
import com.qq.closie.ui.quickcapture.QuickCaptureActivity
import java.util.UUID
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

    companion object {
        val tabs = listOf(Home, Timeline, Modules, Me)
        val tabRoutes = tabs.map { it.route }
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

    // A share / deep-link intent must land inside Closie, which is where the editor lives. The
    // shell navigates there first; ClosieNavHost then performs the actual Edit/Add navigation and
    // calls onExternalCommandConsumed(). Without this the shell would sit on 首页 and silently drop
    // every shared link — a regression on the app's main entry point.
    LaunchedEffect(externalCommand) {
        if (externalCommand != null) {
            nav.navigate(LifeDestination.Closet.route) { launchSingleTop = true }
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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavHost(
                navController = nav,
                startDestination = LifeDestination.Home.route,
                modifier = modifier
            ) {
                composable(LifeDestination.Home.route) {
                    LifeTheme {
                        LifeHomeScreen(
                            captureRepository = captureRepository,
                            onQuickCapture = { showCaptureSheet = true },
                            modifier = Modifier.statusBarsPadding()
                        )
                    }
                }

                composable(LifeDestination.Timeline.route) {
                    LifeTheme {
                        TimelineScreen(
                            captureRepository = captureRepository,
                            modifier = Modifier.statusBarsPadding()
                        )
                    }
                }

                composable(LifeDestination.Modules.route) {
                    LifeTheme {
                        LifeModulesScreen(
                            onOpenCloset = { nav.navigate(LifeDestination.Closet.route) },
                            modifier = Modifier.statusBarsPadding()
                        )
                    }
                }

                composable(LifeDestination.Me.route) {
                    LifeTheme {
                        ProfileScreen(
                            onOpenSettings = { nav.navigate(LifeDestination.ClosetSettings.route) },
                            onOpenBackup = { nav.navigate(LifeDestination.ClosetSettings.route) },
                            modifier = Modifier.statusBarsPadding()
                        )
                    }
                }

                // Closie keeps the app theme — no LifeTheme wrapper here.
                composable(LifeDestination.Closet.route) {
                    ClosieNavHost(
                        repository = wardrobeRepository,
                        externalCommand = externalCommand,
                        onExternalCommandConsumed = onExternalCommandConsumed,
                        startDestination = TopLevel.Closet.route,
                        onExit = { nav.navigateLifeTab(LifeDestination.Modules) }
                    )
                }

                composable(LifeDestination.ClosetSettings.route) {
                    ClosieNavHost(
                        repository = wardrobeRepository,
                        startDestination = Route.Settings.route,
                        onExit = { nav.navigateLifeTab(LifeDestination.Me) }
                    )
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
                    scope.launch {
                        performCaptureAction(
                            context = context,
                            action = action,
                            create = { source, text, url ->
                                captureRepository.create(
                                    id = UUID.randomUUID().toString(),
                                    source = source,
                                    rawText = text,
                                    sourceUrl = url
                                )
                            }
                        )
                    }
                }
            )
        }
    }
}

private suspend fun performCaptureAction(
    context: Context,
    action: CaptureAction,
    create: suspend (CaptureSource, String?, String?) -> Boolean
) {
    when (action) {
        CaptureAction.QuickCapture -> {
            context.startActivity(Intent(context, QuickCaptureActivity::class.java))
        }

        CaptureAction.FromGallery -> Unit // disabled in the sheet; nothing to do

        CaptureAction.PasteText -> {
            val text = readClipboardText(context) ?: return
            create(CaptureSource.CLIPBOARD, text, null)
        }

        CaptureAction.Link -> {
            val text = readClipboardText(context) ?: return
            create(CaptureSource.SHARE, text, text.takeIf { looksLikeUrl(it) })
        }

        CaptureAction.ManualRecord -> {
            create(CaptureSource.MANUAL, null, null)
        }
    }
}

private fun readClipboardText(context: Context): String? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return null
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
}

private fun looksLikeUrl(value: String): Boolean =
    value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("https://", ignoreCase = true) ||
        value.contains("://")

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
                // Reserve exactly the caption line box (18sp) so the ＋ glyph sits on the same
                // baseline as the labelled tabs. An empty Text would work too, but it leaves a
                // pointless node in the tree.
                Spacer(Modifier.height(18.dp))
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
            style = LifeType.Caption,
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
