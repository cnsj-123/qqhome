package com.xiaoming.closie.navigation

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
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.closet.ClosetScreen
import com.xiaoming.closie.ui.detail.DetailScreen
import com.xiaoming.closie.ui.editor.EditorScreen
import com.xiaoming.closie.ui.home.HomeScreen
import com.xiaoming.closie.ui.ootd.OotdScreen
import com.xiaoming.closie.ui.outfit.OutfitListScreen
import com.xiaoming.closie.ui.outfit.OutfitStudioScreen
import com.xiaoming.closie.ui.settings.DataSettingsScreen
import com.xiaoming.closie.ui.theme.ClosieColor

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
        val entries = listOf(Home, Closet, Ootd, Outfits)
    }
}

sealed class Route(val route: String) {
    data object Detail : Route("detail/{id}")
    data object Edit : Route("edit/{id}")
    data object Add : Route("add/{status}")
    data object Settings : Route("settings")
    data object OutfitEdit : Route("outfit_edit/{id}")
}

@Composable
fun ClosieNavHost(repository: WardrobeRepository) {
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val currentRoute = current?.destination?.route
    val showBottomBar = TopLevel.entries.any { it.route == currentRoute }

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
            startDestination = TopLevel.Home.route,
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
                    add = { status -> nav.navigate(Route.Add.route.replace("{status}", status.name)) }
                )
            }

            composable(TopLevel.Ootd.route) { OotdScreen(repository) { nav.popBackStack() } }

            composable(TopLevel.Outfits.route) {
                OutfitListScreen(
                    repository,
                    open = { nav.navigate(Route.OutfitEdit.route.replace("{id}", it)) },
                    create = { nav.navigate(Route.OutfitEdit.route.replace("{id}", "new")) },
                    back = { nav.popBackStack() }
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
                ) { nav.popBackStack() }
            }

            composable(
                Route.Detail.route,
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { entry ->
                DetailScreen(
                    repository,
                    entry.arguments?.getString("id").orEmpty(),
                    edit = { nav.navigate(Route.Edit.route.replace("{id}", it)) },
                    back = { nav.popBackStack() }
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
                ) { nav.popBackStack() }
            }

            composable(
                Route.OutfitEdit.route,
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { entry ->
                val id = entry.arguments?.getString("id")
                OutfitStudioScreen(repository, id?.takeIf { it != "new" }) { nav.popBackStack() }
            }

            composable(Route.Settings.route) { DataSettingsScreen(repository) { nav.popBackStack() } }
        }
    }
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
