package com.qq.closie.life.ui.capture

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.qq.closie.life.ui.components.LifeDivider
import com.qq.closie.life.ui.components.LifeListRow
import com.qq.closie.life.ui.theme.LifeColors
import com.qq.closie.life.ui.theme.LifeShape
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.ui.lifeos.theme.LifeOsTheme
import com.qq.closie.life.ui.theme.LifeType

sealed interface CaptureAction {

    data object QuickCapture : CaptureAction


    data object PasteText : CaptureAction

    data object Link : CaptureAction

    data object ManualRecord : CaptureAction
}

private data class CaptureOption(
    val action: CaptureAction,
    val title: String,
    val subtitle: String,
    val enabled: Boolean = true
)

private val CAPTURE_OPTIONS = listOf(
    CaptureOption(
        action = CaptureAction.QuickCapture,
        title = "快速采集",
        subtitle = "截屏后立刻收进记录"
    ),
    CaptureOption(
        action = CaptureAction.PasteText,
        title = "粘贴文本",
        subtitle = "保存剪贴板内容"
    ),
    CaptureOption(
        action = CaptureAction.Link,
        title = "链接",
        subtitle = "先存下，之后再解析"
    ),
    CaptureOption(
        action = CaptureAction.ManualRecord,
        title = "手动记录",
        subtitle = "直接写一条新记录"
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureBottomSheet(
    onDismiss: () -> Unit,
    onAction: (CaptureAction) -> Unit,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LifeColors.SurfaceRaised,
        shape = RoundedCornerShape(
            topStart = LifeShape.sheet,
            topEnd = LifeShape.sheet
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = LifeSpacing.lg)
        ) {
            Text(
                text = "记点什么",
                style = LifeType.PageTitle,
                color = LifeColors.TextPrimary,
                modifier = Modifier.padding(
                    horizontal = LifeSpacing.cardPadding,
                    vertical = LifeSpacing.xs
                )
            )
            Spacer(Modifier.height(LifeSpacing.xs))
            CAPTURE_OPTIONS.forEachIndexed { index, option ->
                if (index > 0) {
                    LifeDivider()
                }
                LifeListRow(
                    title = option.title,
                    subtitle = option.subtitle,
                    enabled = option.enabled,
                    onClick = if (option.enabled) ({ onAction(option.action) }) else null,
                    showChevron = false
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, name = "Capture sheet")
@Composable
private fun CaptureBottomSheetPreview() {
    LifeOsTheme {
        CaptureBottomSheet(onDismiss = {}, onAction = {})
    }
}
