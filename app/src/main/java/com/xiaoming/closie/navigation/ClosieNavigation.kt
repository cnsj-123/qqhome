package com.xiaoming.closie.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xiaoming.closie.data.model.ItemStatus
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.closet.ClosetScreen
import com.xiaoming.closie.ui.detail.DetailScreen
import com.xiaoming.closie.ui.editor.EditorScreen
import com.xiaoming.closie.ui.home.HomeScreen
import com.xiaoming.closie.ui.ootd.OotdScreen
import com.xiaoming.closie.ui.outfit.OutfitListScreen
import com.xiaoming.closie.ui.outfit.OutfitStudioScreen

sealed class Destination(val route: String) {
    data object Home : Destination("home")
    data object Owned : Destination("owned")
    data object Returned : Destination("returned")
    data object Ootd : Destination("ootd")
    data object Add : Destination("add/{status}")
    data object Detail : Destination("detail/{id}")
    data object Edit : Destination("edit/{id}")
    data object Outfits : Destination("outfits")
    data object OutfitEdit : Destination("outfit_edit/{id}")
}

@Composable
fun ClosieNavHost(repository: WardrobeRepository) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Destination.Home.route) {
        composable(Destination.Home.route) { HomeScreen(repository) { nav.navigate(it) } }

        composable(Destination.Ootd.route) { OotdScreen(repository) { nav.popBackStack() } }

        composable(Destination.Owned.route) {
            ClosetScreen(
                repository, ItemStatus.OWNED,
                open = { nav.navigate(Destination.Detail.route.replace("{id}", it)) },
                add = { nav.navigate(Destination.Add.route.replace("{status}", "OWNED")) },
                back = { nav.popBackStack() }
            )
        }

        composable(Destination.Returned.route) {
            ClosetScreen(
                repository, ItemStatus.RETURNED,
                open = { nav.navigate(Destination.Detail.route.replace("{id}", it)) },
                add = { nav.navigate(Destination.Add.route.replace("{status}", "RETURNED")) },
                back = { nav.popBackStack() }
            )
        }

        composable(Destination.Add.route, arguments = listOf(navArgument("status") { type = NavType.StringType })) { entry ->
            EditorScreen(repository, null, entry.arguments?.getString("status") ?: "OWNED") { nav.popBackStack() }
        }

        composable(Destination.Detail.route, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            DetailScreen(
                repository, entry.arguments?.getString("id").orEmpty(),
                edit = { nav.navigate(Destination.Edit.route.replace("{id}", it)) },
                back = { nav.popBackStack() }
            )
        }

        composable(Destination.Edit.route, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            EditorScreen(repository, entry.arguments?.getString("id"), null) { nav.popBackStack() }
        }

        composable(Destination.Outfits.route) {
            OutfitListScreen(
                repository,
                open = { nav.navigate(Destination.OutfitEdit.route.replace("{id}", it)) },
                create = { nav.navigate(Destination.OutfitEdit.route.replace("{id}", "new")) },
                back = { nav.popBackStack() }
            )
        }

        composable(Destination.OutfitEdit.route, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id")
            OutfitStudioScreen(repository, id?.takeIf { it != "new" }) { nav.popBackStack() }
        }
    }
}
