package com.qq.closie.navigation.modules

import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.ui.plan.*
import com.qq.closie.navigation.LifeOsRoute

fun NavGraphBuilder.planRoutes(nav: NavHostController, container: LifeContainer) {
    composable(LifeOsRoute.PLAN) { entry ->
        val owner = remember(entry) { nav.getBackStackEntry(LifeOsRoute.GRAPH) }
        val vm: PlanViewModel = viewModel(owner, key = "plans", factory = PlanViewModelFactory(container))
        PlanScreen(vm, onAddPlan = { nav.navigate(LifeOsRoute.planEdit(LifeOsRoute.NEW_ID)) },
            onEditPlan = { nav.navigate(LifeOsRoute.planEdit(it.id)) }, onBack = { nav.popBackStack() })
    }
    composable(LifeOsRoute.PLAN_EDIT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
        val owner = remember(entry) { nav.getBackStackEntry(LifeOsRoute.GRAPH) }
        val vm: PlanViewModel = viewModel(owner, key = "plans", factory = PlanViewModelFactory(container))
        val id = entry.arguments?.getString("id").orEmpty()
        val item by remember(id) { vm.observeItem(id) }.collectAsStateWithLifecycle(initialValue = null)
        PlanEditScreen(plan = item, onSave = { title, note, date, clearDate ->
            if (id == LifeOsRoute.NEW_ID) vm.create(title, note, date)
            else item?.let { vm.update(it, title, note, date, clearDate) }
            nav.popBackStack()
        }, onBack = { nav.popBackStack() })
    }
}
