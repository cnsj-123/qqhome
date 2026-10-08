package com.xiaoming.closie.ui.lifeos

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.xiaoming.closie.ExternalNavCommand
import com.xiaoming.closie.data.appearance.AppearanceSettings
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.navigation.LifeOsNavHost
import com.xiaoming.closie.ui.lifeos.theme.LifeOsTheme
import com.xiaoming.closie.ui.lifeos.settings.AppearanceViewModel
import com.xiaoming.closie.ui.lifeos.theme.LocalLifeOsColors
import com.xiaoming.closie.ui.lifeos.drawer.LifeOsDrawer
import com.xiaoming.closie.ui.lifeos.components.reducedLifeMotion
import com.xiaoming.closie.navigation.LifeOsRoute
import com.xiaoming.closie.ui.theme.ClosieColor
import kotlinx.coroutines.launch

/** Product root. Closet is a destination inside this shell, with its original storage owner. */
@Composable
fun LifeOsRoot(
    repository: WardrobeRepository,
    appearance: AppearanceSettings,
    shellViewModel: LifeOsShellViewModel,
    appearanceViewModel: AppearanceViewModel,
    externalCommand: ExternalNavCommand?,
    onExternalCommandConsumed: () -> Unit,
    onDarkAppearance: (Boolean) -> Unit = {}
) {
    LifeOsTheme(appearance) {
        val colors = LocalLifeOsColors.current
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val legacySurface = entry?.destination?.route in setOf(LifeOsRoute.CLOSET, LifeOsRoute.BACKUP)
        val home by shellViewModel.home.collectAsStateWithLifecycle()
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val reduced = reducedLifeMotion()
        val scope = rememberCoroutineScope()
        val drawerWidth = (LocalConfiguration.current.screenWidthDp * .79f).coerceAtMost(380f).dp
        suspend fun closeDrawer() {
            if (reduced) drawer.snapTo(DrawerValue.Closed) else drawer.close()
        }
        fun openDrawer() {
            scope.launch { if (reduced) drawer.snapTo(DrawerValue.Open) else drawer.open() }
        }
        LaunchedEffect(entry?.destination?.route) {
            if (entry?.destination?.route != LifeOsRoute.HOME && drawer.isOpen) closeDrawer()
        }
        SideEffect { onDarkAppearance(colors.isDark && !legacySurface) }
        Surface(Modifier.fillMaxSize(), color = if (legacySurface) ClosieColor.Canvas else colors.paper) {
            Box(Modifier.safeDrawingPadding()) {
                ModalNavigationDrawer(
                    drawerState = drawer,
                    // Closed drawers never claim Android's edge/back gesture to open.
                    gesturesEnabled = drawer.isOpen,
                    scrimColor = colors.shade.copy(alpha = .16f),
                    drawerContent = {
                        // State overload supplies native predictive-back handling (Material3 1.3.1).
                        ModalDrawerSheet(drawerState = drawer, modifier = Modifier.width(drawerWidth),
                            drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                            drawerContainerColor = colors.paperSecondary, drawerContentColor = colors.ink) {
                            LifeOsDrawer(
                                onOpenModule = { module -> nav.navigate(module.route) { launchSingleTop = true } },
                                onClose = { scope.launch { closeDrawer() } }
                            )
                        }
                    }
                ) {
                    LifeOsNavHost(repository, externalCommand, onExternalCommandConsumed, nav, home,
                        shellViewModel::selectDate, appearanceViewModel, ::openDrawer)
                }
            }
        }
    }
}
