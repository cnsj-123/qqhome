package com.xiaoming.closie.navigation

import com.xiaoming.closie.ExternalNavCommand
import java.net.URLEncoder

/** Pure resolution shared by the Activity, the Closet adapter, and JVM tests. */
object ExternalCommandResolver {
    fun parse(action: String?, sharedText: String?, editId: String?, openAdd: Boolean, nonce: Long): ExternalNavCommand? = when {
        action == "android.intent.action.SEND" && !sharedText.isNullOrBlank() -> ExternalNavCommand.Import(sharedText, nonce)
        !editId.isNullOrBlank() -> ExternalNavCommand.Edit(editId, nonce)
        openAdd -> ExternalNavCommand.Add(nonce)
        else -> null
    }

    fun closetDestination(command: ExternalNavCommand): String = when (command) {
        is ExternalNavCommand.Import, is ExternalNavCommand.Add -> "add/OWNED"
        is ExternalNavCommand.Edit -> "edit/" + URLEncoder.encode(command.itemId, "UTF-8").replace("+", "%20")
    }

    fun rootDestination(command: ExternalNavCommand) = when (command) {
        is ExternalNavCommand.Import, is ExternalNavCommand.Edit, is ExternalNavCommand.Add -> LifeOsRoute.CLOSET
    }
}

object LifeOsRoute {
    const val HOME = "life/home"
    const val CLOSET = "life/closet"
    const val CALENDAR = "life/calendar"
    const val MAP = "life/map"
    const val COMPANION = "life/companion"
    const val APPEARANCE = "life/appearance"
    const val CAPTURE = "life/capture"
    const val BACKUP = "life/backup"
    const val MODULE = "life/module/{moduleId}"
    const val DEFAULT = HOME

    fun module(id: String) = "life/module/$id"
}
