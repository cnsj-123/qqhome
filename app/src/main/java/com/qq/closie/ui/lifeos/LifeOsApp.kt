package com.qq.closie.ui.lifeos

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qq.closie.ClosieApplication
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.navigation.ExternalCommandViewModel
import com.qq.closie.navigation.intake.ExternalIntakeCoordinator
import com.qq.closie.navigation.intake.ExternalIntakeViewModel
import com.qq.closie.navigation.modules.PresentationFactory
import com.qq.closie.ui.lifeos.components.LifeEmptyState
import com.qq.closie.ui.lifeos.theme.LifeOsTheme
import com.qq.closie.ui.lifeos.theme.LifeText
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors
import com.qq.closie.ui.lifeos.settings.AppearanceViewModel

/** Android composition root. Preferences can load independently of the business restore barrier. */
@Composable
fun LifeOsApp(app: ClosieApplication, external: ExternalCommandViewModel, onContentReady: () -> Unit = {}, onDarkAppearance: (Boolean) -> Unit) {
    val appearance: AppearanceViewModel = viewModel(factory = AppearanceViewModel.Factory(app.appearanceRepository))
    val selected by appearance.appearance.collectAsStateWithLifecycle()
    val startupStatus = remember { RestoreStartupGate.state.map { it.status }.distinctUntilChanged() }
    val restoreStatus by startupStatus.collectAsStateWithLifecycle(initialValue = RestoreStartupGate.state.value.status)
    val ready = restoreStatus == RestoreStartupGate.Status.READY
    if (!ready) {
        androidx.compose.runtime.SideEffect(onContentReady)
        LifeOsTheme { LifeEmptyState("恢复尚未完成，原数据已保留。请在系统设置中停止应用后重新打开，以重试启动恢复。") }
        return
    }
    val settings = selected
    if (settings == null) {
        androidx.compose.runtime.SideEffect(onContentReady)
        LifeOsTheme {
            val colors = LocalLifeOsColors.current
            Surface(Modifier.fillMaxSize(), color = colors.paper) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.qq.closie.R.drawable.lifeos_launch_cat), null, Modifier.size(72.dp))
                        Text("Life OS", style = com.qq.closie.life.ui.theme.LifeType.Wordmark, color = colors.ink)
                        Text("正在准备生活页", style = LifeText.caption, color = colors.inkSecondary)
                    }
                }
            }
        }
        return
    }
    val container = app.lifeContainer
    androidx.compose.runtime.SideEffect(onContentReady)
    val intake: ExternalIntakeViewModel = viewModel(factory = PresentationFactory(ExternalIntakeViewModel::class.java) {
        ExternalIntakeViewModel(ExternalIntakeCoordinator(container.captureRepository, container.referenceImporter),
            container.captureRepository)
    })
    val shell: LifeOsShellViewModel = viewModel(factory=PresentationFactory(LifeOsShellViewModel::class.java) {
        LifeOsShellViewModel(com.qq.closie.ui.lifeos.home.MediaHomeStateProvider(
            com.qq.closie.ui.lifeos.home.FinanceHomeStateProvider(container.financeRepository), container.homePhotoRepository))
    })
    val command by external.command.collectAsStateWithLifecycle()
    LifeOsRoot(app.wardrobeRepository, container, settings, shell, appearance, intake,
        command, external::consume, onDarkAppearance)
}
