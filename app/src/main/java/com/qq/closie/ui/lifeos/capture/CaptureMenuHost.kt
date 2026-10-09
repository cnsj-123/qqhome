package com.qq.closie.ui.lifeos.capture

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.qq.closie.life.ui.capture.CaptureAction
import com.qq.closie.life.ui.capture.CaptureBottomSheet
import com.qq.closie.navigation.intake.ExternalIntakeViewModel
import com.qq.closie.ui.quickcapture.QuickCaptureActivity

/** Android input adapters only; repositories and intake writes belong to the controller. */
@Composable
fun CaptureMenuHost(intake: ExternalIntakeViewModel, onDismiss: () -> Unit, onManual: () -> Unit) {
    val context = LocalContext.current
    CaptureBottomSheet(onDismiss = onDismiss, onAction = { action ->
        onDismiss()
        when (action) {
            CaptureAction.QuickCapture -> context.startActivity(Intent(context, QuickCaptureActivity::class.java))
            CaptureAction.ManualRecord -> onManual()
            CaptureAction.PasteText, CaptureAction.Link -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                    ?.coerceToText(context)?.toString().orEmpty()
                intake.paste(text, asLink = action == CaptureAction.Link)
            }
        }
    })
}
