package com.qq.closie.ui.lifeos.drawer

private val defaultFirst = listOf("capture", "finance", "calendar", "closet", "inbox", "plans")
internal fun orderedModules(ids: List<String>): List<LifeModule> {
    val all = LifeModules.groups.flatMap { it.modules }.associateBy { it.id }
    return ((ids.ifEmpty { defaultFirst }) + all.keys).distinct().mapNotNull(all::get)
}
