package com.qq.closie.navigation

import com.qq.closie.life.capture.ExternalIntentRouter
import com.qq.closie.life.capture.SharedContent

/** Pure route resolution. Intake persistence is owned by ExternalIntakeViewModel. */
object ExternalCommandResolver {
    fun parse(action: String?, text: String?, editId: String?, openAdd: Boolean, nonce: Long): ExternalNavCommand? = when {
        action == "android.intent.action.SEND" && !text.isNullOrBlank() -> when (val shared = ExternalIntentRouter.route(text)) {
            is SharedContent.ProductLink -> ExternalNavCommand.ProductImport(shared.text, nonce)
            is SharedContent.ReferenceLink -> ExternalNavCommand.ReferenceLink(shared.text, shared.url, nonce)
            is SharedContent.PlainText -> ExternalNavCommand.CaptureText(shared.text, nonce)
        }
        !editId.isNullOrBlank() -> ExternalNavCommand.Edit(editId, nonce)
        openAdd -> ExternalNavCommand.Add(nonce)
        else -> null
    }

    fun isClosetCommand(command: ExternalNavCommand) = command is ExternalNavCommand.ProductImport ||
        command is ExternalNavCommand.Edit || command is ExternalNavCommand.Add
}
