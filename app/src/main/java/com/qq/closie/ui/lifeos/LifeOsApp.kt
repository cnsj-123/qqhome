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
    val restoreTask by app.backupTasks.state.collectAsStateWithLifecycle()
    val ready = restoreStatus == RestoreStartupGate.Status.READY
    if (!ready) {
        androidx.compose.runtime.SideEffect(onContentReady)
        LifeOsTheme {
            if (restoreStatus == RestoreStartupGate.Status.RESTORING)
                LifeStartupFeedback("正在恢复数据", "正在核对备份并替换本地数据，请保持应用开启")
            else LifeEmptyState("恢复未完成，业务数据已暂停访问。请停止应用后重新打开，以重试启动恢复。")
        }
        return
    }
    val settings = selected
    if (settings == null) {
        androidx.compose.runtime.SideEffect(onContentReady)
        LifeOsTheme { LifeStartupFeedback("正在准备生活页", "正在读取外观与本地数据") }
        return
    }
    if (restoreTask.result != null) LifeOsTheme {
        androidx.compose.material3.AlertDialog(onDismissRequest = app.backupTasks::acknowledge,
            title = { Text(if (restoreTask.failed) "恢复未完成" else "备份恢复") },
            text = { Text(restoreTask.result.orEmpty()) },
            confirmButton = { androidx.compose.material3.TextButton(app.backupTasks::acknowledge) { Text("知道了") } })
    }
    val initialized by androidx.compose.runtime.produceState<Result<Pair<com.qq.closie.life.data.LifeContainer,com.qq.closie.data.repository.WardrobeRepository>>?>(null,app) {
        value=try {Result.success(loadLifeShellResources(app))}
        catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
        catch(error:Exception){Result.failure(error)}
    }
    val resources=initialized?.getOrNull()
    if(resources==null) {
        LifeOsTheme {
            if(initialized==null) LifeStartupFeedback("正在打开生活页","正在读取本地衣橱与账本")
            else LifeEmptyState("本地数据未能打开，请重新打开应用重试。原数据未被替换。")
        }
        return
    }
    val container = resources.first
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
    LifeOsRoot(resources.second, container, settings, shell, appearance, intake,
        command, external::consume, onDarkAppearance)
}
