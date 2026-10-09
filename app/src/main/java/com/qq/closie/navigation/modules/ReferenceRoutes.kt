package com.qq.closie.navigation.modules

import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.ui.reference.*
import com.qq.closie.life.ui.reading.ReadingScreen
import com.qq.closie.navigation.returnToLifeHome
import com.qq.closie.navigation.returnWithinLifeModule
import com.qq.closie.navigation.LifeOsRoute

fun NavGraphBuilder.referenceRoutes(nav: NavHostController, container: LifeContainer, onCapture: () -> Unit) {
    composable(LifeOsRoute.REFERENCE) { entry ->
        val owner = remember(entry) { nav.getBackStackEntry(LifeOsRoute.GRAPH) }
        val vm: ReferenceViewModel = viewModel(owner, key = "reference", factory = ReferenceViewModelFactory(container))
        ReferenceLibraryScreen(vm, onOpenItem = { nav.navigate(LifeOsRoute.reference(it)) },
            onBack = { nav.returnToLifeHome() }, onAddItem = onCapture)
    }
    val args = listOf(navArgument("id") { type = NavType.StringType })
    composable(LifeOsRoute.REFERENCE_DETAIL, arguments = args) { entry ->
        val owner = remember(entry) { nav.getBackStackEntry(LifeOsRoute.GRAPH) }
        val vm: ReferenceViewModel = viewModel(owner, key = "reference", factory = ReferenceViewModelFactory(container))
        ReferenceDetailScreen(vm, entry.arguments?.getString("id").orEmpty(), onBack = { nav.returnWithinLifeModule() },
            onEdit = { nav.navigate(LifeOsRoute.referenceEdit(it)) })
    }
    composable(LifeOsRoute.REFERENCE_EDIT, arguments = args) { entry ->
        val owner = remember(entry) { nav.getBackStackEntry(LifeOsRoute.GRAPH) }
        val vm: ReferenceViewModel = viewModel(owner, key = "reference", factory = ReferenceViewModelFactory(container))
        ReferenceEditScreen(vm, entry.arguments?.getString("id").orEmpty(),
            onDone = { nav.returnWithinLifeModule() }, onBack = { nav.returnWithinLifeModule() })
    }
    composable(LifeOsRoute.READING) { entry ->
        val owner = remember(entry) { nav.getBackStackEntry(LifeOsRoute.GRAPH) }
        val vm: ReferenceViewModel = viewModel(owner, key = "reference", factory = ReferenceViewModelFactory(container))
        ReadingScreen(vm, onOpenItem = { nav.navigate(LifeOsRoute.reference(it)) },
            onAddReading = onCapture, onBack = { nav.returnToLifeHome() })
    }
}
