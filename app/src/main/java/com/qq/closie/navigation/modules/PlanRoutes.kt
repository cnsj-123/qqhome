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
import com.qq.closie.navigation.returnToLifeHome
import com.qq.closie.navigation.returnWithinLifeModule
import com.qq.closie.navigation.LifeOsRoute

fun NavGraphBuilder.planRoutes(nav: NavHostController, container: LifeContainer) {
    composable(LifeOsRoute.PLAN) { entry ->
        val owner = remember(entry) { nav.getBackStackEntry(LifeOsRoute.GRAPH) }
        val vm: PlanViewModel = viewModel(owner, key = "plans", factory = PlanViewModelFactory(container))
        PlanScreen(vm, onAddPlan = { nav.navigate(LifeOsRoute.planEdit(LifeOsRoute.NEW_ID)) },
            onEditPlan = { nav.navigate(LifeOsRoute.planEdit(it.id)) }, onBack = { nav.returnToLifeHome() })
    }
    composable(LifeOsRoute.PLAN_EDIT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
        val owner = remember(entry) { nav.getBackStackEntry(LifeOsRoute.GRAPH) }
        val vm: PlanViewModel = viewModel(owner, key = "plans", factory = PlanViewModelFactory(container))
        val id = entry.arguments?.getString("id").orEmpty()
        val item by remember(id) { vm.observeItem(id) }.collectAsStateWithLifecycle(initialValue = null)
        val homeVisible: Boolean? by remember(id) {vm.observeHomeVisible(id)}.collectAsStateWithLifecycle(initialValue=null)
        if(id!=LifeOsRoute.NEW_ID && homeVisible==null) {
            com.qq.closie.ui.lifeos.components.LifeEmptyState("正在读取计划的首页展示设置…")
            return@composable
        }
        PlanEditScreen(plan = item, initialHomeVisible=homeVisible==true,onSave = { title, note, date, clearDate,showOnHome ->
            if (id == LifeOsRoute.NEW_ID) vm.create(title, note, date,showOnHome)
            else item?.let { vm.update(it, title, note, date, clearDate,showOnHome) }
            nav.returnWithinLifeModule()
        }, onBack = { nav.returnWithinLifeModule() })
    }
}
