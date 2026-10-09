package com.qq.closie.navigation.modules

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.ui.capture.*
import com.qq.closie.navigation.LifeOsRoute

fun NavGraphBuilder.captureRoutes(nav: NavHostController, container: LifeContainer,
    onCapture: () -> Unit, onFileToLibrary: (String) -> Unit) {
    composable(LifeOsRoute.CAPTURE) {
        val vm: CaptureInboxViewModel = viewModel(factory = PresentationFactory(CaptureInboxViewModel::class.java) {
            CaptureInboxViewModel(container.captureRepository)
        })
        CaptureInboxScreen(vm, onOpenRecord = { nav.navigate(LifeOsRoute.capture(it)) },
            onBack = { nav.popBackStack() }, onCapture = onCapture)
    }
    composable(LifeOsRoute.CAPTURE_DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
        val id = entry.arguments?.getString("id").orEmpty()
        val vm: CaptureDetailViewModel = viewModel(factory = PresentationFactory(CaptureDetailViewModel::class.java) {
            CaptureDetailViewModel(id, container.captureRepository, container.mediaRepository, entry.savedStateHandle)
        })
        CaptureDetailScreen(vm, onBack = { nav.popBackStack() }, onFileToLibrary = onFileToLibrary)
    }
}
