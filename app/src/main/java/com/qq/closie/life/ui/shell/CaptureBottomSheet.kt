package com.qq.closie.life.ui.shell

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
import com.qq.closie.life.ui.theme.LifeTheme
import com.qq.closie.life.ui.theme.LifeType

/** What the user chose on the capture sheet. The shell owns the actual side effects. */
sealed interface CaptureAction {
    /** Start the existing MediaProjection-based quick capture. */
    data object QuickCapture : CaptureAction

    /** Import an existing photo from the gallery. */
    data object FromGallery : CaptureAction

    /** Save whatever text is currently on the clipboard. */
    data object PasteText : CaptureAction

    /** Save a pasted/shared link for later parsing. */
    data object Link : CaptureAction

    /** Create an empty record the user fills in by hand. */
    data object ManualRecord : CaptureAction
}

private data class CaptureOption(
    val action: CaptureAction,
    val title: String,
    val subtitle: String,
    val enabled: Boolean = true
)

/**
 * v0.3.0 availability — **every row is live.**
 *
 *  - 快速采集 launches the existing QuickCaptureActivity.
 *  - 从相册 opens the system Photo Picker and copies the chosen image into Life OS's own media
 *    directory. It was disabled in v0.1 ("即将开放"); it is now the second-most useful row in the
 *    sheet, because a screenshot the user already has is the most common thing they want to keep.
 *  - 粘贴文本 / 链接 write a capture row straight from the clipboard.
 *  - 手动记录 opens the record editor **immediately** rather than inserting a silent empty row —
 *    see the note on [CaptureAction.ManualRecord].
 */
private val CAPTURE_OPTIONS = listOf(
    CaptureOption(
        action = CaptureAction.QuickCapture,
        title = "快速采集",
        subtitle = "截屏后立刻收进记录"
    ),
    CaptureOption(
        action = CaptureAction.FromGallery,
        title = "从相册",
        subtitle = "选择一张照片存进记录"
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

/**
 * The ＋ sheet — a light tool panel, not a launcher.
 *
 * Five rows separated by hairlines: no coloured icon grid, no oversized buttons, no row of cards.
 * Every enabled row is a real [LifeListRow] with `clickable` applied before its padding, so the
 * whole row is the target; 快速采集 genuinely starts the existing quick-capture flow.
 * 从相册 passes `enabled = false`, which removes the ripple and the focus ring entirely — a no-op
 * lambda would still animate and read as "tapped, but broken".
 */
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
    LifeTheme {
        CaptureBottomSheet(onDismiss = {}, onAction = {})
    }
}
